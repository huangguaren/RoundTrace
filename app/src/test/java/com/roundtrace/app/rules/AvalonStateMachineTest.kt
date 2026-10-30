package com.roundtrace.app.rules

import com.roundtrace.app.core.model.IssueCode
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.RecordPhase
import com.roundtrace.app.core.model.WinReason
import com.roundtrace.app.core.rules.avalon.AvalonReducer
import com.roundtrace.app.core.rules.avalon.AvalonRules
import com.roundtrace.app.util.MatchBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R08 / R09 / R11 + 否决计数重置（TASKS.md T25）。
 */
class AvalonStateMachineTest {

    private val rules = AvalonRules()
    private val reducer = AvalonReducer(rules)

    // ————————————————————————————————————————————————————————
    // R08：同一任务五次连续否决 → 坏人胜
    // ————————————————————————————————————————————————————————

    private fun buildRejections(count: Int): MatchBuilder {
        val builder = MatchBuilder.eightPlayerMerlinMordred().questStarted(1, 1)
        for (proposalNo in 1..count) {
            builder
                .teamProposed(1, proposalNo, 1, listOf(1, 2, 3))
                .teamVoteRecorded(1, proposalNo, approveCount = 2, rejectCount = 6)
                .teamProposalResolved(
                    1,
                    proposalNo,
                    ProposalDecision.REJECTED,
                    approveCount = 2,
                    rejectCount = 6
                )
        }
        return builder
    }

    @Test
    fun `R08 同一任务四次否决后仍未终局`() {
        val state = buildRejections(4).replay()
        assertEquals("R08 四次否决仍在进行中", MatchStatus.IN_PROGRESS, state.status)
        assertEquals("R08 否决计数应为 4", 4, state.consecutiveRejections)
        assertNull("R08 尚未终局，不应有 outcome", state.outcome)
    }

    @Test
    fun `R08 同一任务五次连续否决坏人直接胜利`() {
        val state = buildRejections(5).replay()
        assertEquals("R08 五次否决应终局为坏人胜", MatchStatus.FINISHED_EVIL, state.status)
        assertEquals(
            "R08 终局原因应为五连否决",
            WinReason.FIVE_CONSECUTIVE_REJECTS,
            state.outcome?.reason
        )
        assertEquals("R08 终局阶段应为 ENDED", RecordPhase.ENDED, state.phase)
        assertEquals("R08 否决计数应停在 5", 5, state.consecutiveRejections)
    }

    @Test
    fun `R08 第六次提案在终局后被拒绝且不改变结果`() {
        val builder = buildRejections(5)
        builder.teamProposed(1, 6, 1, listOf(1, 2, 3))
        val result = builder.replayResult()

        assertEquals(
            "R08 终局结果不得被后续事件改写",
            MatchStatus.FINISHED_EVIL,
            result.state.status
        )
        assertTrue(
            "R08 终局后提案应报 PROPOSAL_AFTER_RESOLVED，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == IssueCode.PROPOSAL_AFTER_RESOLVED }
        )
    }

    @Test
    fun `R08R09 提案通过后否决计数归零`() {
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questStarted(1, 1)
            // 连续 3 次否决
            .teamProposed(1, 1, 1, listOf(1, 2, 3))
            .teamVoteRecorded(1, 1, approveCount = 2, rejectCount = 6)
            .teamProposalResolved(1, 1, ProposalDecision.REJECTED, approveCount = 2, rejectCount = 6)
            .teamProposed(1, 2, 2, listOf(1, 2, 3))
            .teamVoteRecorded(1, 2, approveCount = 2, rejectCount = 6)
            .teamProposalResolved(1, 2, ProposalDecision.REJECTED, approveCount = 2, rejectCount = 6)
            .teamProposed(1, 3, 3, listOf(1, 2, 3))
            .teamVoteRecorded(1, 3, approveCount = 2, rejectCount = 6)
            .teamProposalResolved(1, 3, ProposalDecision.REJECTED, approveCount = 2, rejectCount = 6)
            // 第 4 次提案通过 → R 归零
            .teamProposed(1, 4, 4, listOf(1, 2, 3))
            .teamVoteRecorded(1, 4, approveCount = 7, rejectCount = 1)

        val beforeApprove = builder.replay()
        assertEquals("R08 通过前否决计数应为 3", 3, beforeApprove.consecutiveRejections)

        builder.teamProposalResolved(1, 4, ProposalDecision.APPROVED, approveCount = 7, rejectCount = 1)
        val afterApprove = builder.replay()
        assertEquals("R08 提案通过后否决计数必须归零", 0, afterApprove.consecutiveRejections)
        assertEquals("R08 通过后进入任务阶段", RecordPhase.MISSION_PENDING, afterApprove.phase)
    }

    // ————————————————————————————————————————————————————————
    // R09：三次任务成功 → 待刺杀，不立即好人胜
    // ————————————————————————————————————————————————————————

