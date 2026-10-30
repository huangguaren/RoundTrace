package com.roundtrace.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 仓储层仪器测试（TASKS.md T43②③）。
 *
 * 覆盖：
 * - CASCADE：删除 match 后 5 张子表零残留；
 * - D03：修订后旧事件 `retracted = 1`，读路径不再返回；
 * - `appendEvent` 的 `occurredOrder` 为 1..N；
 * - 事件修订策略在仓储层被真正执行。
 */
@RunWith(AndroidJUnit4::class)
class RoomMatchRepositoryTest {

    private lateinit var database: RoundTraceDatabase
    private lateinit var repository: RoomMatchRepository

    private val clock = fixedClock(1_700_000_000_000L)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, RoundTraceDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val registry = DefaultGameRulesRegistry()
        repository = RoomMatchRepository(
            database = database,
            gameRulesRegistry = registry,
            reducer = registry.avalonReducer,
            codec = EventCodec(),
            idGenerator = SequentialIdGenerator("id"),
            clock = clock
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun createMatch(playerCount: Int = 8): MatchId = repository.createMatch(
        MatchCreationDraft(
            title = "仓储测试对局",
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

    private fun questStarted(questNo: Int): QuestStarted = QuestStarted(
        eventId = EventId("q-$questNo"),
        occurredOrder = 0,
        recordedAt = 0L,
        questNo = questNo,
        leaderSeat = 1
    )

    @Test
    fun createMatchWritesAllThreeTables() = runTest {
        val matchId = createMatch()
        val bundle = repository.getBundle(matchId)

        assertNotNull("对局必须存在", bundle)
        assertEquals("应写入 8 个座位参与人", 8, bundle?.participants?.size)
        assertEquals("每个座位一条角色记录", 8, bundle?.roleAssignments?.size)
        assertTrue(
            "初始角色应为 UNKNOWN（不得凭人数反推）",
            bundle?.roleAssignments?.values?.all {
                it.roleId == com.roundtrace.app.core.model.RoleId.UNKNOWN
            } == true
        )
    }

    @Test
    fun appendEventStampsOrderFromOne() = runTest {
        val matchId = createMatch()

        val first = repository.appendEvent(matchId, questStarted(1))
        val second = repository.appendEvent(matchId, questStarted(2))

        assertEquals("第一条事件 order 应为 1", 1, first.occurredOrder)
        assertEquals("第二条事件 order 应为 2", 2, second.occurredOrder)

        val bundle = repository.getBundle(matchId)
        assertEquals(
            "读取顺序必须与写入一致",
            listOf(1, 2),
            bundle?.events?.map { it.occurredOrder }
        )
    }

    @Test
    fun cascadeDeleteLeavesNoResidue() = runTest {
        val matchId = createMatch()
        repository.appendEvent(matchId, questStarted(1))
        repository.saveDraft(matchId, "RECORD_PROPOSAL", "{}")

        repository.deleteMatch(matchId)

        assertNull("对局应被删除", repository.getBundle(matchId))
        assertEquals(
            "participants 应被级联删除",
            0,
            database.participantDao().listByMatch(matchId.raw).size
        )
        assertEquals(
            "role_assignments 应被级联删除",
            0,
            database.roleAssignmentDao().listByMatch(matchId.raw).size
        )
        assertEquals(
            "match_events 应被级联删除",
            0,
            database.matchEventDao().listAll(matchId.raw).size
        )
        assertEquals(
            "match_drafts 应被级联删除",
            null,
            database.matchDraftDao().load(matchId.raw, "RECORD_PROPOSAL")
        )
    }

    @Test
    fun reviseEventRetractsOldVersion() = runTest {
        val matchId = createMatch()
        val first = repository.appendEvent(matchId, questStarted(1))
        val targetId = first.eventId
        assertNotNull(targetId)

        val replacement = questStarted(1).copy(leaderSeat = 3)
        val revised = repository.reviseEvent(matchId, targetId!!, replacement)

        assertEquals("修订应成功", null, revised.error)
        assertEquals("新事件 order 应为 2", 2, revised.occurredOrder)

        val active = database.matchEventDao().listActive(matchId.raw)
        assertEquals("读路径只剩新事件", 1, active.size)
        assertEquals("新事件 leaderSeat 应为 3", true, active.first().payloadJson.contains("\"leaderSeat\":3"))

        val all = database.matchEventDao().listAll(matchId.raw)
        assertEquals("审计链保留两条", 2, all.size)
        assertEquals(
            "旧事件必须 retracted=1",
            1,
            all.count { it.retracted }
        )
        assertEquals(
            "新事件的 supersedesId 必须指向旧事件",
            targetId.raw,
            all.first { !it.retracted }.supersedesId
        )
    }

    @Test
    fun reviseDifferentTypeIsRejected() = runTest {
        val matchId = createMatch()
        val first = repository.appendEvent(matchId, questStarted(1))

        val badReplacement = com.roundtrace.app.core.model.MissionResolved(
            eventId = EventId("bad"),
            occurredOrder = 0,
            recordedAt = 0L,
            questNo = 1,
            teamSeats = listOf(1, 2, 3),
            teamSizeExpected = 3,
            failCount = 0,
            observedOutcome = com.roundtrace.app.core.model.MissionOutcome.SUCCESS
        )
        val result = repository.reviseEvent(matchId, first.eventId!!, badReplacement)

        assertEquals(
            "跨事件类型的修订必须被拒绝",
            com.roundtrace.app.core.data.RepositoryError.INVALID_SUPERSEDE_TARGET,
            result.error
        )
        assertEquals(
            "被拒绝时不得改动任何事件",
            1,
            database.matchEventDao().listAll(matchId.raw).size
        )
    }

    @Test
    fun retractEventHidesItFromReplay() = runTest {
        val matchId = createMatch()
        val first = repository.appendEvent(matchId, questStarted(1))
        repository.retractEvent(matchId, first.eventId!!)

        val bundle = repository.getBundle(matchId)
        assertEquals("撤销后读路径应为空", 0, bundle?.events?.size)
        assertEquals(
            "但审计链仍在",
            1,
            database.matchEventDao().listAll(matchId.raw).size
        )
    }

    @Test
    fun appendEventAutoCreatesQuestStarted() = runTest {
        val matchId = createMatch()

        // 直接提交提案，不显式开始任务 → 仓储必须自动补建 QUEST_STARTED（T35③）
        val proposal = com.roundtrace.app.core.model.TeamProposed(
            eventId = EventId("p1"),
            occurredOrder = 0,
            recordedAt = 0L,
            questNo = 1,
            proposalNo = 1,
            leaderSeat = 1,
            teamSeats = listOf(1, 2, 3)
        )
        repository.appendEvent(matchId, proposal)

        val bundle = repository.getBundle(matchId)
        val events = bundle?.events.orEmpty()
        assertEquals("应自动补建 + 提案共 2 条事件", 2, events.size)
        assertEquals(
            "第一条必须是 QUEST_STARTED",
            com.roundtrace.app.core.model.EventType.QUEST_STARTED,
            events.first().type
        )
        assertEquals(
            "顺序必须是 1、2",
            listOf(1, 2),
            events.map { it.occurredOrder }
        )
    }
}
