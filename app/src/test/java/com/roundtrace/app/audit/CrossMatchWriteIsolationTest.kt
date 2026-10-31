package com.roundtrace.app.audit

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.core.data.RepositoryError
import com.roundtrace.app.core.data.RoomMatchRepository
import com.roundtrace.app.core.database.EventCodec
import com.roundtrace.app.core.database.RoundTraceDatabase
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.util.SequentialIdGenerator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QA-P1 · RT-002 对抗性回归：**事件写入必须按 matchId 归属**。
 *
 * 这些用例不是「照着修复代码写」，而是从攻击面出发：
 * 攻击者（或一个写错的 UI）只需要拿到一个 `(matchId, eventId)` 组合，
 * 就能尝试用 A 局的身份去改 B 局的事实。测试要证明的四件事：
 *
 * 1. `retractEvent(A, bEventId)` 返回错误，且 **A/B 两局的事件表逐行不变**；
 * 2. `reviseEvent(A, bEventId, ...)` 返回错误，且不会在 A 局留下替代事件；
 * 3. DAO 层 `reviseSupersede` 在「撤销未命中」时**回滚**——不产生
 *    「替代事件已写入、旧事件仍有效」的半状态；
 * 4. DAO 层 `markRetracted` 本身按 matchId 限定（第二道闸门真实存在）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrossMatchWriteIsolationTest {

    private lateinit var database: RoundTraceDatabase
    private lateinit var repository: RoomMatchRepository

    private val clock = MutableClock(1_700_000_000_000L)
    private val registry = DefaultGameRulesRegistry()
    private val codec = EventCodec()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RoundTraceDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()

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

    // ————————————————————————————————————————————————————————
    // E2E-02：跨对局撤销
    // ————————————————————————————————————————————————————————

    @Test
    fun e2e02_retract_cannot_cross_match_boundary() = runBlocking {
        val matchA = createMatch("A")
        val matchB = createMatch("B")

        val appended = repository.appendEvent(
            matchB,
            AuditEvents.questStarted("b-e1", questNo = 1, leaderSeat = 1)
        )
        assertEquals("B 局首条事件 order 应为 1", 1, appended.occurredOrder)
        val bEventId = appended.eventId!!

        val result = repository.retractEvent(matchA, bEventId)

        assertNotNull("用 A 局的 matchId 撤销 B 局事件必须失败（RT-002）", result.error)
        assertEquals(
            "A 局事件表必须逐行不变",
            0,
            database.matchEventDao().listAll(matchA.raw).size
        )
        assertEquals(
            "B 局事件的 retracted 必须仍为 0",
            0,
            database.matchEventDao().listAll(matchB.raw).count { it.retracted }
        )
        assertEquals(
            "B 局读路径仍必须能看到该事件",
            1,
            repository.getBundle(matchB)!!.events.size
        )
    }

    // ————————————————————————————————————————————————————————
    // E2E-03：跨对局修订
    // ————————————————————————————————————————————————————————

    @Test
    fun e2e03_revise_cannot_cross_match_boundary() = runBlocking {
        val matchA = createMatch("A")
        val matchB = createMatch("B")

        val appended = repository.appendEvent(
            matchB,
            AuditEvents.questStarted("b-e1", questNo = 1, leaderSeat = 1)
        )
        val bEventId = appended.eventId!!

        val result = repository.reviseEvent(
            matchId = matchA,
            targetEventId = bEventId,
            replacement = AuditEvents.questStarted("a-new", questNo = 1, leaderSeat = 3)
        )

        assertEquals(
            "跨对局修订必须返回 INVALID_SUPERSEDE_TARGET",
            RepositoryError.INVALID_SUPERSEDE_TARGET,
            result.error
        )
        assertEquals(
            "A 局不得被写入任何事件（含替代事件）",
            0,
            database.matchEventDao().listAll(matchA.raw).size
        )
        assertEquals(
            "B 局被指向的事件不得被撤销",
            0,
            database.matchEventDao().listAll(matchB.raw).count { it.retracted }
        )
    }

    // ————————————————————————————————————————————————————————
    // DAO 层：rollback 语义（「半状态」是否存在）
    // ————————————————————————————————————————————————————————

    @Test
    fun e2e03b_dao_reviseSupersede_rolls_back_when_retraction_misses() = runBlocking {
        val matchA = createMatch("A")
        val matchB = createMatch("B")

        val appended = repository.appendEvent(
            matchB,
            AuditEvents.questStarted("b-e1", questNo = 1, leaderSeat = 1)
        )
        val bEventId = appended.eventId!!

        val beforeA = database.matchEventDao().listAll(matchA.raw).size
        val beforeB = database.matchEventDao().listAll(matchB.raw).size

        var threw = false
        try {
            // 直接用 B 的事件 id 去走 A 局的修订事务：撤销必命中 0 行
            database.matchEventDao().reviseSupersede(
                matchId = matchA.raw,
                newEntityProvider = { order ->
                    codec.encode(
                        AuditEvents.questStarted("a-shadow", questNo = 1, leaderSeat = 2),
                        matchA,
                        order
                    )
                },
                oldEventId = bEventId.raw,
                now = clock.nowMs
            )
        } catch (e: IllegalStateException) {
            threw = true
        }

        assertTrue("撤销未命中时必须抛异常（否则半状态成立）", threw)
        assertEquals(
            "异常必须回滚：A 局不得留下替代事件",
            beforeA,
            database.matchEventDao().listAll(matchA.raw).size
        )
        assertEquals(
            "异常必须回滚：B 局事件表逐行不变",
            beforeB,
            database.matchEventDao().listAll(matchB.raw).size
        )
        assertEquals(
            "B 局事件不得被撤销",
            0,
            database.matchEventDao().listAll(matchB.raw).count { it.retracted }
        )
    }

    @Test
    fun e2e02b_dao_markRetracted_is_match_scoped() = runBlocking {
        val matchA = createMatch("A")
        val matchB = createMatch("B")

        val appended = repository.appendEvent(
            matchB,
            AuditEvents.questStarted("b-e1", questNo = 1, leaderSeat = 1)
        )
        val bEventId = appended.eventId!!

        assertEquals(
            "拿 A 局的 matchId 撤销 B 局事件必须命中 0 行",
            0,
            database.matchEventDao().markRetracted(matchA.raw, bEventId.raw)
        )
        assertEquals(
            "同一事件用正确的 matchId 撤销才命中 1 行",
            1,
            database.matchEventDao().markRetracted(matchB.raw, bEventId.raw)
        )
    }

    // ————————————————————————————————————————————————————————
    // 反向保险：正确的同局写入不受影响
    // ————————————————————————————————————————————————————————

    @Test
    fun same_match_revise_and_retract_still_work() = runBlocking {
        val matchA = createMatch("A")
        val first = repository.appendEvent(
            matchA,
            AuditEvents.questStarted("a-e1", questNo = 1, leaderSeat = 1)
        )
        val firstId = first.eventId!!

        val revised = repository.reviseEvent(
            matchId = matchA,
            targetEventId = firstId,
            replacement = AuditEvents.questStarted("a-e2", questNo = 1, leaderSeat = 5)
        )
        assertEquals("同局修订必须成功", null, revised.error)
        assertEquals("读路径只剩替代事件", 1, repository.getBundle(matchA)!!.events.size)

        val activeId = database.matchEventDao().listActive(matchA.raw).first().id
        val retracted = repository.retractEvent(matchA, EventId(activeId))
        assertEquals("同局撤销必须成功", null, retracted.error)
        assertEquals("撤销后读路径为空", 0, repository.getBundle(matchA)!!.events.size)
        assertNotEquals(
            "审计链仍在（事件行不删除）",
            0,
            database.matchEventDao().listAll(matchA.raw).size
        )
    }

    // ————————————————————————————————————————————————————————

    private suspend fun createMatch(label: String, playerCount: Int = 8): MatchId =
        repository.createMatch(
            MatchCreationDraft(
                title = "审计对局 $label",
                playerCount = playerCount,
                roleSlots = AvalonPreset.standardPreset(playerCount),
                mySeat = 1,
                initialLeaderSeat = 1,
                participants = MatchCreationDraft.defaultParticipants(playerCount),
                presetId = AvalonPreset.presetId(playerCount),
                rulesetId = AvalonPreset.RULESET_ID,
                rulesetVersion = AvalonPreset.RULESET_VERSION
            )
        )
}