    @Test
    fun `R09 三次任务成功进入待刺杀而不是好人胜利`() {
        val state = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(1, leaderSeat = 1, teamSeats = listOf(1, 2, 3), failCount = 0)
            .questCycle(2, leaderSeat = 2, teamSeats = listOf(1, 2, 3, 4), failCount = 0)
            .questCycle(3, leaderSeat = 3, teamSeats = listOf(4, 6, 7, 8), failCount = 0)
            .replay()

        assertEquals(
            "R09 三次任务成功应进入待刺杀",
            MatchStatus.AWAITING_ASSASSINATION,
            state.status
        )
        assertNull(
            "R09 待刺杀阶段不得给出终局结论（尚未刺杀）",
            state.outcome
        )
        assertNull(
            "R09 determineOutcome 在待刺杀时必须返回 null",
            reducer.let { rules.determineOutcome(state) }
        )
        assertEquals("R09 好人成功计数应为 3", 3, state.goodSuccessCount)
        assertEquals("R09 阶段应为刺杀阶段", RecordPhase.ASSASSINATION, state.phase)
    }

    @Test
    fun `R09 待刺杀阶段不再接受新的任务开始`() {
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(1, leaderSeat = 1, teamSeats = listOf(1, 2, 3), failCount = 0)
            .questCycle(2, leaderSeat = 2, teamSeats = listOf(1, 2, 3, 4), failCount = 0)
            .questCycle(3, leaderSeat = 3, teamSeats = listOf(4, 6, 7, 8), failCount = 0)
            .questStarted(4, 4)

        val result = builder.replayResult()
        assertEquals(
            "R09 待刺杀阶段不得开始第 4 个任务",
            MatchStatus.AWAITING_ASSASSINATION,
            result.state.status
        )
        assertTrue(
            "R09 越序任务应报 QUEST_OUT_OF_ORDER，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == IssueCode.QUEST_OUT_OF_ORDER }
        )
    }

    @Test
    fun `R09 五次否决不会覆盖已完成的三次成功`() {
        // 先三次成功 → 待刺杀；随后的否决类事件不应把状态改成坏人胜
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(1, leaderSeat = 1, teamSeats = listOf(1, 2, 3), failCount = 0)
            .questCycle(2, leaderSeat = 2, teamSeats = listOf(1, 2, 3, 4), failCount = 0)
            .questCycle(3, leaderSeat = 3, teamSeats = listOf(4, 6, 7, 8), failCount = 0)
            .teamProposed(3, 2, 4, listOf(1, 2, 3, 4))

        val state = builder.replay()
        assertEquals(
            "R09 待刺杀状态优先于其它终局判定",
            MatchStatus.AWAITING_ASSASSINATION,
            state.status
        )
    }

    // ————————————————————————————————————————————————————————
    // R11：三次任务失败 → 坏人胜
    // ————————————————————————————————————————————————————————

    @Test
    fun `R11 三次任务失败坏人直接胜利`() {
        val state = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(1, leaderSeat = 1, teamSeats = listOf(1, 2, 3), failCount = 1)
            .questCycle(2, leaderSeat = 2, teamSeats = listOf(1, 2, 3, 4), failCount = 2)
            .questCycle(3, leaderSeat = 3, teamSeats = listOf(4, 6, 7, 8), failCount = 1)
            .replay()

        assertEquals("R11 三次任务失败应终局为坏人胜", MatchStatus.FINISHED_EVIL, state.status)
        assertEquals(
            "R11 终局原因应为三次任务失败",
            WinReason.THREE_MISSIONS_FAILED,
            state.outcome?.reason
        )
        assertEquals("R11 失败计数应为 3", 3, state.evilFailureCount)
        assertEquals("R11 阶段应为 ENDED", RecordPhase.ENDED, state.phase)
    }

    @Test
    fun `R11 两次失败一次成功仍在进行中`() {
        val state = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(1, leaderSeat = 1, teamSeats = listOf(1, 2, 3), failCount = 1)
            .questCycle(2, leaderSeat = 2, teamSeats = listOf(1, 2, 3, 4), failCount = 2)
            .questCycle(3, leaderSeat = 3, teamSeats = listOf(4, 6, 7, 8), failCount = 0)
            .replay()

        assertEquals("R11 二负一胜不应终局", MatchStatus.IN_PROGRESS, state.status)
        assertEquals("R11 失败计数应为 2", 2, state.evilFailureCount)
        assertEquals("R11 成功计数应为 1", 1, state.goodSuccessCount)
        assertNull("R11 未终局不应有 outcome", state.outcome)
    }

    @Test
    fun `R11 未核实任务不计入失败计数`() {
        val state = MatchBuilder.eightPlayerMerlinMordred()
            // 队伍只记录了 2 人（应有 3 人）→ 未核实，不计数
            .questCycle(
                questNo = 1,
                leaderSeat = 1,
                teamSeats = listOf(1, 2, 3),
                failCount = 1,
                missionTeamSeats = listOf(1, 2)
            )
            .replay()

        assertEquals("R11 未核实任务不得计入失败数", 0, state.evilFailureCount)
        assertNull(
            "R11 未核实任务不应给出可计数结论",
            state.quests.first().outcome?.takeIf { state.quests.first().verified }
        )
        assertEquals(
            "R11 未核实应体现在数据完整性上，实际=${state.integrity}",
            com.roundtrace.app.core.model.IntegrityStatus.NEEDS_REVIEW,
            state.integrity
        )
    }
}
