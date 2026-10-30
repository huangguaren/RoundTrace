package com.roundtrace.app.rules

import com.roundtrace.app.core.model.IssueCode
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.RecordPhase
import com.roundtrace.app.core.rules.avalon.AvalonRules
import com.roundtrace.app.util.MatchBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R02 / R03 + 提案可判定性（ARCHITECTURE §6.3，TASKS.md T24）。
 *
 * 核心红线：**平票视为否决**；且部分票未知时，只要「数学上已经确定」就能判定，
 * 只有可能翻转时才 UNKNOWN。
 */
class AvalonVoteRulesTest {

    private val rules = AvalonRules()

    @Test
    fun `R02 八人局四赞成四反对视为否决`() {
        assertFalse(
            "8 人局 4 赞成不超过半数（4 > 4 为 false），必须否决",
            rules.isTeamApproved(approveCount = 4, playerCount = 8)
        )
        assertEquals(
            "R02 可判定性应为 REJECTED（平票视为否决）",
            ProposalDecision.REJECTED,
            rules.decideProposal(approveCount = 4, rejectCount = 4, unknownVoteCount = 0, playerCount = 8)
        )

        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questStarted(1, 1)
            .teamProposed(1, 1, 1, listOf(1, 2, 3))
            .teamVoteRecorded(1, 1, approveCount = 4, rejectCount = 4)
            .teamProposalResolved(1, 1, ProposalDecision.REJECTED, approveCount = 4, rejectCount = 4)

        val result = builder.replayResult()
        assertEquals(
            "R02 否决后应回到「可再次提案」阶段",
            RecordPhase.PROPOSAL_OPEN,
            result.state.phase
        )
        assertEquals(
            "R02 否决计数应为 1",
            1,
            result.state.consecutiveRejections
        )
        assertTrue(
            "R02 决议与票数自洽，不应产生 DECISION_VOTE_CONFLICT，实际 issues=${result.issues.map { it.code }}",
            result.issues.none { it.code == IssueCode.DECISION_VOTE_CONFLICT }
        )
        assertEquals(
            "R02 否决时不应进入任务阶段，currentProposalNo 应前进到 2",
            2,
            result.state.currentProposalNo
        )
    }

    @Test
    fun `R03 八人局五赞成三反对视为通过`() {
        assertTrue(
            "8 人局 5 赞成超过半数（5 > 4），必须通过",
            rules.isTeamApproved(approveCount = 5, playerCount = 8)
        )
        assertEquals(
            "R03 可判定性应为 APPROVED",
            ProposalDecision.APPROVED,
            rules.decideProposal(approveCount = 5, rejectCount = 3, unknownVoteCount = 0, playerCount = 8)
        )

        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questStarted(1, 1)
            .teamProposed(1, 1, 1, listOf(1, 2, 3))
            .teamVoteRecorded(1, 1, approveCount = 5, rejectCount = 3)
            .teamProposalResolved(1, 1, ProposalDecision.APPROVED, approveCount = 5, rejectCount = 3)

        val state = builder.replay()
        assertEquals(
            "R03 通过后应进入任务待结算阶段",
            RecordPhase.MISSION_PENDING,
            state.phase
        )
        assertEquals(
            "R03 通过后否决计数应归零",
            0,
            state.consecutiveRejections
        )
        assertEquals(
            "R03 通过队伍应取自提案",
            listOf(1, 2, 3),
            state.quests.first { it.questNo == 1 }.teamSeats
        )
    }

    @Test
    fun `R02R03 部分票未知时的三分支可判定性`() {
        // 赞成 4、反对 1、未知 3（8 人）：4 + 3 = 7 > 4 → 可能翻转 → UNKNOWN
        assertEquals(
            "赞成 4 / 反对 1 / 未知 3 在 8 人局无法判定",
            ProposalDecision.UNKNOWN,
            rules.decideProposal(approveCount = 4, rejectCount = 1, unknownVoteCount = 3, playerCount = 8)
        )
        // 赞成 5：即使还有未知票，数学上已过半 → APPROVED
        assertEquals(
            "赞成 5 已过半数，未知票不能改变结果",
            ProposalDecision.APPROVED,
            rules.decideProposal(approveCount = 5, rejectCount = 0, unknownVoteCount = 3, playerCount = 8)
        )
        // 赞成 2、反对 5：剩余 1 票全赞成也只有 3 ≤ 4 → REJECTED
        assertEquals(
            "赞成 2 / 反对 5 时剩余票无法翻盘，应否决",
            ProposalDecision.REJECTED,
            rules.decideProposal(approveCount = 2, rejectCount = 5, unknownVoteCount = null, playerCount = 8)
        )
        // 赞成票未记录 → 一律 UNKNOWN（不填不等于 0）
        assertEquals(
            "赞成票为 null 时必须 UNKNOWN，不能当作 0",
            ProposalDecision.UNKNOWN,
            rules.decideProposal(approveCount = null, rejectCount = 0, unknownVoteCount = null, playerCount = 8)
        )
    }

    @Test
    fun `R02R03 奇数人数的半数判定`() {
        // 7 人：n/2 = 3，4 票才通过
        assertFalse("7 人局 3 赞成应否决", rules.isTeamApproved(3, 7))
        assertTrue("7 人局 4 赞成应通过", rules.isTeamApproved(4, 7))
        // 5 人：n/2 = 2，3 票才通过
        assertFalse("5 人局 2 赞成应否决", rules.isTeamApproved(2, 5))
        assertTrue("5 人局 3 赞成应通过", rules.isTeamApproved(3, 5))
    }
}
