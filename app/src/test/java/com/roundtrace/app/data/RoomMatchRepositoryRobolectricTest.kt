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
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.AssassinationRecorded
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.RevealState
import com.roundtrace.app.core.model.RoleAssignment
import com.roundtrace.app.core.model.RoleSource
import com.roundtrace.app.core.model.TeamProposalResolved
import com.roundtrace.app.core.model.TeamVoteRecorded
import com.roundtrace.app.core.knowledge.AvalonKnowledgeProvider
import com.roundtrace.app.core.model.Perspective
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

    // ————————————————————————————————————————————————————————
    // RT-002：跨对局事件写入必须在 DAO 层就被拦住
    // ————————————————————————————————————————————————————————

    @Test
    fun markRetractedIsScopedByMatchId() = runBlocking {
        val matchA = createMatch()
        val matchB = createMatch()
        repository.appendEvent(matchA, questStarted(1, "scope-a"))
        val bEvent = repository.appendEvent(matchB, questStarted(1, "scope-b"))

        // 直接打 DAO：用 A 的 matchId 去撤销 B 的事件
        val affected = database.matchEventDao()
            .markRetracted(matchId = matchA.raw, id = bEvent.eventId!!.raw)

        assertEquals("跨对局撤销必须命中 0 行", 0, affected)
        assertEquals(
            "B 的事件必须仍然有效",
            0,
            database.matchEventDao().listAll(matchB.raw).count { it.retracted }
        )
    }

    @Test
    fun retractEventCannotTouchAnotherMatch() = runBlocking {
        val matchA = createMatch()
        val matchB = createMatch()
        val aEvent = repository.appendEvent(matchA, questStarted(1, "own-a"))
        val bEvent = repository.appendEvent(matchB, questStarted(1, "own-b"))

        val result = repository.retractEvent(matchA, bEvent.eventId!!)

        assertEquals(
            "用 A 的身份撤销 B 的事件必须失败",
            RepositoryError.NOT_FOUND,
            result.error
        )
        assertEquals(
            "B 的事件不得被撤销",
            0,
            database.matchEventDao().listAll(matchB.raw).count { it.retracted }
        )
        assertEquals(
            "A 自己的事件也不该被误伤",
            0,
            database.matchEventDao().listAll(matchA.raw).count { it.retracted }
        )
        assertNotNull(aEvent.eventId)
    }

    @Test
    fun reviseEventCannotTouchAnotherMatch() = runBlocking {
        val matchA = createMatch()
        val matchB = createMatch()
        repository.appendEvent(matchA, questStarted(1, "rev-a"))
        val bEvent = repository.appendEvent(matchB, questStarted(1, "rev-b"))

        val beforeA = database.matchEventDao().listAll(matchA.raw).size
        val replacement = questStarted(1, "rev-new").copy(leaderSeat = 3)
        val result = repository.reviseEvent(matchA, bEvent.eventId!!, replacement)

        assertEquals(
            "跨对局修订必须被拒绝",
            RepositoryError.INVALID_SUPERSEDE_TARGET,
            result.error
        )
        assertEquals(
            "A 不得新增事件",
            beforeA,
            database.matchEventDao().listAll(matchA.raw).size
        )
        assertEquals(
            "B 不得被撤销",
            0,
            database.matchEventDao().listAll(matchB.raw).count { it.retracted }
        )
    }

    @Test
    fun reviseSupersedeRollsBackWhenTargetBelongsToAnotherMatch() = runBlocking {
        val matchA = createMatch()
        val matchB = createMatch()
        val aEvent = repository.appendEvent(matchA, questStarted(1, "tx-a"))
        val bEvent = repository.appendEvent(matchB, questStarted(1, "tx-b"))

        var threw = false
        try {
            // 绕过仓储前置校验，直接打 DAO：事务内必须先撤销成功才允许插入替代事件
            database.matchEventDao().reviseWithSlot(
                matchId = matchA.raw,
                oldEventId = bEvent.eventId!!.raw,
                entityProvider = { slot ->
                    EventCodec().encode(
                        questStarted(1, "tx-new"),
                        matchA,
                        slot.occurredOrder,
                        slot.logicalPosition,
                        slot.revisionSeq
                    )
                },
                now = baseEpochMs
            )
        } catch (e: IllegalStateException) {
            threw = true
        }

        assertTrue("目标不属于本局时必须抛异常以回滚事务", threw)
        assertEquals(
            "回滚后 A 不得留下替代事件",
            1,
            database.matchEventDao().listAll(matchA.raw).size
        )
        assertEquals(
            "A 原事件仍有效",
            0,
            database.matchEventDao().listAll(matchA.raw).count { it.retracted }
        )
        assertEquals(
            "B 原事件仍有效",
            0,
            database.matchEventDao().listAll(matchB.raw).count { it.retracted }
        )
        assertNotNull(aEvent.eventId)
    }

    // ————————————————————————————————————————————————————————
    // RT-001：appendEvent 不得把 revealedAt 抹成 null
    // ————————————————————————————————————————————————————————

    /** 追加一次「全员赞成 + 0 张失败牌」的完整任务闭环。 */
    private suspend fun appendWinningQuest(
        matchId: MatchId,
        questNo: Int,
        leader: Int,
        team: List<Int>
    ) {
        repository.appendEvent(matchId, questStarted(questNo, "q$questNo-started").copy(leaderSeat = leader))
        repository.appendEvent(
            matchId,
            TeamProposed(
                eventId = EventId("q$questNo-proposal"),
                occurredOrder = 0,
                recordedAt = 0L,
                questNo = questNo,
                proposalNo = 1,
                leaderSeat = leader,
                teamSeats = team
            )
        )
        repository.appendEvent(
            matchId,
            TeamVoteRecorded(
                eventId = EventId("q$questNo-vote"),
                occurredOrder = 0,
                recordedAt = 0L,
                questNo = questNo,
                proposalNo = 1,
                votesBySeat = null,
                approveCount = 8,
                rejectCount = 0,
                playerCountAtVote = 8
            )
        )
        repository.appendEvent(
            matchId,
            TeamProposalResolved(
                eventId = EventId("q$questNo-resolved"),
                occurredOrder = 0,
                recordedAt = 0L,
                questNo = questNo,
                proposalNo = 1,
                decision = ProposalDecision.APPROVED,
                approveCount = 8,
                rejectCount = 0,
                playerCountAtVote = 8
            )
        )
        repository.appendEvent(
            matchId,
            MissionResolved(
                eventId = EventId("q$questNo-mission"),
                occurredOrder = 0,
                recordedAt = 0L,
                questNo = questNo,
                teamSeats = team,
                teamSizeExpected = team.size,
                failCount = 0,
                observedOutcome = MissionOutcome.SUCCESS
            )
        )
    }

    @Test
    fun setStatusActuallyPersistsRevealedAt() = runBlocking {
        val matchId = createMatch()
        val revealedAt = 1_700_000_500_000L

        repository.setStatus(matchId, MatchStatus.FINISHED_GOOD, revealedAt)

        assertEquals(
            "setStatus 必须把 revealedAt 写进 matches",
            revealedAt,
            database.matchDao().findById(matchId.raw)?.revealedAt
        )
    }

    @Test
    fun appendEventPreservesRevealedAt() = runBlocking {
        val matchId = createMatch()
        val revealedAt = 1_700_000_600_000L
        repository.setStatus(matchId, MatchStatus.FINISHED_GOOD, revealedAt)

        // 揭晓之后再补录一条事件：状态投影会重写 status，但绝不能清掉 revealedAt
        repository.appendEvent(matchId, questStarted(1, "after-reveal"))

        assertEquals(
            "补录事件不得抹掉已揭晓时间（RT-001）",
            revealedAt,
            database.matchDao().findById(matchId.raw)?.revealedAt
        )
        assertNotNull(
            "揭晓状态本身也不应被清空",
            database.matchDao().findById(matchId.raw)?.revealedAt
        )
    }

    @Test
    fun threeQuestWinsThenAssassinationKeepsRevealedAtAndGodView() = runBlocking {
        val matchId = createMatch()
        val revealedAt = 1_700_000_700_000L

        // 三次任务成功 → AWAITING_ASSASSINATION
        appendWinningQuest(matchId, questNo = 1, leader = 1, team = listOf(1, 2, 3))
        appendWinningQuest(matchId, questNo = 2, leader = 2, team = listOf(1, 2, 3, 4))
        appendWinningQuest(matchId, questNo = 3, leader = 3, team = listOf(4, 6, 7, 8))

        assertEquals(
            "三次任务成功后应进入待刺杀",
            MatchStatus.AWAITING_ASSASSINATION,
            database.matchDao().findById(matchId.raw)?.status?.let { MatchStatus.valueOf(it) }
        )

        // 揭晓真实身份（1 号梅林，5 号刺客）
        repository.saveRoleAssignments(
            matchId,
            listOf(
                RoleAssignment(1, RoleId.MERLIN, RoleSource.REVEALED_AFTER_END, RevealState.REVEALED),
                RoleAssignment(5, RoleId.ASSASSIN, RoleSource.REVEALED_AFTER_END, RevealState.REVEALED)
            )
        )
        repository.setStatus(matchId, MatchStatus.AWAITING_ASSASSINATION, revealedAt)

        // 刺杀命中梅林 → FINISHED_EVIL
        repository.appendEvent(
            matchId,
            AssassinationRecorded(
                eventId = EventId("assassination"),
                occurredOrder = 0,
                recordedAt = 0L,
                assassinSeat = 5,
                targetSeat = 1,
                hitMerlin = null
            )
        )

        val entity = database.matchDao().findById(matchId.raw)
        assertEquals(
            "刺杀结算为坏人胜",
            MatchStatus.FINISHED_EVIL.name,
            entity?.status
        )
        assertEquals(
            "状态变更不得抹掉 revealedAt（RT-001）",
            revealedAt,
            entity?.revealedAt
        )

        val bundle = repository.getBundle(matchId)!!
        val provider = AvalonKnowledgeProvider(DefaultGameRulesRegistry().avalonReducer)
        assertTrue(
            "已揭晓且已终局时上帝视角必须可用",
            provider.canOpenGodView(bundle.toReconstructedMatch(), MatchStatus.FINISHED_EVIL)
        )
        // 真正构建一次上帝快照，确保 reveal 链路端到端可用
        val snapshot = provider.snapshot(
            match = bundle.toReconstructedMatch(),
            observer = null,
            at = com.roundtrace.app.core.model.GamePosition(
                bundle.events.maxOf { it.occurredOrder }
            ),
            mode = Perspective.GOD_AFTER_REVEAL
        )
        assertEquals(
            "上帝视角应看到 1 号是梅林",
            RoleId.MERLIN,
            snapshot.seatKnowledge.getValue(1).visibleRole
        )
    }

    // ————————————————————————————————————————————————————————
    // RT-004：matches.status 必须始终等于「事件重放派生状态」
    // ————————————————————————————————————————————————————————

    /** 追加一次「全员赞成 + [failCount] 张失败牌」的任务闭环。 */
    private suspend fun appendQuest(
        matchId: MatchId,
        questNo: Int,
        leader: Int,
        team: List<Int>,
        failCount: Int
    ) {
        repository.appendEvent(
            matchId,
            questStarted(questNo, "f$questNo-started").copy(leaderSeat = leader)
        )
        repository.appendEvent(
            matchId,
            TeamProposed(
                eventId = EventId("f$questNo-proposal"),
                occurredOrder = 0,
                recordedAt = 0L,
                questNo = questNo,
                proposalNo = 1,
                leaderSeat = leader,
                teamSeats = team
            )
        )
        repository.appendEvent(
            matchId,
            TeamVoteRecorded(
                eventId = EventId("f$questNo-vote"),
                occurredOrder = 0,
                recordedAt = 0L,
                questNo = questNo,
                proposalNo = 1,
                votesBySeat = null,
                approveCount = 8,
                rejectCount = 0,
                playerCountAtVote = 8
            )
        )
        repository.appendEvent(
            matchId,
            TeamProposalResolved(
                eventId = EventId("f$questNo-resolved"),
                occurredOrder = 0,
                recordedAt = 0L,
                questNo = questNo,
                proposalNo = 1,
                decision = ProposalDecision.APPROVED,
                approveCount = 8,
                rejectCount = 0,
                playerCountAtVote = 8
            )
        )
        repository.appendEvent(
            matchId,
            MissionResolved(
                eventId = EventId("f$questNo-mission"),
                occurredOrder = 0,
                recordedAt = 0L,
                questNo = questNo,
                teamSeats = team,
                teamSizeExpected = team.size,
                failCount = failCount,
                observedOutcome = if (failCount == 0) {
                    MissionOutcome.SUCCESS
                } else {
                    MissionOutcome.FAILED
                }
            )
        )
    }

    /** 用当前库里的事件重放，得到「权威」状态。 */
    private fun derivedStatus(matchId: MatchId): MatchStatus {
        val bundle = runBlocking { repository.getBundle(matchId) }!!
        return ReplayEngine(DefaultGameRulesRegistry().avalonReducer)
            .replay(bundle.toReconstructedMatch())
            .state
            .status
    }

    private fun storedStatus(matchId: MatchId): MatchStatus =
        MatchStatus.valueOf(
            runBlocking { database.matchDao().findById(matchId.raw) }!!.status
        )

    @Test
    fun retractingQuestEventRecomputesMatchStatus() = runBlocking {
        val matchId = createMatch()

        // 三次任务失败 → FINISHED_EVIL
        appendQuest(matchId, questNo = 1, leader = 1, team = listOf(1, 2, 3), failCount = 1)
        appendQuest(matchId, questNo = 2, leader = 2, team = listOf(1, 2, 3, 4), failCount = 1)
        appendQuest(matchId, questNo = 3, leader = 3, team = listOf(4, 6, 7, 8), failCount = 1)

        assertEquals(
            "三次失败应先落成 FINISHED_EVIL",
            MatchStatus.FINISHED_EVIL,
            storedStatus(matchId)
        )

        // 撤销第 3 任务的失败结果 → 只剩 2 次失败
        val thirdMission = database.matchEventDao().listActive(matchId.raw)
            .first { it.id == "f3-mission" }
        repository.retractEvent(matchId, EventId(thirdMission.id))

        val expected = derivedStatus(matchId)
        assertEquals(
            "撤销后重放应只剩两次失败",
            MatchStatus.IN_PROGRESS,
            expected
        )
        assertEquals(
            "matches.status 必须跟着重算（RT-004）",
            expected,
            storedStatus(matchId)
        )
    }

    @Test
    fun revisingQuestEventRecomputesMatchStatus() = runBlocking {
        val matchId = createMatch()

        appendQuest(matchId, questNo = 1, leader = 1, team = listOf(1, 2, 3), failCount = 1)
        appendQuest(matchId, questNo = 2, leader = 2, team = listOf(1, 2, 3, 4), failCount = 1)
        appendQuest(matchId, questNo = 3, leader = 3, team = listOf(4, 6, 7, 8), failCount = 1)
        assertEquals(MatchStatus.FINISHED_EVIL, storedStatus(matchId))

        // 把第 3 任务的失败牌改成 0 张 → 该任务成功 → 2 失败 1 成功 → IN_PROGRESS
        val thirdMission = database.matchEventDao().listActive(matchId.raw)
            .first { it.id == "f3-mission" }
        repository.reviseEvent(
            matchId = matchId,
            targetEventId = EventId(thirdMission.id),
            replacement = MissionResolved(
                eventId = EventId("f3-mission-v2"),
                occurredOrder = 0,
                recordedAt = 0L,
                questNo = 3,
                teamSeats = listOf(4, 6, 7, 8),
                teamSizeExpected = 4,
                failCount = 0,
                observedOutcome = MissionOutcome.SUCCESS
            )
        )

        val expected = derivedStatus(matchId)
        assertEquals("修订后重放应为 IN_PROGRESS", MatchStatus.IN_PROGRESS, expected)
        assertEquals(
            "修订同样必须触发状态重算（RT-004）",
            expected,
            storedStatus(matchId)
        )
    }

    @Test
    fun importBundleStoresDerivedStatusNotDraft() = runBlocking {
        // 先在本库造一局 FINISHED_GOOD：3 次成功 + 刺杀未命中
        val sourceMatch = createMatch()
        appendWinningQuest(sourceMatch, questNo = 1, leader = 1, team = listOf(1, 2, 3))
        appendWinningQuest(sourceMatch, questNo = 2, leader = 2, team = listOf(1, 2, 3, 4))
        appendWinningQuest(sourceMatch, questNo = 3, leader = 3, team = listOf(4, 6, 7, 8))
        repository.saveRoleAssignments(
            sourceMatch,
            listOf(
                RoleAssignment(1, RoleId.MERLIN, RoleSource.REVEALED_AFTER_END, RevealState.REVEALED),
                RoleAssignment(2, RoleId.LOYAL_SERVANT, RoleSource.REVEALED_AFTER_END, RevealState.REVEALED),
                RoleAssignment(5, RoleId.ASSASSIN, RoleSource.REVEALED_AFTER_END, RevealState.REVEALED)
            )
        )
        repository.appendEvent(
            sourceMatch,
            AssassinationRecorded(
                eventId = EventId("miss-assassination"),
                occurredOrder = 0,
                recordedAt = 0L,
                assassinSeat = 5,
                targetSeat = 2,
                hitMerlin = null
            )
        )
        assertEquals(
            "源对局应为好人胜",
            MatchStatus.FINISHED_GOOD,
            storedStatus(sourceMatch)
        )

        // 用源对局的内容做导入
        val source = repository.getBundle(sourceMatch)!!
        val imported = repository.importBundle(
            com.roundtrace.app.core.data.ImportBundle(
                config = source.config,
                participants = source.participants,
                roleAssignments = source.roleAssignments.values.toList(),
                events = source.events,
                revealedAt = source.revealedAt
            )
        )

        assertNotEquals("导入必须新建对局", sourceMatch.raw, imported.raw)
        assertEquals(
            "导入后 matches.status 必须是重放派生值，不能落 DRAFT（RT-004）",
            MatchStatus.FINISHED_GOOD,
            storedStatus(imported)
        )
        assertEquals(
            "导入后的派生状态与重放结果必须一致",
            derivedStatus(imported),
            storedStatus(imported)
        )
    }
}
