package com.roundtrace.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.core.data.RepositoryError
import com.roundtrace.app.core.data.RoomMatchRepository
import com.roundtrace.app.core.database.EventCodec
import com.roundtrace.app.core.database.RoundTraceDatabase
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventType
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.QuestStarted
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.replay.ReplayEngine
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.util.SequentialIdGenerator
import com.roundtrace.app.core.util.fixedClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 仓储层 **JVM（Robolectric）** 仪器级测试（TASKS.md T43②③）。
 *
 * 与 `src/androidTest/.../RoomMatchRepositoryTest.kt` 并行保障。
 *
 * 覆盖主理人本轮指定的四条硬断言：
 * 1. D01：`occurredOrder` 为 1..N 连续；
 * 2. CASCADE：删除 match 后五张子表零残留；
 * 3. D03：修订后旧事件 `retracted=1`，读路径只返回新事件，`replay` 不含旧结论；
 * 4. 类型转换回归：`createdAtEpochMs` 经 Room 往返后**不等于 0**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomMatchRepositoryRobolectricTest {

    private lateinit var database: RoundTraceDatabase
    private lateinit var repository: RoomMatchRepository

    /** 固定的基准时刻：用来钉死 `createdAtEpochMs` 的 Long 往返。 */
    private val baseEpochMs = 1_700_000_000_000L
    private val clock = fixedClock(baseEpochMs)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RoundTraceDatabase::class.java
        )
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
            title = "仓储 JVM 测试对局",
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

    private fun questStarted(questNo: Int, id: String): QuestStarted = QuestStarted(
        eventId = EventId(id),
        occurredOrder = 0,
        recordedAt = 0L,
        questNo = questNo,
        leaderSeat = 1
    )

    // ————————————————————————————————————————————————————————
    // 主理人 §三.4：Long 类型转换回归
    // ————————————————————————————————————————————————————————

    @Test
    fun createMatchPersistsLongTimestamps() = runBlocking {
        val matchId = createMatch()
        val bundle = repository.getBundle(matchId)

        assertNotNull("对局必须存在", bundle)
        val createdAt = bundle!!.config.createdAtEpochMs
        assertNotEquals("createdAtEpochMs 绝不能是 0（intOrNull 截断的经典症状）", 0L, createdAt)
        assertEquals("createdAtEpochMs 必须与注入的 Clock 一致", baseEpochMs, createdAt)
        assertEquals(
            "matches.createdAt 列也必须是同一个值",
            baseEpochMs,
            database.matchDao().findById(matchId.raw)?.createdAt
        )
    }

    @Test
    fun configRoleSlotsRoundTripThroughConfigJson() = runBlocking {
        val matchId = createMatch(playerCount = 10)
        val config = repository.getBundle(matchId)!!.config

        assertEquals("人数必须往返一致", 10, config.playerCount)
        assertEquals(
            "角色构成必须与 ruleset 的标准预设完全一致（含奥伯伦）",
            AvalonPreset.standardPreset(10),
            config.roleSlots
        )
        assertEquals("presetId 必须往返一致", AvalonPreset.presetId(10), config.presetId)
        assertEquals("rulesetVersion 必须往返一致", AvalonPreset.RULESET_VERSION, config.rulesetVersion)
        assertEquals("title 必须往返一致", "仓储 JVM 测试对局", config.title)
    }

    // ————————————————————————————————————————————————————————
    // 主理人 §三.2：CASCADE
    // ————————————————————————————————————————————————————————

    @Test
    fun cascadeDeleteLeavesAllFiveTablesEmpty() = runBlocking {
        val matchId = createMatch()
        repository.appendEvent(matchId, questStarted(1, "cascade-e1"))
        repository.saveDraft(matchId, "RECORD_PROPOSAL", "{}")
        database.analysisReportDao().upsert(
            com.roundtrace.app.core.database.entity.AnalysisReportEntity(
                id = "report-1",
                matchId = matchId.raw,
                eventRevision = 1,
                perspectiveKey = "PUBLIC",
                contentJson = "{}",
                createdAt = 0L
            )
        )

        // 删前确认五张子表都有数据
        assertEquals(8, database.participantDao().listByMatch(matchId.raw).size)
        assertEquals(8, database.roleAssignmentDao().listByMatch(matchId.raw).size)
        assertEquals(1, database.matchEventDao().listAll(matchId.raw).size)
        assertNotNull(database.matchDraftDao().load(matchId.raw, "RECORD_PROPOSAL"))
        assertNotNull(database.analysisReportDao().find(matchId.raw, 1, "PUBLIC"))

        repository.deleteMatch(matchId)

        assertNull("对局应被删除", repository.getBundle(matchId))
        assertEquals("participants 零残留", 0, database.participantDao().listByMatch(matchId.raw).size)
        assertEquals("role_assignments 零残留", 0, database.roleAssignmentDao().listByMatch(matchId.raw).size)
        assertEquals("match_events 零残留", 0, database.matchEventDao().listAll(matchId.raw).size)
        assertNull(
            "match_drafts 零残留",
            database.matchDraftDao().load(matchId.raw, "RECORD_PROPOSAL")
        )
        assertNull(
            "analysis_reports 零残留",
            database.analysisReportDao().find(matchId.raw, 1, "PUBLIC")
        )
    }

    // ————————————————————————————————————————————————————————
    // 主理人 §三.3：D03 修订语义
    // ————————————————————————————————————————————————————————

    @Test
    fun reviseEventRetractsOldAndReplayUsesNewFacts() = runBlocking {
        val matchId = createMatch()
        val first = repository.appendEvent(matchId, questStarted(1, "revise-old"))
        val targetId = first.eventId!!
        assertEquals("首条事件 order 应为 1", 1, first.occurredOrder)

        // 修订：队长从 1 号改成 3 号
        val replacement = questStarted(1, "revise-new").copy(leaderSeat = 3)
        val revised = repository.reviseEvent(matchId, targetId, replacement)

        assertNull("修订应成功", revised.error)
        assertEquals("新事件 order 应为 2", 2, revised.occurredOrder)

        // ① 读路径只返回新事件
        val active = database.matchEventDao().listActive(matchId.raw)
        assertEquals("读路径应只剩 1 条", 1, active.size)
        assertEquals("留下的必须是新事件", "revise-new", active.first().id)
        assertTrue(
            "新事件 payload 的 leaderSeat 应为 3：${active.first().payloadJson}",
            active.first().payloadJson.contains("\"leaderSeat\":3")
        )

        // ② 审计链完整、旧事件 retracted=1
        val all = database.matchEventDao().listAll(matchId.raw)
        assertEquals("审计链应有 2 条", 2, all.size)
        assertEquals("旧事件必须 retracted=1", 1, all.count { it.retracted })
        assertEquals(
            "旧事件 id 必须保留在审计链",
            true,
            all.any { it.id == targetId.raw && it.retracted }
        )
        assertEquals(
            "新事件的 supersedesId 必须指向旧事件",
            targetId.raw,
            all.first { !it.retracted }.supersedesId
        )

        // ③ replay 不含旧结论
        val bundle = repository.getBundle(matchId)!!
        assertEquals("bundle 只含新事件", 1, bundle.events.size)
        val state = ReplayEngine(DefaultGameRulesRegistry().avalonReducer)
            .replay(bundle.toReconstructedMatch())
            .state
        assertEquals(
            "重放后的队长必须是修订后的 3 号，不是旧的 1 号",
            3,
            state.quests.first { it.questNo == 1 }.leaderSeat
        )
    }

    @Test
    fun reviseDifferentTypeIsRejectedAndWritesNothing() = runBlocking {
        val matchId = createMatch()
        val first = repository.appendEvent(matchId, questStarted(1, "type-guard"))

        val badReplacement = MissionResolved(
            eventId = EventId("bad-mission"),
            occurredOrder = 0,
            recordedAt = 0L,
            questNo = 1,
            teamSeats = listOf(1, 2, 3),
            teamSizeExpected = 3,
            failCount = 0,
            observedOutcome = MissionOutcome.SUCCESS
        )
        val result = repository.reviseEvent(matchId, first.eventId!!, badReplacement)

        assertEquals(
            "跨事件类型的修订必须被拒绝",
            RepositoryError.INVALID_SUPERSEDE_TARGET,
            result.error
        )
        assertEquals(
            "被拒绝时不得写入任何事件",
            1,
            database.matchEventDao().listAll(matchId.raw).size
        )
        assertEquals(
            "也不得把旧事件标记为 retracted",
            0,
            database.matchEventDao().listAll(matchId.raw).count { it.retracted }
        )
    }

    @Test
    fun retractEventHidesItFromReplay() = runBlocking {
        val matchId = createMatch()
        val first = repository.appendEvent(matchId, questStarted(1, "retract-me"))
        assertEquals(1, repository.getBundle(matchId)!!.events.size)

        repository.retractEvent(matchId, first.eventId!!)

        assertEquals("撤销后读路径应为空", 0, repository.getBundle(matchId)!!.events.size)
        assertEquals(
            "但审计链仍在",
            1,
            database.matchEventDao().listAll(matchId.raw).size
        )
    }

    // ————————————————————————————————————————————————————————
    // 主理人 §三.1：经由仓储的 1..N（含自动补建）
    // ————————————————————————————————————————————————————————

    @Test
    fun appendEventAutoCreatesQuestStartedThenKeepsOrderContiguous() = runBlocking {
        val matchId = createMatch()

        // 首次提交提案：仓储必须同事务补建 QUEST_STARTED（T35③）
        val proposal = TeamProposed(
            eventId = EventId("auto-p1"),
            occurredOrder = 0,
            recordedAt = 0L,
            questNo = 1,
            proposalNo = 1,
            leaderSeat = 1,
            teamSeats = listOf(1, 2, 3)
        )
        val result = repository.appendEvent(matchId, proposal)
        assertEquals("提案本身的 order 应为 2（前有自动补建）", 2, result.occurredOrder)

        val events = repository.getBundle(matchId)!!.events
        assertEquals("应有 2 条事件", 2, events.size)
        assertEquals(
            "第一条必须是自动补建的 QUEST_STARTED",
            EventType.QUEST_STARTED,
            events.first().type
        )
        assertEquals("order 必须连续 1、2", listOf(1, 2), events.map { it.occurredOrder })
    }

    @Test
    fun twentyAppendsThroughRepositoryAreContiguous() = runBlocking {
        val matchId = createMatch()

        val orders = (1..20).map { index ->
            repository.appendEvent(matchId, questStarted(1, "repo-e-$index")).occurredOrder
        }

        assertEquals("通过仓储追加 20 条，order 必须是 1..20", (1..20).toList(), orders)
        val stored = repository.getBundle(matchId)!!.events.map { it.occurredOrder }
        assertEquals("读回后仍必须是 1..20", (1..20).toList(), stored)
        assertTrue(
            "严格递增无空洞",
            stored.zipWithNext().all { (a, b) -> b == a + 1 }
        )
    }

    // ————————————————————————————————————————————————————————
    // 角色初始化
    // ————————————————————————————————————————————————————————

    @Test
    fun createMatchSeedsUnknownRolesPerSeat() = runBlocking {
        val matchId = createMatch()
        val bundle = repository.getBundle(matchId)!!

        assertEquals("应写入 8 个座位参与人", 8, bundle.participants.size)
        assertEquals("每个座位一条角色记录", 8, bundle.roleAssignments.size)
        assertTrue(
            "初始角色必须是 UNKNOWN（不得凭人数反推）",
            bundle.roleAssignments.values.all { it.roleId == RoleId.UNKNOWN }
        )
    }
}
