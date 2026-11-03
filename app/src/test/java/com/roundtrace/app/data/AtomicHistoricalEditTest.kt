package com.roundtrace.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.audit.MutableClock
import com.roundtrace.app.core.data.EditApplyResult
import com.roundtrace.app.core.data.RepositoryError
import com.roundtrace.app.core.data.RoomMatchRepository
import com.roundtrace.app.core.data.edit.EditImpactPlan
import com.roundtrace.app.core.data.edit.EditPlanRejection
import com.roundtrace.app.core.data.edit.EditPlanResult
import com.roundtrace.app.core.data.edit.ImpactOperation
import com.roundtrace.app.core.database.EventCodec
import com.roundtrace.app.core.database.RoundTraceDatabase
import com.roundtrace.app.core.database.entity.MatchEventEntity
import com.roundtrace.app.core.model.BeliefType
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventFactory
import com.roundtrace.app.core.model.EventType
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.TeamProposalResolved
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteRecorded
import com.roundtrace.app.core.replay.ReplayEngine
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.util.SequentialIdGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * NX-06 历史安全编辑：**真实 Room + 真实事务**的数据库级证据（A01、A03～A17）。
 *
 * 为什么必须用真库：本轮 P0 的三个缺陷全都出在「事务边界」与「业务返回值」上——
 * 用内存 fake 断言「没调用写方法」只能证明调用图，证明不了
 * 「注入 SQL 失败后所有写入都回滚」「并发双确认只成功一次」。
 *
 * 所有用例都通过 `planHistoricalEdit` 拿到计划（令牌同样由仓储从 DAO 实体行读取），
 * 测试本身**不构造** `ActiveEventToken`，避免「用假令牌证明校验有效」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AtomicHistoricalEditTest {

    private lateinit var database: RoundTraceDatabase
    private lateinit var repository: RoomMatchRepository
    private val registry = DefaultGameRulesRegistry()
    private val clock = MutableClock(1_700_000_000_000L)
    private val ids = SequentialIdGenerator("edit")
    private val events = EventFactory(ids, clock)

    private val playerCount = 8

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RoundTraceDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = RoomMatchRepository(
            database, registry, registry.avalonReducer, EventCodec(), ids, clock
        )
    }

    @After
    fun tearDown() = database.close()

    // ————————————————————————————————————————————————————————
    // 夹具
    // ————————————————————————————————————————————————————————

    private suspend fun createMatch(): MatchId = repository.createMatch(
        MatchCreationDraft(
            title = "历史编辑测试",
            playerCount = playerCount,
            mySeat = 1,
            roleSlots = AvalonPreset.standardPreset(playerCount),
            participants = MatchCreationDraft.defaultParticipants(playerCount),
            initialLeaderSeat = 1,
            presetId = AvalonPreset.presetId(playerCount)
        )
    )

    private suspend fun append(match: MatchId, event: MatchEvent) {
        val result = repository.appendEvent(match, event)
        assertTrue("前置写入失败：${result.error}", result.isSuccess)
    }

    /** 一次完整的「提案 → 投票 → 决议 → 任务结果」。 */
    private suspend fun appendCycle(
        match: MatchId,
        questNo: Int,
        leaderSeat: Int,
        teamSeats: List<Int>,
        failCount: Int?,
        decision: ProposalDecision = ProposalDecision.APPROVED,
        approve: Int = playerCount,
        reject: Int = 0,
        missionTeamSeats: List<Int>? = teamSeats
    ) {
        append(match, events.questStarted(questNo, leaderSeat))
        append(match, events.teamProposed(questNo, 1, leaderSeat, teamSeats))
        append(
            match,
            events.teamVoteRecorded(
                questNo = questNo,
                proposalNo = 1,
                playerCountAtVote = playerCount,
                approveCount = approve,
                rejectCount = reject
            )
        )
        append(
            match,
            events.teamProposalResolved(
                questNo = questNo,
                proposalNo = 1,
                decision = decision,
                playerCountAtVote = playerCount,
                approveCount = approve,
                rejectCount = reject
            )
        )
        append(
            match,
            events.missionResolved(
                questNo = questNo,
                teamSizeExpected = teamSeats.size,
                teamSeats = missionTeamSeats,
                failCount = failCount
            )
        )
    }

    private suspend fun rows(match: MatchId): List<MatchEventEntity> =
        database.matchEventDao().listAll(match.raw)

    private fun MatchEventEntity.isActive(): Boolean = !retracted

    private suspend fun bundle(match: MatchId) = repository.getBundle(match)!!

    private suspend fun planFor(
        match: MatchId,
        targetId: EventId,
        replacement: MatchEvent
    ): EditImpactPlan {
        val result = repository.planHistoricalEdit(match, targetId, replacement)
        assertTrue("期望得到影响计划，实际 $result", result is EditPlanResult.Ready)
        return (result as EditPlanResult.Ready).plan
    }

    private suspend fun apply(
        match: MatchId,
        plan: EditImpactPlan,
        confirmedRetracts: List<EventId> = plan.retractIds
    ): EditApplyResult = repository.applyConfirmedEdit(
        matchId = match,
        targetEventId = plan.targetId,
        replacement = plan.replacement,
        confirmedRetractIds = confirmedRetracts,
        expectedTokens = plan.expectedActive
    )

    private fun EventType.isQuestStructural(): Boolean = this == EventType.QUEST_STARTED ||
        this == EventType.TEAM_PROPOSED ||
        this == EventType.TEAM_VOTE_RECORDED ||
        this == EventType.TEAM_PROPOSAL_RESOLVED ||
        this == EventType.MISSION_RESOLVED

    // ————————————————————————————————————————————————————————
    // A01 / A09：预览与取消都是零写库
    // ————————————————————————————————————————————————————————

    @Test
    fun previewOfHistoricalEditWritesNothing() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0)
        appendCycle(match, 2, 2, listOf(2, 3, 4, 5), failCount = 0)

        val beforeRows = rows(match)
        val beforeMatch = database.matchDao().findById(match.raw)
        val beforeBundle = bundle(match)

        val proposal = beforeBundle.events.filterIsInstance<TeamProposed>().first { it.questNo == 1 }
        val plan = planFor(match, proposal.eventId, events.teamProposed(1, 1, 1, listOf(1, 2, 5)))

        assertTrue("这次修改确实会撤销记录，才能证明「预览没写」有意义", plan.isDestructive)
        assertEquals("A01：预览不得写任何事件行", beforeRows, rows(match))
        assertEquals("A01：预览不得动 matches（含 updatedAt）", beforeMatch, database.matchDao().findById(match.raw))
        assertEquals("A01：预览不得改变可见历史", beforeBundle, bundle(match))
        assertEquals(
            "令牌必须来自 DAO 实体行（数量与 active 事件一致）",
            beforeRows.count { it.isActive() },
            plan.expectedActive.size
        )
    }

    @Test
    fun cancelAfterPreviewLeavesHistoryAndDerivedStateUntouched() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0)
        appendCycle(match, 2, 2, listOf(2, 3, 4, 5), failCount = 0)

        val before = bundle(match)
        val proposal = before.events.filterIsInstance<TeamProposed>().first { it.questNo == 1 }
        val plan = planFor(match, proposal.eventId, events.teamProposed(1, 1, 1, listOf(2, 3, 4)))
        assertTrue(plan.retractIds.isNotEmpty())

        // 用户点「取消」：不调用 applyConfirmedEdit
        val after = bundle(match)
        assertEquals("A09：取消 = 零历史写入", before.events, after.events)
        assertEquals(before.summary.status, after.summary.status)
        assertEquals(
            "A09：派生状态也不得变",
            ReplayEngine(registry.avalonReducer).replay(after.toReconstructedMatch()).state.status,
            after.summary.status
        )
    }

    // ————————————————————————————————————————————————————————
    // A03：改提案 → 只撤销同提案的真实依赖
    // ————————————————————————————————————————————————————————

    @Test
    fun proposalEditExposesSameProposalDependenciesOnly() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0)
        appendCycle(match, 2, 2, listOf(2, 3, 4, 5), failCount = 0)

        val current = bundle(match)
        val proposal = current.events.filterIsInstance<TeamProposed>().first { it.questNo == 1 }
        val vote = current.events.filterIsInstance<TeamVoteRecorded>().first { it.questNo == 1 }
        val resolution = current.events.filterIsInstance<TeamProposalResolved>().first { it.questNo == 1 }
        val mission = current.events.filterIsInstance<MissionResolved>().first { it.questNo == 1 }

        val plan = planFor(match, proposal.eventId, events.teamProposed(1, 1, 1, listOf(1, 2, 5)))

        assertEquals(
            "A03：同提案的投票、决议，以及依赖该提案的任务结果",
            setOf(vote.eventId, resolution.eventId, mission.eventId),
            plan.retractIds.toSet()
        )
        assertTrue(
            "后续任务一条都不许进撤销清单",
            plan.affected.none { (it.questNo ?: 0) > 1 }
        )
    }

    @Test
    fun proposalEditForRejectedProposalKeepsMissionOfAnotherProposal() = runBlocking {
        val match = createMatch()
        // 第 1 任务：提案 1 被否决 → 提案 2 通过 → 任务结果
        append(match, events.questStarted(1, 1))
        append(match, events.teamProposed(1, 1, 1, listOf(1, 2, 3)))
        append(
            match,
            events.teamVoteRecorded(1, 1, playerCount, approveCount = 3, rejectCount = 5)
        )
        append(
            match,
            events.teamProposalResolved(
                1, 1, ProposalDecision.REJECTED, playerCount, approveCount = 3, rejectCount = 5
            )
        )
        append(match, events.teamProposed(1, 2, 2, listOf(2, 3, 4)))
        append(
            match,
            events.teamVoteRecorded(1, 2, playerCount, approveCount = 6, rejectCount = 2)
        )
        append(
            match,
            events.teamProposalResolved(
                1, 2, ProposalDecision.APPROVED, playerCount, approveCount = 6, rejectCount = 2
            )
        )
        append(match, events.missionResolved(1, 3, listOf(2, 3, 4), failCount = 0))

        val current = bundle(match)
        val first = current.events.filterIsInstance<TeamProposed>().first { it.proposalNo == 1 }
        val mission = current.events.filterIsInstance<MissionResolved>().first { it.questNo == 1 }

        val plan = planFor(match, first.eventId, events.teamProposed(1, 1, 1, listOf(1, 2, 4)))

        assertTrue(
            "被否决的提案被改，任务结果不属于它的依赖",
            plan.affected.none { it.eventId == mission.eventId }
        )
    }

    /**
     * D1 回归（QA 发现）：改「被否决的提案」时，**另一个提案**的任务结果被误撤销。
     *
     * 夹具是双胞胎车队：提案 1 `[1,2,3]` 被否决，提案 2 同为 `[1,2,3]` 且通过。
     * 旧实现只看「任务结果记录的车队 == 被改提案的车队」，两个车队相同 → 误判依赖。
     *
     * 这里走完真实链路（计划 → 确认 → 落库），断言**任务结果行仍是 active**。
     */
    @Test
    fun editingRejectedProposalKeepsMissionOfTwinTeamApprovedProposal() = runBlocking {
        val match = createMatch()
        append(match, events.questStarted(1, 1))
        append(match, events.teamProposed(1, 1, 1, listOf(1, 2, 3)))
        append(match, events.teamVoteRecorded(1, 1, playerCount, approveCount = 2, rejectCount = 6))
        append(
            match,
            events.teamProposalResolved(
                1, 1, ProposalDecision.REJECTED, playerCount, approveCount = 2, rejectCount = 6
            )
        )
        // 换队长重提，队伍完全相同
        append(match, events.teamProposed(1, 2, 2, listOf(1, 2, 3)))
        append(match, events.teamVoteRecorded(1, 2, playerCount, approveCount = 6, rejectCount = 2))
        append(
            match,
            events.teamProposalResolved(
                1, 2, ProposalDecision.APPROVED, playerCount, approveCount = 6, rejectCount = 2
            )
        )
        append(match, events.missionResolved(1, 3, listOf(1, 2, 3), failCount = 0))
        appendCycle(match, 2, 3, listOf(3, 4, 5, 6), failCount = 0)

        val current = bundle(match)
        val rejected = current.events.filterIsInstance<TeamProposed>().first { it.proposalNo == 1 }
        val mission = current.events.filterIsInstance<MissionResolved>().first { it.questNo == 1 }

        val plan = planFor(match, rejected.eventId, events.teamProposed(1, 1, 1, listOf(1, 2, 4)))
        assertTrue(
            "D1：任务结果属于另一次通过的提案，不得进影响清单，实际=" +
                plan.affected.map { it.eventId.raw },
            plan.affected.none { it.eventId == mission.eventId }
        )
        assertFalse("D1：撤销清单里不得出现任务结果", mission.eventId in plan.retractIds)

        val result = apply(match, plan)
        assertTrue("期望确认成功，实际 $result", result is EditApplyResult.Success)
        assertFalse(
            "D1：任务结果行必须仍是 active（不能被误撤销）",
            rows(match).single { it.eventIdRaw() == mission.eventId.raw }.retracted
        )
    }

    /**
     * D2 回归（QA 发现）：两轮已结算对局，**原值重新保存第 1 任务第 1 次提案**。
     *
     * 现场最容易触发的路径：打开编辑（原值预填）→ 直接点保存。
     * 旧实现无条件撤销同提案下游事实（投票 / 决议 / 任务结果），一次误触就毁掉 3 条真实记录。
     */
    @Test
    fun resubmittingIdenticalProposalContentWritesNothing() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0)
        appendCycle(match, 2, 2, listOf(2, 3, 4, 5), failCount = 0)

        val current = bundle(match)
        val proposal = current.events.filterIsInstance<TeamProposed>().first { it.questNo == 1 }
        val vote = current.events.filterIsInstance<TeamVoteRecorded>().first { it.questNo == 1 }
        val resolution =
            current.events.filterIsInstance<TeamProposalResolved>().first { it.questNo == 1 }
        val mission = current.events.filterIsInstance<MissionResolved>().first { it.questNo == 1 }

        val rowsBefore = rows(match).map { "${it.id}|${it.revisionSeq}|${it.retracted}" }.sorted()
        val updatedBefore = current.summary.updatedAtEpochMs

        // 队长、队伍与库内完全一致
        val identical = events.teamProposed(1, 1, proposal.leaderSeat, proposal.teamSeats)
        val planned = repository.planHistoricalEdit(match, proposal.eventId, identical)

        assertEquals(
            "P2：内容未修改时必须直接拒绝，不进入预览",
            EditPlanRejection.NO_BUSINESS_CHANGE,
            (planned as? EditPlanResult.Rejected)?.reason
        )

        // 即使 UI 手里还攥着一个「确认」，仓储也必须零写入
        val applied = repository.applyConfirmedEdit(
            matchId = match,
            targetEventId = proposal.eventId,
            replacement = identical,
            confirmedRetractIds = emptyList(),
            expectedTokens = emptyList()
        )
        assertEquals("P2：确认也必须判 NoChange", EditApplyResult.NoChange, applied)

        assertEquals(
            "P2：历史表不得新增任何行（不得 revisionSeq + 1）",
            rowsBefore,
            rows(match).map { "${it.id}|${it.revisionSeq}|${it.retracted}" }.sorted()
        )
        assertEquals("P2：投影不得重算", updatedBefore, bundle(match).summary.updatedAtEpochMs)

        val after = rows(match).associateBy { it.eventIdRaw() }
        assertFalse("D2：投票不得被撤销", after.getValue(vote.eventId.raw).retracted)
        assertFalse("D2：决议不得被撤销", after.getValue(resolution.eventId.raw).retracted)
        assertFalse("D2：任务结果不得被撤销", after.getValue(mission.eventId.raw).retracted)
    }

    // ————————————————————————————————————————————————————————
    // A04 / A05：改票不静默改决议（B35）
    // ————————————————————————————————————————————————————————

    @Test
    fun voteEditConsistentWithDecisionDoesNotTouchResolution() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0)
        appendCycle(match, 2, 2, listOf(2, 3, 4, 5), failCount = 0)

        val current = bundle(match)
        val vote = current.events.filterIsInstance<TeamVoteRecorded>().first { it.questNo == 1 }
        val plan = planFor(
            match,
            vote.eventId,
            events.teamVoteRecorded(1, 1, playerCount, approveCount = 6, rejectCount = 2)
        )

        assertTrue("票数与决议一致时无影响", plan.affected.isEmpty())
        assertTrue("A08：无影响也要能确认", apply(match, plan) is EditApplyResult.Success)

        val after = bundle(match)
        assertEquals(
            "只应修订投票，不得新增/改写决议",
            1,
            after.events.count { it.type == EventType.TEAM_PROPOSAL_RESOLVED && it.questNo == 1 }
        )
        assertEquals(
            "决议仍然是原来那一条事件",
            current.events.filterIsInstance<TeamProposalResolved>()
                .first { it.questNo == 1 }.eventId,
            after.events.filterIsInstance<TeamProposalResolved>().first { it.questNo == 1 }.eventId
        )
    }

    @Test
    fun voteEditConflictingWithDecisionIsReviewOnlyAndNeverRewritesResolution() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0)
        appendCycle(match, 2, 2, listOf(2, 3, 4, 5), failCount = 0)

        val current = bundle(match)
        val vote = current.events.filterIsInstance<TeamVoteRecorded>().first { it.questNo == 1 }
        val resolution = current.events.filterIsInstance<TeamProposalResolved>().first { it.questNo == 1 }

        val plan = planFor(
            match,
            vote.eventId,
            events.teamVoteRecorded(1, 1, playerCount, approveCount = 2, rejectCount = 6)
        )

        assertEquals(
            "A05：冲突只体现在同提案决议上，且只能是「仅提示」",
            listOf(ImpactOperation.REVIEW_ONLY),
            plan.affected.map { it.operation }
        )
        assertEquals(listOf(resolution.eventId), plan.affected.map { it.eventId })
        assertTrue("必须给出人工复核原因", plan.manualReviewReasons.isNotEmpty())
        assertFalse("A05：改票本身不撤销任何记录", plan.isDestructive)

        assertTrue(apply(match, plan) is EditApplyResult.Success)

        val resolutionRow = database.matchEventDao().findById(resolution.eventId.raw)!!
        assertFalse("A05：决议不得被静默撤销/改写", resolutionRow.retracted)
        assertEquals(
            "决议的内容必须一字未动",
            resolution,
            bundle(match).events.filterIsInstance<TeamProposalResolved>().first { it.questNo == 1 }
        )
        assertTrue("旧投票被修订下线", database.matchEventDao().findById(vote.eventId.raw)!!.retracted)
    }

    // ————————————————————————————————————————————————————————
    // A06 / A07 / A08：改任务结果的影响
    // ————————————————————————————————————————————————————————

    @Test
    fun missionEditWithSameOutcomeWritesNothing() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0)
        appendCycle(match, 2, 2, listOf(2, 3, 4, 5), failCount = 0)

        val current = bundle(match)
        val mission = current.events.filterIsInstance<MissionResolved>().first { it.questNo == 1 }
        val sameOutcome = events.missionResolved(1, 3, listOf(1, 2, 3), failCount = 0)

        // A06 + P2：结果一字未改 → 直接拒绝，连修订都不发
        assertEquals(
            "A06：结果没变时必须是 NO_BUSINESS_CHANGE",
            EditPlanRejection.NO_BUSINESS_CHANGE,
            (repository.planHistoricalEdit(match, mission.eventId, sameOutcome)
                as? EditPlanResult.Rejected)?.reason
        )

        assertEquals(
            "P2：零写入（确认也只会被判 NoChange）",
            EditApplyResult.NoChange,
            repository.applyConfirmedEdit(
                matchId = match,
                targetEventId = mission.eventId,
                replacement = sameOutcome,
                confirmedRetractIds = emptyList(),
                expectedTokens = emptyList()
            )
        )
        assertEquals(
            "A06：active 事件集合一条都不能变",
            current.events.size,
            bundle(match).events.size
        )
    }

    @Test
    fun missionEditThatMakesMatchDecidedRetractsOnlyImpossibleEvents() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 1)
        appendCycle(match, 2, 2, listOf(1, 2, 3, 4), failCount = 1)
        appendCycle(match, 3, 3, listOf(1, 2, 3, 4), failCount = 0)
        appendCycle(match, 4, 4, listOf(1, 2, 3, 4, 5), failCount = 0)
        append(
            match,
            events.playerClaimed(
                speakerSeat = 5,
                claimType = ClaimType.ROLE_CLAIM,
                questNo = 4,
                claimedRole = RoleId.MERLIN
            )
        )
        append(
            match,
            events.privateNoteAdded(
                observerSeat = 1,
                beliefType = BeliefType.SUSPECT,
                availableFromOrder = 1,
                questNo = 4,
                targetSeats = listOf(5)
            )
        )

        val current = bundle(match)
        val mission3 = current.events.filterIsInstance<MissionResolved>().first { it.questNo == 3 }
        val claim = current.events.first { it.type == EventType.PLAYER_CLAIMED }
        val note = current.events.first { it.type == EventType.PRIVATE_NOTE_ADDED }

        // 第 3 任务由成功改成 2 张失败牌（门槛 1）→ 坏人第 3 胜 → 当场终局
        val plan = planFor(
            match,
            mission3.eventId,
            events.missionResolved(3, 4, listOf(1, 2, 3, 4), failCount = 2)
        )

        val fourthQuest = current.events.filter { it.questNo == 4 && it.type.isQuestStructural() }
        assertEquals(
            "A07：只有第 4 任务的任务性事件真正失去前提",
            fourthQuest.map { it.eventId }.toSet(),
            plan.retractIds.toSet()
        )
        assertFalse("A17：发言不得被撤销", plan.retractIds.contains(claim.eventId))
        assertFalse("A17：私人笔记不得被撤销", plan.retractIds.contains(note.eventId))

        assertTrue(apply(match, plan) is EditApplyResult.Success)

        val after = bundle(match)
        assertTrue("修改后对局应在该结果处终局", after.summary.status == MatchStatus.FINISHED_EVIL)
        assertTrue(
            "A17：无关笔记/声称仍然有效",
            after.events.any { it.eventId == claim.eventId } &&
                after.events.any { it.eventId == note.eventId }
        )
        assertEquals(
            "A17：笔记的可见性不得被改动（隐私不泄漏）",
            note.visibility,
            after.events.first { it.eventId == note.eventId }.visibility
        )
        assertTrue(
            "第 4 任务的行仍在库里（撤销≠物理删除）",
            rows(match).any { it.eventIdRaw() == fourthQuest.first().eventId.raw }
        )
    }

    // ————————————————————————————————————————————————————————
    // A11 / A12 / A16：确认=单事务写；失败整体回滚；修订链
    // ————————————————————————————————————————————————————————

    @Test
    fun confirmedEditWritesRevisionAndRetractionsInOneTransaction() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0)
        appendCycle(match, 2, 2, listOf(2, 3, 4, 5), failCount = 0)

        val current = bundle(match)
        val proposal = current.events.filterIsInstance<TeamProposed>().first { it.questNo == 1 }
        val targetRowBefore = database.matchEventDao().findById(proposal.eventId.raw)!!
        val rowCountBefore = rows(match).size
        val updatedAtBefore = database.matchDao().findById(match.raw)!!.updatedAt

        val replacement = events.teamProposed(1, 1, 1, listOf(1, 2, 5))
        val plan = planFor(match, proposal.eventId, replacement)
        clock.advanceBy(10_000L)

        val applied = apply(match, plan)
        assertTrue("A11：确认应成功，实际 $applied", applied is EditApplyResult.Success)
        assertEquals(replacement.eventId, (applied as EditApplyResult.Success).replacementEventId)

        assertTrue(
            "A11：确认的每一条撤销都必须真的落地",
            plan.retractIds.all { database.matchEventDao().findById(it.raw)!!.retracted }
        )
        assertEquals(
            "A11：只新增一条行（修订版本），撤销不产生新行",
            rowCountBefore + 1,
            rows(match).size
        )
        assertNotEquals(
            "A11：updatedAt 在事务内刷新且只刷一次",
            updatedAtBefore,
            database.matchDao().findById(match.raw)!!.updatedAt
        )
        assertEquals(
            "A11：投影与重放一致",
            ReplayEngine(registry.avalonReducer).replay(bundle(match).toReconstructedMatch()).state.status,
            bundle(match).summary.status
        )

        // A16：修订链语义
        val newRow = rows(match).first { it.eventIdRaw() == replacement.eventId.raw }
        assertEquals("A16：修订必须继承逻辑位置", targetRowBefore.logicalPosition, newRow.logicalPosition)
        assertEquals("A16：修订序号 +1", targetRowBefore.revisionSeq + 1, newRow.revisionSeq)
        assertEquals("A16：审计链指向被替代事件", proposal.eventId.raw, newRow.supersedesId)
        assertTrue("A16：旧版本下线但仍在库里", database.matchEventDao().findById(proposal.eventId.raw)!!.retracted)
        assertEquals(
            "A16：同一逻辑位置在读路径上只有一条 active",
            1,
            bundle(match).events.count { it.occurredOrder == targetRowBefore.logicalPosition }
        )
    }

    @Test
    fun injectedFailureRollsBackEveryWriteOfTheEdit() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0)
        appendCycle(match, 2, 2, listOf(2, 3, 4, 5), failCount = 0)

        val current = bundle(match)
        val proposal = current.events.filterIsInstance<TeamProposed>().first { it.questNo == 1 }
        val plan = planFor(match, proposal.eventId, events.teamProposed(1, 1, 1, listOf(1, 2, 5)))

        val beforeRows = rows(match)
        val beforeMatch = database.matchDao().findById(match.raw)

        // 最后一步（投影刷新）注入 SQL 失败
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER abort_edit_projection BEFORE UPDATE OF status ON matches " +
                "BEGIN SELECT RAISE(ABORT, 'projection failure'); END"
        )

        val applied = apply(match, plan)
        assertEquals(
            "A12：写失败必须返回 FAILED",
            RepositoryError.WRITE_FAILED,
            (applied as EditApplyResult.Failed).error
        )
        assertEquals("A12：事件行必须整体回滚（含新修订版本）", beforeRows, rows(match))
        assertEquals("A12：matches 也必须回滚", beforeMatch, database.matchDao().findById(match.raw))
        assertFalse(
            "A12：旧事件仍然有效",
            database.matchEventDao().findById(proposal.eventId.raw)!!.retracted
        )
    }

    // ————————————————————————————————————————————————————————
    // A13 / A14：过期预览与重复确认
    // ————————————————————————————————————————————————————————

    @Test
    fun stalePreviewIsRejectedWithoutAnyWrite() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0)
        appendCycle(match, 2, 2, listOf(2, 3, 4, 5), failCount = 0)

        val current = bundle(match)
        val proposal = current.events.filterIsInstance<TeamProposed>().first { it.questNo == 1 }
        val plan = planFor(match, proposal.eventId, events.teamProposed(1, 1, 1, listOf(1, 2, 5)))

        // 预览之后，历史被别人改了（新增第 3 任务的记录）
        clock.advanceBy(1_000L)
        append(match, events.questStarted(3, 3))
        val rowsAfterExternalWrite = rows(match)

        val applied = apply(match, plan)
        assertTrue("A13：必须判定为过期预览，实际 $applied", applied is EditApplyResult.StalePreview)
        val fresh = (applied as EditApplyResult.StalePreview).freshPlan
        assertNotNull("A13：必须给出刷新后的计划供 UI 重新展示", fresh)
        assertTrue(
            "A13：刷新后的计划包含刚写入的事件令牌",
            fresh.expectedActive.size > plan.expectedActive.size
        )
        assertEquals("A13：过期预览必须零写入", rowsAfterExternalWrite, rows(match))
    }

    @Test
    fun confirmedRetractSetMustMatchTheRulesExactly() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0)
        appendCycle(match, 2, 2, listOf(2, 3, 4, 5), failCount = 0)

        val current = bundle(match)
        val proposal = current.events.filterIsInstance<TeamProposed>().first { it.questNo == 1 }
        val plan = planFor(match, proposal.eventId, events.teamProposed(1, 1, 1, listOf(1, 2, 5)))
        assertTrue(plan.retractIds.size >= 2)
        val beforeRows = rows(match)

        val applied = apply(match, plan, confirmedRetracts = plan.retractIds.drop(1))
        assertTrue("少确认一条也必须拒绝，实际 $applied", applied is EditApplyResult.StalePreview)
        assertEquals(beforeRows, rows(match))

        val extra = apply(match, plan, confirmedRetracts = plan.retractIds + EventId("ghost-event"))
        assertTrue("多确认一条也必须拒绝，实际 $extra", extra is EditApplyResult.StalePreview)
        assertEquals(beforeRows, rows(match))
    }

    @Test
    fun doubleConfirmSucceedsAtMostOnce() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0)
        appendCycle(match, 2, 2, listOf(2, 3, 4, 5), failCount = 0)

        val current = bundle(match)
        val proposal = current.events.filterIsInstance<TeamProposed>().first { it.questNo == 1 }
        val plan = planFor(match, proposal.eventId, events.teamProposed(1, 1, 1, listOf(1, 2, 5)))
        val rowCountBefore = rows(match).size

        val results = coroutineScope {
            List(2) { async(Dispatchers.IO) { apply(match, plan) } }.awaitAll()
        }

        assertEquals(
            "A14：并发双确认最多成功一次，实际=$results",
            1,
            results.count { it is EditApplyResult.Success }
        )
        assertEquals(
            "A14：只允许出现一条新的修订版本",
            rowCountBefore + 1,
            rows(match).size
        )
        assertTrue(
            "A14：另一方必须拿到「过期/失败」而不是静默成功，实际=$results",
            results.all { it is EditApplyResult.Success || it is EditApplyResult.StalePreview || it is EditApplyResult.Failed }
        )
    }

    // ————————————————————————————————————————————————————————
    // A15：跨对局不可操作
    // ————————————————————————————————————————————————————————

    @Test
    fun crossMatchEventIdCannotBeEditedInEitherMatch() = runBlocking {
        val matchA = createMatch()
        appendCycle(matchA, 1, 1, listOf(1, 2, 3), failCount = 0)
        val matchB = createMatch()
        appendCycle(matchB, 1, 1, listOf(2, 3, 4), failCount = 0)

        val proposalB = bundle(matchB).events.filterIsInstance<TeamProposed>().first()
        val rowsABefore = rows(matchA)
        val rowsBBefore = rows(matchB)

        val planResult = repository.planHistoricalEdit(
            matchA,
            proposalB.eventId,
            events.teamProposed(1, 1, 1, listOf(1, 2, 5))
        )
        assertTrue(planResult is EditPlanResult.Rejected)
        assertEquals(
            EditPlanRejection.TARGET_NOT_FOUND,
            (planResult as EditPlanResult.Rejected).reason
        )

        val applied = repository.applyConfirmedEdit(
            matchId = matchA,
            targetEventId = proposalB.eventId,
            replacement = events.teamProposed(1, 1, 1, listOf(1, 2, 5)),
            confirmedRetractIds = emptyList(),
            expectedTokens = emptyList()
        )
        assertTrue("A15：必须失败，实际 $applied", applied is EditApplyResult.Failed)

        assertEquals("A15：A 局一行未动", rowsABefore, rows(matchA))
        assertEquals("A15：B 局一行未动", rowsBBefore, rows(matchB))
    }

    @Test
    fun missingMatchIsReportedAsFailure() = runBlocking {
        val missing = MatchId("no-such-match")
        val planResult = repository.planHistoricalEdit(
            missing,
            EventId("e"),
            events.questStarted(1, 1)
        )
        assertTrue(planResult is EditPlanResult.Rejected)
        assertEquals(
            EditPlanRejection.TARGET_NOT_FOUND,
            (planResult as EditPlanResult.Rejected).reason
        )
    }

    // ————————————————————————————————————————————————————————
    // 白名单与人工复核拦阻
    // ————————————————————————————————————————————————————————

    @Test
    fun nonEditableEventTypeIsRejected() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0)

        val resolution = bundle(match).events.filterIsInstance<TeamProposalResolved>().first()
        val planResult = repository.planHistoricalEdit(
            match,
            resolution.eventId,
            events.teamProposalResolved(1, 1, ProposalDecision.REJECTED, playerCount)
        )

        assertTrue(planResult is EditPlanResult.Rejected)
        assertEquals(
            EditPlanRejection.TARGET_NOT_EDITABLE,
            (planResult as EditPlanResult.Rejected).reason
        )
    }

    @Test
    fun destructiveEditWithUnprovableImpactIsRefused() = runBlocking {
        val match = createMatch()
        // 没有决议、任务结果也没记录队伍 → 任务结果是否依赖该提案无法判定
        append(match, events.questStarted(1, 1))
        append(match, events.teamProposed(1, 1, 1, listOf(1, 2, 3)))
        append(match, events.teamVoteRecorded(1, 1, playerCount, approveCount = 8, rejectCount = 0))
        append(match, events.missionResolved(1, 3, teamSeats = null, failCount = 0))

        val proposal = bundle(match).events.filterIsInstance<TeamProposed>().first()
        val plan = planFor(match, proposal.eventId, events.teamProposed(1, 1, 1, listOf(1, 2, 5)))
        assertTrue("前提：既有撤销项又需要人工复核", plan.isDestructive && plan.requiresManualReview)

        val beforeRows = rows(match)
        val applied = apply(match, plan)

        assertTrue("US-06：不可判定的破坏性修改必须被拒，实际 $applied", applied is EditApplyResult.Conflict)
        assertTrue((applied as EditApplyResult.Conflict).reasons.isNotEmpty())
        assertEquals("冲突时零写入", beforeRows, rows(match))
    }

    @Test
    fun noPhysicalDeleteHappensAnywhere() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0)
        appendCycle(match, 2, 2, listOf(2, 3, 4, 5), failCount = 0)

        val rowsBefore = rows(match).size
        val proposal = bundle(match).events.filterIsInstance<TeamProposed>().first { it.questNo == 1 }
        val plan = planFor(match, proposal.eventId, events.teamProposed(1, 1, 1, listOf(1, 2, 5)))
        assertTrue(apply(match, plan) is EditApplyResult.Success)

        assertTrue(
            "撤销只能「标记 retracted」，行数只会增加",
            rows(match).size > rowsBefore
        )
        assertTrue(
            "被撤销的行必须仍在库里（审计链保留）",
            plan.retractIds.all { database.matchEventDao().findById(it.raw) != null }
        )
        assertNull(
            "不得出现悬空 supersedesId",
            rows(match).firstOrNull { it.supersedesId != null && database.matchEventDao().findById(it.supersedesId!!) == null }
        )
    }

    /** 便于断言：实体 id（`MatchEventEntity.id` 与领域 `EventId.raw` 同源）。 */
    private fun MatchEventEntity.eventIdRaw(): String = id
}
