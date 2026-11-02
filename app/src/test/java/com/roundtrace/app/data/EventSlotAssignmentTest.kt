package com.roundtrace.app.data

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
 * T4：写入槽位分配（RT-003 Step 1）。
 *
 * 这一组断言守的是「`logicalPosition` 与 `occurredOrder` 的分工」——
 * 前者是时间线位置（同一事件的多个版本共享），后者是历史写入序号（每条新行都递增）。
 *
 * | ID | 场景 | 断言 |
 * |---|---|---|
 * | T4-a | 空局连追加 20 条 | 两个序号都是 1..20；`revisionSeq` 全 0 |
 * | T4-b | 修订位置 5 | 新行 `occurredOrder=21`、`logicalPosition=5`、`revisionSeq=1`；旧行 retracted |
 * | T4-c | 再修订新版本 | `revisionSeq=2`，位置仍 5 |
 * | T4-d | 修订后追加 | 新事件 `logicalPosition=21`，`revisionSeq=0` |
 * | T4-e | 撤销后追加 | 不复用被撤销的位置 |
 * | T4-f | 跨对局 | A 局修订不影响 B 局 |
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventSlotAssignmentTest {

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
            title = "槽位测试对局",
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

    private suspend fun append(matchId: MatchId, id: String): EventSlot =
        database.matchEventDao().appendWithSlot(
            matchId = matchId.raw,
            entityProvider = { slot ->
                codec.encode(
                    QuestStarted(
                        eventId = EventId(id),
                        occurredOrder = 0,
                        recordedAt = 0L,
                        questNo = 1,
                        leaderSeat = 1
                    ),
                    matchId,
                    slot.occurredOrder,
                    slot.logicalPosition,
                    slot.revisionSeq
                )
            },
            now = clock.millis()
        )

    private suspend fun revise(matchId: MatchId, targetId: String, newId: String): EventSlot =
        database.matchEventDao().reviseWithSlot(
            matchId = matchId.raw,
            oldEventId = targetId,
            entityProvider = { slot ->
                codec.encode(
                    QuestStarted(
                        eventId = EventId(newId),
                        occurredOrder = 0,
                        recordedAt = 0L,
                        questNo = 1,
                        leaderSeat = 1
                    ),
                    matchId,
                    slot.occurredOrder,
                    slot.logicalPosition,
                    slot.revisionSeq
                ).copy(supersedesId = targetId)
            },
            now = clock.millis()
        )

    private suspend fun rows(matchId: MatchId) =
        database.matchEventDao().listAll(matchId.raw).associateBy { it.id }

    @Test
    fun t4a_appendingTwentyKeepsBothSequencesAligned() = runBlocking {
        val matchId = createMatch()

        val slots = (1..20).map { index -> append(matchId, "e-$index") }

        assertEquals(
            "occurredOrder 必须是 1..20",
            (1..20).toList(),
            slots.map { it.occurredOrder }
        )
        assertEquals(
            "logicalPosition 也必须同步为 1..20（从未修订过）",
            (1..20).toList(),
            slots.map { it.logicalPosition }
        )
        assertTrue("原始版本 revisionSeq 必须全为 0", slots.all { it.revisionSeq == 0 })
        assertTrue(
            "落库后也不得出现 logicalPosition = 0 的脏行（S1）",
            database.matchEventDao().listAll(matchId.raw).none { it.logicalPosition == 0 }
        )
    }

    @Test
    fun t4b_revisingPositionFiveWritesNewSeqAndRetractsOld() = runBlocking {
        val matchId = createMatch()
        (1..5).forEach { index -> append(matchId, "e-$index") }

        val slot = revise(matchId, targetId = "e-5", newId = "e-5-v2")

        assertEquals("新版本写入序号应为 21（第 6 条）", 6, slot.occurredOrder)
        assertEquals("新版本必须复用位置 5", 5, slot.logicalPosition)
        assertEquals("新版本 revisionSeq 应为 1", 1, slot.revisionSeq)

        val stored = rows(matchId)
        assertEquals("旧版本必须被撤销", true, stored.getValue("e-5").retracted)
        assertEquals("旧版本仍是位置 5 / 版本 0", 5, stored.getValue("e-5").logicalPosition)
        assertEquals(0, stored.getValue("e-5").revisionSeq)
        assertEquals("新版本位置必须是 5", 5, stored.getValue("e-5-v2").logicalPosition)
    }

    @Test
    fun t4c_revisingAgainIncrementsSeqAndKeepsPosition() = runBlocking {
        val matchId = createMatch()
        (1..5).forEach { index -> append(matchId, "e-$index") }
        revise(matchId, targetId = "e-5", newId = "e-5-v2")

        val slot = revise(matchId, targetId = "e-5-v2", newId = "e-5-v3")

        assertEquals("第三次写入序号应为 7", 7, slot.occurredOrder)
        assertEquals("位置必须仍是 5", 5, slot.logicalPosition)
        assertEquals("revisionSeq 应为 2", 2, slot.revisionSeq)
    }

    @Test
    fun t4d_appendAfterRevisionUsesMaxPositionPlusOne() = runBlocking {
        val matchId = createMatch()
        (1..5).forEach { index -> append(matchId, "e-$index") }
        revise(matchId, targetId = "e-5", newId = "e-5-v2")

        val slot = append(matchId, "e-6")

        assertEquals(
            "修订不产生新位置：下一条新事件的 logicalPosition 应为 6（MAX+1）",
            6,
            slot.logicalPosition
        )
        assertEquals("新事件 revisionSeq 必须为 0", 0, slot.revisionSeq)
        assertEquals("写入序号应为 7（修订也占写入序号）", 7, slot.occurredOrder)
        assertNotEquals("两个序号在修订发生后不再相等，这正是本次改造的意义", slot.occurredOrder, slot.logicalPosition)
    }

    @Test
    fun t4e_retractedPositionIsNeverReused() = runBlocking {
        val matchId = createMatch()
        (1..3).forEach { index -> append(matchId, "e-$index") }

        repository.retractEvent(matchId, EventId("e-3"))
        val slot = append(matchId, "e-4")

        assertEquals(
            "撤销后新事件不得复用被撤销的位置（nextLogicalPosition 不过滤 retracted）",
            4,
            slot.logicalPosition
        )
    }

    @Test
    fun t4f_slotsAreIsolatedPerMatch() = runBlocking {
        val matchA = createMatch()
        val matchB = createMatch()
        (1..5).forEach { index -> append(matchA, "a-$index") }
        (1..2).forEach { index -> append(matchB, "b-$index") }

        revise(matchA, targetId = "a-5", newId = "a-5-v2")

        val bRows = rows(matchB)
        assertEquals("B 局事件数不变", 2, bRows.size)
        assertEquals("B 局位置不变", listOf(1, 2), bRows.values.sortedBy { it.occurredOrder }.map { it.logicalPosition })
        assertTrue("B 局不得出现 revisionSeq > 0", bRows.values.all { it.revisionSeq == 0 })
    }

    // ————————————————————————————————————————————————————————
    // Step 1 冻结用例：读语义必须与改造前完全一致
    // ————————————————————————————————————————————————————————

    /**
     * **Step 3 翻转后的语义测试**：领域 `occurredOrder` 现在承载**逻辑位置**。
     *
     * 这条用例在 Step 1 是「冻结测试」（名字 `step1_decodedOccurredOrderStillEqualsColumnNotLogicalPosition`，
     * 断言领域值 == 列写入序号）。Step 3 把 `EventCodec.decode` 切到 `logicalPosition` 后，
     * 按 migration-plan §17.5 逐条翻转期望值——**改名与改期望都是这一步的组成部分**，
     * 不是"测试挡路了就改测试"：
     *
     * | 断言 | Step 1（旧） | Step 3（新） |
     * |---|---|---|
     * | A | 领域 == 写入序号（6） | 领域 == 逻辑位置（**5**） |
     * | B | 领域 != 逻辑位置 | 领域 != 写入序号（**≠6**） |
     * | C | `GamePosition(5)` **不含** | `GamePosition(5)` **含**、`GamePosition(4)` 不含 |
     * | E | 序号 `[1,2,3,4,6]` | 序号 `[1,2,3,4,5]` |
     */
    @Test
    fun step3_decodedOccurredOrderFollowsLogicalPosition() = runBlocking {
        val matchId = createMatch()
        (1..5).forEach { index -> append(matchId, "e-$index") }
        val slot = revise(matchId, targetId = "e-5", newId = "e-5-v2")

        assertEquals("前提 1：写入序号仍是 6（列 occurredOrder）", 6, slot.occurredOrder)
        assertEquals("前提 2：逻辑位置仍是 5", 5, slot.logicalPosition)

        val bundle = repository.getBundle(matchId)!!
        val revision = bundle.events.first { it.eventId.raw == "e-5-v2" }

        // 断言 A：领域 occurredOrder 现在等于 logicalPosition
        assertEquals(
            "Step 3：领域 occurredOrder 必须等于逻辑位置（5）",
            slot.logicalPosition,
            revision.occurredOrder
        )
        // 断言 B：不再等于写入序号
        assertNotEquals(
            "Step 3：领域 occurredOrder 不得再等于写入序号（6）",
            slot.occurredOrder,
            revision.occurredOrder
        )

        // 断言 C：upTo 现在是「逻辑位置上界」
        assertTrue(
            "Step 3：GamePosition(5) 必须包含该修订（它就在第 5 个逻辑位置）",
            GamePosition(5).includes(revision.occurredOrder)
        )
        assertTrue(
            "Step 3：GamePosition(4) 必须不包含它",
            !GamePosition(4).includes(revision.occurredOrder)
        )

        // 断言 E/F：顺序按逻辑位置，修订回到原位置（而不是排末尾）
        assertEquals(
            "Step 3：事件 id 顺序应为 e-1..e-4 再 e-5-v2（修订占据位置 5）",
            listOf("e-1", "e-2", "e-3", "e-4", "e-5-v2"),
            bundle.events.map { it.eventId.raw }
        )
        assertEquals(
            "Step 3：领域序号应为 1,2,3,4,5 —— 修订不再让历史变长",
            listOf(1, 2, 3, 4, 5),
            bundle.events.map { it.occurredOrder }
        )
    }
}
