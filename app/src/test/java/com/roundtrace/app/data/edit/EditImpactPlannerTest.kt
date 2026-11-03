package com.roundtrace.app.data.edit

import com.roundtrace.app.core.data.MatchBundle
import com.roundtrace.app.core.data.edit.ActiveEventToken
import com.roundtrace.app.core.data.edit.EditImpactPlan
import com.roundtrace.app.core.data.edit.EditImpactPlanner
import com.roundtrace.app.core.data.edit.EditPlanRejection
import com.roundtrace.app.core.data.edit.EditPlanResult
import com.roundtrace.app.core.data.edit.ImpactOperation
import com.roundtrace.app.core.model.BeliefType
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventType
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.MatchSummary
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.TeamProposalResolved
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteRecorded
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.util.MatchBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NX-06 影响范围纯函数（A2）的表驱动测试。
 *
 * 这些用例全部**不碰数据库**：它们钉住的是「依赖规则」本身，
 * 也就是 PRD §2.2 那张表 —— 任何一条断言失败都意味着某类历史修改会误删或漏删别人的记录。
 */
class EditImpactPlannerTest {

    private val planner = EditImpactPlanner(DefaultGameRulesRegistry().avalonReducer)

    // ————————————————————————————————————————————————————————
    // 夹具
    // ————————————————————————————————————————————————————————

    private fun bundleOf(builder: MatchBuilder): MatchBundle {
        val match = builder.build()
        return MatchBundle(
            summary = MatchSummary(
                matchId = match.matchId,
                title = "计划测试",
                playerCount = match.config.playerCount,
                status = MatchStatus.IN_PROGRESS,
                rulesetId = match.config.rulesetId,
                updatedAtEpochMs = 0L,
                questsStarted = builder.events().count { it.type == EventType.QUEST_STARTED },
                mySeat = match.config.mySeat
            ),
            config = match.config,
            participants = match.participants,
            roleAssignments = match.roleAssignments,
            events = builder.events(),
            revealedAt = null
        )
    }

    /** 令牌：纯函数测试里 `occurredOrder` 就是逻辑位置，修订序号从 0 起。 */
    private fun tokensOf(bundle: MatchBundle): List<ActiveEventToken> = bundle.events.map {
        ActiveEventToken(eventId = it.eventId, logicalPosition = it.occurredOrder, revisionSeq = 0)
    }

    private fun plan(bundle: MatchBundle, targetId: EventId, replacement: MatchEvent): EditImpactPlan {
        val result = planner.plan(bundle, tokensOf(bundle), targetId, replacement)
        assertTrue("期望计划就绪，实际 $result", result is EditPlanResult.Ready)
        return (result as EditPlanResult.Ready).plan
    }

    private fun rejection(bundle: MatchBundle, targetId: EventId, replacement: MatchEvent): EditPlanRejection {
        val result = planner.plan(bundle, tokensOf(bundle), targetId, replacement)
        assertTrue("期望被拒绝，实际 $result", result is EditPlanResult.Rejected)
        return (result as EditPlanResult.Rejected).reason
    }

    private inline fun <reified T : MatchEvent> MatchBundle.event(
        predicate: (T) -> Boolean = { true }
    ): T = events.filterIsInstance<T>().first(predicate)

    /** 用新的 eventId 构造替换事件，模拟「重新作答」，不改逻辑位置（由 planner 借用目标位置）。 */
    private fun TeamProposed.replaced(teamSeats: List<Int> = this.teamSeats): TeamProposed =
        copy(eventId = EventId("replacement-${eventId.raw}"), occurredOrder = 0, teamSeats = teamSeats)

    private fun TeamVoteRecorded.replaced(approve: Int?, reject: Int?): TeamVoteRecorded =
        copy(
            eventId = EventId("replacement-${eventId.raw}"),
            occurredOrder = 0,
            approveCount = approve,
            rejectCount = reject,
            votesBySeat = null
        )

    private fun MissionResolved.replaced(failCount: Int?): MissionResolved =
        copy(eventId = EventId("replacement-${eventId.raw}"), occurredOrder = 0, failCount = failCount)

    // ————————————————————————————————————————————————————————
    // 改提案（TeamProposed）
    // ————————————————————————————————————————————————————————

