package com.roundtrace.app.replay

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.core.data.RoomMatchRepository
import com.roundtrace.app.core.database.EventCodec
import com.roundtrace.app.core.database.RoundTraceDatabase
import com.roundtrace.app.core.database.dao.EventSlot
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.GamePosition
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.QuestStarted
import com.roundtrace.app.core.replay.ReplayEngine
import com.roundtrace.app.core.replay.TimelineBuilder
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.util.SequentialIdGenerator
import com.roundtrace.app.core.util.fixedClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RT-003 Step 3 的核心语义测试（migration-plan §17.2 的 U1–U4）。
 *
 * 这一步是 RT-003 里**唯一会改变用户可观察行为**的地方，所以每条语义都要钉死：
 *
 * | ID | 语义 |
 * |---|---|
 * | U1 | `upTo` = **逻辑位置上界**：`upTo=5` 含位置 5 的修订版本，`upTo=4` 不含 |
 * | U2 | 时间线里修订条目回到**原位置**（索引 4），不是排在末尾 |
 * | U3 | `GamePosition(5)` 在修订前后都指向「位置 5 上的有效版本」，**位置值不漂移** |
 * | U4 | 中间位置修订（`e-3`）→ 顺序回到 `e-1,e-2,e-3-v2,e-4,e-5` |
 *
 * 这些断言全部走真实的 `RoomMatchRepository` + `reviseWithSlot`，
 * 因为「修订后位置不变」是写入路径与读路径配合的结果，单测任一侧都证明不了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RevisionOrderSemanticsTest {

    private lateinit var database: RoundTraceDatabase
    private lateinit var repository: RoomMatchRepository
    private val codec = EventCodec()
    private val clock = fixedClock(1_700_000_000_000L)
    private val reducer = DefaultGameRulesRegistry().avalonReducer

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
            title = "顺序语义测试",
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

    /** 追加一条 `QuestStarted`（第 questNo 个任务、指定队长）。 */
    private suspend fun append(matchId: MatchId, id: String, questNo: Int, leaderSeat: Int): EventSlot =
        database.matchEventDao().appendWithSlot(
            matchId = matchId.raw,
            entityProvider = { slot ->
                codec.encode(
                    QuestStarted(
                        eventId = EventId(id),
                        occurredOrder = 0,
                        recordedAt = 0L,
                        questNo = questNo,
                        leaderSeat = leaderSeat
                    ),
                    matchId,
                    slot.occurredOrder,
                    slot.logicalPosition,
                    slot.revisionSeq
                )
            },
            now = clock.millis()
        )

    private suspend fun revise(
        matchId: MatchId,
        targetId: String,
        newId: String,
        questNo: Int,
        leaderSeat: Int
    ): EventSlot = database.matchEventDao().reviseWithSlot(
        matchId = matchId.raw,
        oldEventId = targetId,
        entityProvider = { slot ->
            codec.encode(
                QuestStarted(
                    eventId = EventId(newId),
                    occurredOrder = 0,
                    recordedAt = 0L,
                    questNo = questNo,
                    leaderSeat = leaderSeat
                ),
                matchId,
                slot.occurredOrder,
                slot.logicalPosition,
                slot.revisionSeq
            ).copy(supersedesId = targetId)
        },
        now = clock.millis()
    )

    /** 建一局 5 个任务，位置 1..5。 */
    private suspend fun prepareFiveQuests(matchId: MatchId) {
        (1..5).forEach { position ->
            append(matchId, "e-$position", questNo = position, leaderSeat = 1)
        }
    }

    // ————————————————————————————————————————————————————————
    // U1：upTo = 逻辑位置上界
    // ————————————————————————————————————————————————————————

    @Test
    fun u1_upToIsLogicalPositionUpperBound() = runBlocking {
        val matchId = createMatch()
        prepareFiveQuests(matchId)
        // 修订位置 5：队长 1 → 7（结果可观察）
        revise(matchId, targetId = "e-5", newId = "e-5-v2", questNo = 5, leaderSeat = 7)

        val bundle = repository.getBundle(matchId)!!
        val match = bundle.toReconstructedMatch()

        val atFive = ReplayEngine(reducer).replay(match, upTo = 5)
        assertEquals("upTo=5 应应用到位置 5", 5, atFive.state.lastAppliedOrder)
        assertEquals("upTo=5 应包含 5 个任务", 5, atFive.state.quests.size)
        assertEquals(
            "upTo=5 必须看到修订后的队长（证明修订版本在位置 5 上生效）",
            7,
            atFive.state.quests.first { it.questNo == 5 }.leaderSeat
        )

        val atFour = ReplayEngine(reducer).replay(match, upTo = 4)
        assertEquals("upTo=4 应只应用到位置 4", 4, atFour.state.lastAppliedOrder)
        assertEquals("upTo=4 不应包含第 5 个任务", 4, atFour.state.quests.size)
        assertTrue(
            "upTo=4 时第 5 个任务必须不存在",
            atFour.state.quests.none { it.questNo == 5 }
        )
    }

    // ————————————————————————————————————————————————————————
    // U2：时间线顺序 —— 修订回到原位置
    // ————————————————————————————————————————————————————————

    @Test
    fun u2_revisionSitsAtOriginalIndexInTimeline() = runBlocking {
        val matchId = createMatch()
        prepareFiveQuests(matchId)
        revise(matchId, targetId = "e-5", newId = "e-5-v2", questNo = 5, leaderSeat = 7)

        val bundle = repository.getBundle(matchId)!!
        val timeline = TimelineBuilder.build(bundle.events, upTo = null)

        assertEquals("时间线应有 5 条", 5, timeline.size)
        assertEquals(
            "修订条目必须排在**索引 4**（第 5 位），不是末尾",
            4,
            timeline.indexOfFirst { it.sourceEventIds.any { id -> id.raw == "e-5-v2" } }
        )
        assertEquals(
            "时间线顺序编号应为 1,2,3,4,5",
            listOf(1, 2, 3, 4, 5),
            timeline.map { it.occurredOrder }
        )
    }

    // ————————————————————————————————————————————————————————
    // U3：选点不漂移
    // ————————————————————————————————————————————————————————

    @Test
    fun u3_positionFiveKeepsPointingAtPositionFiveAcrossRevision() = runBlocking {
        val matchId = createMatch()
        prepareFiveQuests(matchId)

        val before = repository.getBundle(matchId)!!
        val beforeAtFive = activeIdAtPosition(before.events.map { it.eventId.raw to it.occurredOrder }, 5)
        assertEquals("修订前位置 5 的有效版本应是 e-5", "e-5", beforeAtFive)
        assertTrue("修订前 GamePosition(5) 包含位置 5", GamePosition(5).includes(5))

        revise(matchId, targetId = "e-5", newId = "e-5-v2", questNo = 5, leaderSeat = 7)

        val after = repository.getBundle(matchId)!!
        val afterAtFive = activeIdAtPosition(after.events.map { it.eventId.raw to it.occurredOrder }, 5)
        assertEquals(
            "修订后位置 5 的有效版本应变成新版本，但**位置值仍是 5**",
            "e-5-v2",
            afterAtFive
        )
        assertNotEquals("有效版本确实换人了", beforeAtFive, afterAtFive)
        assertTrue("修订后 GamePosition(5) 仍包含位置 5", GamePosition(5).includes(5))
        assertTrue("修订后 GamePosition(4) 仍不包含位置 5", !GamePosition(4).includes(5))
    }

    private fun activeIdAtPosition(events: List<Pair<String, Int>>, position: Int): String? =
        events.firstOrNull { it.second == position }?.first

    // ————————————————————————————————————————————————————————
    // U4：中间位置修订
    // ————————————————————————————————————————————————————————

    @Test
    fun u4_revisingMiddlePositionKeepsFullOrder() = runBlocking {
        val matchId = createMatch()
        prepareFiveQuests(matchId)
        revise(matchId, targetId = "e-3", newId = "e-3-v2", questNo = 3, leaderSeat = 6)

        val bundle = repository.getBundle(matchId)!!
        assertEquals(
            "中间位置修订后顺序应为 e-1,e-2,e-3-v2,e-4,e-5",
            listOf("e-1", "e-2", "e-3-v2", "e-4", "e-5"),
            bundle.events.map { it.eventId.raw }
        )
        assertEquals(
            "领域序号仍必须是连续的 1..5（修订不增加长度）",
            listOf(1, 2, 3, 4, 5),
            bundle.events.map { it.occurredOrder }
        )
        assertEquals(
            "时间线里 e-3-v2 必须落在索引 2",
            2,
            TimelineBuilder.build(bundle.events, upTo = null)
                .indexOfFirst { it.sourceEventIds.any { id -> id.raw == "e-3-v2" } }
        )
    }
}
