package com.roundtrace.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.core.data.RepositoryError
import com.roundtrace.app.core.data.RoomMatchRepository
import com.roundtrace.app.core.database.EventCodec
import com.roundtrace.app.core.database.RoundTraceDatabase
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.QuestStarted
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.util.SequentialIdGenerator
import com.roundtrace.app.core.util.fixedClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C-1：修订的事务性复核与并发保护。
 *
 * 修订是「先撤销旧版本、再插入新版本」两步。如果只有事务**外**的检查，
 * 两个并发请求会同时通过检查、都认为目标有效，然后：
 *
 * - 最坏情况：插入两条新版本 → 同一位置两条 active → INV-1 被破坏（读路径只能靠去重兜底）；
 * - 或者撤销命中 0 行却仍然插入 → 一样留下重复事实。
 *
 * 因此这里必须证明 **最多一个成功**，且失败方拿到的是**可理解的错误**而不是崩溃。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RevisionConcurrencyTest {

    private lateinit var database: RoundTraceDatabase
    private lateinit var repository: RoomMatchRepository
    private val codec = EventCodec()
    private val clock = fixedClock(1_700_000_000_000L)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RoundTraceDatabase::class.java
        ).allowMainThreadQueries().build()

        val registry = DefaultGameRulesRegistry()
        repository = RoomMatchRepository(
            database = database,
            gameRulesRegistry = registry,
            reducer = registry.avalonReducer,
            codec = codec,
            idGenerator = SequentialIdGenerator("id"),
            clock = clock
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun createMatch(): MatchId = repository.createMatch(
        MatchCreationDraft(
            title = "并发修订测试",
            playerCount = 8,
            roleSlots = AvalonPreset.standardPreset(8),
            mySeat = 1,
            initialLeaderSeat = 1,
            participants = MatchCreationDraft.defaultParticipants(8),
            presetId = AvalonPreset.presetId(8),
            rulesetId = AvalonPreset.RULESET_ID,
            rulesetVersion = AvalonPreset.RULESET_VERSION
        )
    )

    private fun questStarted(id: String, questNo: Int, leaderSeat: Int): QuestStarted =
        QuestStarted(
            eventId = EventId(id),
            occurredOrder = 0,
            recordedAt = 0L,
            questNo = questNo,
            leaderSeat = leaderSeat
        )

    private suspend fun seedFiveQuests(matchId: MatchId) {
        (1..5).forEach { position ->
            val result = repository.appendEvent(
                matchId,
                questStarted("e-$position", questNo = position, leaderSeat = 1)
            )
            check(result.isSuccess) { "播种失败：${result.error}" }
        }
    }

    // ————————————————————————————————————————————————————————
    // 并发：同一目标只能成功一次
    // ————————————————————————————————————————————————————————

    @Test
    fun concurrentRevisionsOnSameTargetOnlyOneWins() = runBlocking {
        val matchId = createMatch()
        seedFiveQuests(matchId)

        val startGate = CompletableDeferred<Unit>()

        val results = coroutineScope {
            val first = async(Dispatchers.IO) {
                startGate.await()
                repository.reviseEvent(
                    matchId,
                    EventId("e-5"),
                    questStarted("e-5-v2a", questNo = 5, leaderSeat = 2)
                )
            }
            val second = async(Dispatchers.IO) {
                startGate.await()
                repository.reviseEvent(
                    matchId,
                    EventId("e-5"),
                    questStarted("e-5-v2b", questNo = 5, leaderSeat = 3)
                )
            }
            startGate.complete(Unit)
            listOf(first.await(), second.await())
        }

        assertEquals(
            "两个并发修订必须**恰好一个**成功，实际=${results.map { it.error }}",
            1,
            results.count { it.isSuccess }
        )
        assertEquals(
            "失败方必须拿到明确的冲突错误（不是崩溃）",
            1,
            results.count { it.error == RepositoryError.INVALID_SUPERSEDE_TARGET }
        )

        val active = database.matchEventDao().listActive(matchId.raw)
        val atPositionFive = active.filter { it.logicalPosition == 5 }
        assertEquals(
            "同一逻辑位置只能有 1 条 active（INV-1），实际=${atPositionFive.map { it.id }}",
            1,
            atPositionFive.size
        )
        assertEquals(
            "胜者的 revisionSeq 必须是 1",
            1,
            atPositionFive.first().revisionSeq
        )
        assertEquals(
            "胜者必须继承原位置 5",
            5,
            atPositionFive.first().logicalPosition
        )
        // 败者必须**一行都没写**：两个候选新版本里，只允许存在胜者那一个
        val all = database.matchEventDao().listAll(matchId.raw)
        val presentNewVersions = listOf("e-5-v2a", "e-5-v2b").filter { candidate ->
            all.any { it.id == candidate }
        }
        assertEquals(
            "只有胜者的新版本落库，败者不得留下任何行，实际落库=${presentNewVersions}",
            1,
            presentNewVersions.size
        )
    }

    // ————————————————————————————————————————————————————————
    // 目标已失效：一行都不许写
    // ————————————————————————————————————————————————————————

    @Test
    fun revisingAlreadyRetractedTargetWritesNothing() = runBlocking {
        val matchId = createMatch()
        seedFiveQuests(matchId)

        // 先把位置 5 撤销掉
        repository.retractEvent(matchId, EventId("e-5"))
        val beforeAll = database.matchEventDao().listAll(matchId.raw).size

        val result = repository.reviseEvent(
            matchId,
            EventId("e-5"),
            questStarted("e-5-v2", questNo = 5, leaderSeat = 2)
        )

        assertEquals(
            "对已撤销的目标做修订必须被拒绝",
            RepositoryError.INVALID_SUPERSEDE_TARGET,
            result.error
        )
        assertEquals(
            "**一行都不许写入**",
            beforeAll,
            database.matchEventDao().listAll(matchId.raw).size
        )
        assertTrue(
            "不得出现新版本",
            database.matchEventDao().listAll(matchId.raw).none { it.id == "e-5-v2" }
        )
    }

    @Test
    fun revisingCrossMatchTargetWritesNothing() = runBlocking {
        val matchA = createMatch()
        val matchB = createMatch()
        // 两局各播种 1 条（id 全局唯一，故加前缀）
        repository.appendEvent(matchA, questStarted("a-e1", questNo = 1, leaderSeat = 1))
        repository.appendEvent(matchB, questStarted("b-e1", questNo = 1, leaderSeat = 1))
        val beforeA = database.matchEventDao().listAll(matchA.raw).size
        val beforeB = database.matchEventDao().listAll(matchB.raw).size

        val result = repository.reviseEvent(
            matchA,
            EventId("b-e1"),
            questStarted("a-shadow", questNo = 1, leaderSeat = 2)
        )

        assertEquals(
            "跨对局修订必须被拒绝",
            RepositoryError.INVALID_SUPERSEDE_TARGET,
            result.error
        )
        assertEquals("A 局行数不变", beforeA, database.matchEventDao().listAll(matchA.raw).size)
        assertEquals("B 局行数不变", beforeB, database.matchEventDao().listAll(matchB.raw).size)
        assertEquals(
            "B 局不得被撤销",
            0,
            database.matchEventDao().listAll(matchB.raw).count { it.retracted }
        )
    }

    // ————————————————————————————————————————————————————————
    // 语义保留：位置继承 / 版本递增
    // ————————————————————————————————————————————————————————

    @Test
    fun revisionKeepsPositionAndIncrementsSeq() = runBlocking {
        val matchId = createMatch()
        seedFiveQuests(matchId)

        val first = repository.reviseEvent(
            matchId,
            EventId("e-3"),
            questStarted("e-3-v2", questNo = 3, leaderSeat = 2)
        )
        assertTrue("首次修订应成功", first.isSuccess)

        val afterFirst = database.matchEventDao().listActive(matchId.raw)
            .first { it.logicalPosition == 3 }
        assertEquals("位置继承", 3, afterFirst.logicalPosition)
        assertEquals("版本从 0 → 1", 1, afterFirst.revisionSeq)

        // 再修订一次（目标是上一次的新版本）
        val second = repository.reviseEvent(
            matchId,
            EventId("e-3-v2"),
            questStarted("e-3-v3", questNo = 3, leaderSeat = 3)
        )
        assertTrue("二次修订应成功", second.isSuccess)

        val afterSecond = database.matchEventDao().listActive(matchId.raw)
            .first { it.logicalPosition == 3 }
        assertEquals("位置仍为 3", 3, afterSecond.logicalPosition)
        assertEquals("版本 1 → 2", 2, afterSecond.revisionSeq)
        assertEquals(
            "位置 3 仍只有一条 active",
            1,
            database.matchEventDao().listActive(matchId.raw).count { it.logicalPosition == 3 }
        )
    }
}