    @Test
    fun proposalEditRetractsOnlySameProposalDependencies() {
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(1, 1, listOf(1, 2, 3), failCount = 0)
            .questCycle(2, 2, listOf(2, 3, 4, 5), failCount = 0)
        val bundle = bundleOf(builder)

        val target = bundle.event<TeamProposed> { it.questNo == 1 }
        val vote = bundle.event<TeamVoteRecorded> { it.questNo == 1 && it.proposalNo == 1 }
        val resolution = bundle.event<TeamProposalResolved> { it.questNo == 1 && it.proposalNo == 1 }
        val mission = bundle.event<MissionResolved> { it.questNo == 1 }

        val plan = plan(bundle, target.eventId, target.replaced(listOf(1, 2, 5)))

        assertEquals(
            "只应撤销「同任务同提案的投票/决议 + 依赖该提案的任务结果」",
            setOf(vote.eventId, resolution.eventId, mission.eventId),
            plan.retractIds.toSet()
        )
        assertTrue(plan.isDestructive)
        assertFalse("依赖可判定时不应要求人工复核", plan.requiresManualReview)
    }

    @Test
    fun proposalEditNeverTouchesLaterQuests() {
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(1, 1, listOf(1, 2, 3), failCount = 0)
            .questCycle(2, 2, listOf(2, 3, 4, 5), failCount = 0)
            .questCycle(3, 3, listOf(3, 4, 5, 6), failCount = 0)
        val bundle = bundleOf(builder)

        val target = bundle.event<TeamProposed> { it.questNo == 1 }
        val plan = plan(bundle, target.eventId, target.replaced(listOf(1, 2, 4)))

        assertTrue(
            "**不允许按逻辑位置推定**：第 2/3 任务的任何事件都不能进撤销清单，实际=" +
                plan.affected.map { it.eventId.raw },
            plan.affected.all { it.questNo == 1 }
        )
        val laterQuestIds = bundle.events.filter { (it.questNo ?: 0) >= 2 }.map { it.eventId }
        assertTrue(
            "后续任务的事件一条都不许动",
            plan.retractIds.none { it in laterQuestIds }
        )
    }

    @Test
    fun proposalEditKeepsMissionThatBelongsToAnotherProposal() {
        // 第 1 任务：提案 1 被否决，提案 2 通过并执行任务 —— 任务结果不依赖提案 1
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questStarted(1, 1)
            .teamProposed(1, 1, 1, listOf(1, 2, 3))
            .teamVoteRecorded(1, 1, approveCount = 3, rejectCount = 5)
            .teamProposalResolved(1, 1, ProposalDecision.REJECTED, approveCount = 3, rejectCount = 5)
            .teamProposed(1, 2, 2, listOf(2, 3, 4))
            .teamVoteRecorded(1, 2, approveCount = 6, rejectCount = 2)
            .teamProposalResolved(1, 2, ProposalDecision.APPROVED, approveCount = 6, rejectCount = 2)
            .missionResolved(1, listOf(2, 3, 4), failCount = 0)
            .questCycle(2, 3, listOf(3, 4, 5, 6), failCount = 0)
        val bundle = bundleOf(builder)

        val firstProposal = bundle.event<TeamProposed> { it.proposalNo == 1 }
        val firstVote = bundle.event<TeamVoteRecorded> { it.proposalNo == 1 }
        val firstResolution = bundle.event<TeamProposalResolved> { it.proposalNo == 1 }
        val mission = bundle.event<MissionResolved> { it.questNo == 1 }

        val plan = plan(bundle, firstProposal.eventId, firstProposal.replaced(listOf(1, 2, 4)))

        assertEquals(
            "被否决的提案被改时，同任务的任务结果不属于它的依赖",
            setOf(firstVote.eventId, firstResolution.eventId),
            plan.retractIds.toSet()
        )
        assertFalse(plan.affected.any { it.eventId == mission.eventId })
    }

    @Test
    fun ambiguousMissionDependencyRequiresReviewButStillListsRealRetractions() {
        // 没有决议、任务结果也没记录队伍 → 无法判定任务结果是否依赖本次提案修改
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questStarted(1, 1)
            .teamProposed(1, 1, 1, listOf(1, 2, 3))
            .teamVoteRecorded(1, 1, approveCount = 8, rejectCount = 0)
            .missionResolved(1, null, failCount = 0)
        val bundle = bundleOf(builder)

        val target = bundle.event<TeamProposed> { it.questNo == 1 }
        val mission = bundle.event<MissionResolved> { it.questNo == 1 }
        val plan = plan(bundle, target.eventId, target.replaced(listOf(1, 2, 5)))

        assertTrue("投票是确定的依赖，必须撤销", plan.retractIds.isNotEmpty())
        assertTrue("任务结果依赖关系不明 → 必须要求人工复核", plan.requiresManualReview)
        assertEquals(
            "不明依赖的任务结果只能是「仅提示」，绝不能自动撤销",
            ImpactOperation.REVIEW_ONLY,
            plan.affected.first { it.eventId == mission.eventId }.operation
        )
        assertEquals(1, plan.reviewOnlyCount)
    }

    // ————————————————————————————————————————————————————————
    // 改投票（TeamVoteRecorded）：B35 —— 永不改决议
    // ————————————————————————————————————————————————————————

    @Test
    fun voteEditConsistentWithRecordedDecisionHasNoImpact() {
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(1, 1, listOf(1, 2, 3), failCount = 0)
            .questCycle(2, 2, listOf(2, 3, 4, 5), failCount = 0)
        val bundle = bundleOf(builder)

        val vote = bundle.event<TeamVoteRecorded> { it.questNo == 1 }
        // 8 人局：6 票赞成仍然通过，与已记录的 APPROVED 一致
        val plan = plan(bundle, vote.eventId, vote.replaced(approve = 6, reject = 2))

        assertTrue("票数与决议一致时不得撤销任何记录", plan.affected.isEmpty())
        assertFalse(plan.isDestructive)
        assertFalse(plan.requiresManualReview)
    }

    @Test
    fun voteEditConflictingWithRecordedDecisionIsReviewOnly() {
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(1, 1, listOf(1, 2, 3), failCount = 0)
            .questCycle(2, 2, listOf(2, 3, 4, 5), failCount = 0)
        val bundle = bundleOf(builder)

        val vote = bundle.event<TeamVoteRecorded> { it.questNo == 1 }
        val resolution = bundle.event<TeamProposalResolved> { it.questNo == 1 }
        // 2 赞成 / 6 反对 → 应为 REJECTED，而记录是 APPROVED
        val plan = plan(bundle, vote.eventId, vote.replaced(approve = 2, reject = 6))

        assertEquals(
            "冲突只允许出现在同提案的决议上",
            listOf(resolution.eventId),
            plan.affected.map { it.eventId }
        )
        assertEquals(
            "决议是不可编辑的现场事实，只能 REVIEW_ONLY",
            ImpactOperation.REVIEW_ONLY,
            plan.affected.single().operation
        )
        assertTrue("必须给出可读的复核原因", plan.manualReviewReasons.isNotEmpty())
        assertFalse("改票本身不得撤销任何记录（B35）", plan.isDestructive)
    }

    // ————————————————————————————————————————————————————————
    // 改任务结果（MissionResolved）
    // ————————————————————————————————————————————————————————

    @Test
    fun missionEditWithIdenticalOutcomeHasNoImpact() {
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(1, 1, listOf(1, 2, 3), failCount = 0)
            .questCycle(2, 2, listOf(2, 3, 4, 5), failCount = 0)
        val bundle = bundleOf(builder)

        val mission = bundle.event<MissionResolved> { it.questNo == 1 }
        val plan = plan(bundle, mission.eventId, mission.replaced(failCount = 0))

        assertTrue("结果没变时不得多删任何事件", plan.affected.isEmpty())
        assertFalse(plan.isDestructive)
    }

    @Test
    fun missionEditChangingOnlyCountsRetractsNothing() {
        // 第 1 任务 8 人局门槛 = 1：0 → 1 只是把「成功」改成「失败」，对局并未终局
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(1, 1, listOf(1, 2, 3), failCount = 0)
            .questCycle(2, 2, listOf(2, 3, 4, 5), failCount = 0)
        val bundle = bundleOf(builder)

        val mission = bundle.event<MissionResolved> { it.questNo == 1 }
        val plan = plan(bundle, mission.eventId, mission.replaced(failCount = 1))

        assertTrue(
            "胜负计数变化本身由重放自动重算，不得撤销任何事件，实际=" +
                plan.affected.map { it.eventId.raw },
            plan.affected.isEmpty()
        )
    }

    @Test
    fun missionEditThatMakesMatchDecidedRetractsOnlyImpossibleRecords() {
        // 前两个任务失败（2 个坏人胜场），第 3 任务记录为成功，第 4 任务已开录
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(1, 1, listOf(1, 2, 3), failCount = 1)
            .questCycle(2, 2, listOf(1, 2, 3, 4), failCount = 1)
            .questCycle(3, 3, listOf(1, 2, 3, 4), failCount = 0)
            .questCycle(4, 4, listOf(1, 2, 3, 4, 5), failCount = 0)
            .playerClaimed(
                speakerSeat = 5,
                claimType = ClaimType.ROLE_CLAIM,
                questNo = 4,
                claimedRole = RoleId.MERLIN
            )
            .privateNoteAdded(
                observerSeat = 1,
                beliefType = BeliefType.SUSPECT,
                targetSeats = listOf(5),
                questNo = 4
            )
        val bundle = bundleOf(builder)

        val mission = bundle.event<MissionResolved> { it.questNo == 3 }
        // 第 3 任务改成失败 → 坏人第 3 个胜场 → 对局在该结果处终局
        val plan = plan(bundle, mission.eventId, mission.replaced(failCount = 2))

        val questFourEvents = bundle.events.filter { it.questNo == 4 && it.type in QUEST_STRUCTURAL }
        assertEquals(
            "第 4 任务的任务性事件全部失去前提",
            questFourEvents.map { it.eventId }.toSet(),
            plan.retractIds.toSet()
        )
        assertTrue(
            "发言与私人笔记是已发生的现场信息，终局后依然成立，不得撤销（A17）",
            plan.affected.none { it.eventType == EventType.PLAYER_CLAIMED }
        )
        assertTrue(
            "私人笔记同样不得撤销",
            plan.affected.none { it.eventType == EventType.PRIVATE_NOTE_ADDED }
        )
    }

    // ————————————————————————————————————————————————————————
    // 拒绝路径
    // ————————————————————————————————————————————————————————

    @Test
    fun nonEditableTypeIsRejected() {
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(1, 1, listOf(1, 2, 3), failCount = 0)
        val bundle = bundleOf(builder)

        val resolution = bundle.event<TeamProposalResolved> { it.questNo == 1 }
        val replacement = resolution.copy(eventId = EventId("replacement-resolution"))
        assertEquals(
            EditPlanRejection.TARGET_NOT_EDITABLE,
            rejection(bundle, resolution.eventId, replacement)
        )
    }

    @Test
    fun missingTargetIsRejectedInsteadOfSilentlySucceeding() {
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(1, 1, listOf(1, 2, 3), failCount = 0)
        val bundle = bundleOf(builder)

        val proposal = bundle.event<TeamProposed> { it.questNo == 1 }
        assertEquals(
            EditPlanRejection.TARGET_NOT_FOUND,
            rejection(bundle, EventId("no-such-event"), proposal.replaced(listOf(9, 9, 9)))
        )
    }

    @Test
    fun staleTokenSetIsRejectedAsInconsistentSnapshot() {
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(1, 1, listOf(1, 2, 3), failCount = 0)
        val bundle = bundleOf(builder)

        val proposal = bundle.event<TeamProposed> { it.questNo == 1 }
        val staleTokens = tokensOf(bundle).dropLast(1)
        val result = planner.plan(bundle, staleTokens, proposal.eventId, proposal.replaced(listOf(1, 2, 5)))

        assertTrue(result is EditPlanResult.Rejected)
        assertEquals(
            EditPlanRejection.INCONSISTENT_SNAPSHOT,
            (result as EditPlanResult.Rejected).reason
        )
    }

    @Test
    fun duplicateReplacementIdIsRejected() {
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(1, 1, listOf(1, 2, 3), failCount = 0)
        val bundle = bundleOf(builder)

        val proposal = bundle.event<TeamProposed> { it.questNo == 1 }
        val other = bundle.event<TeamVoteRecorded> { it.questNo == 1 }
        val clashing = proposal.copy(eventId = other.eventId, teamSeats = listOf(1, 2, 4))

        assertEquals(
            EditPlanRejection.DUPLICATE_EVENT_ID,
            rejection(bundle, proposal.eventId, clashing)
        )
    }

    @Test
    fun planIsDeterministicAndKeepsExpectedTokens() {
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(1, 1, listOf(1, 2, 3), failCount = 0)
            .questCycle(2, 2, listOf(2, 3, 4, 5), failCount = 0)
        val bundle = bundleOf(builder)

        val target = bundle.event<TeamProposed> { it.questNo == 1 }
        val replacement = target.replaced(listOf(1, 2, 5))
        val first = plan(bundle, target.eventId, replacement)
        val second = plan(bundle, target.eventId, replacement)

        assertEquals("同一输入必须得到同一计划（幂等）", first, second)
        assertEquals("令牌必须原样携带，供仓储确认时比对", bundle.events.size, first.expectedActive.size)
        assertNotNull(first.affected.firstOrNull())
    }

    private companion object {
        /** 与 `EditImpactPlanner` 内部一致：只有这 5 类是「任务性事件」。 */
        val QUEST_STRUCTURAL: Set<EventType> = setOf(
            EventType.QUEST_STARTED,
            EventType.TEAM_PROPOSED,
            EventType.TEAM_VOTE_RECORDED,
            EventType.TEAM_PROPOSAL_RESOLVED,
            EventType.MISSION_RESOLVED
        )
    }
}
