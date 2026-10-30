package com.roundtrace.app.export

import com.roundtrace.app.core.data.MatchBundle
import com.roundtrace.app.core.model.BeliefType
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.MatchSummary
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.util.MatchBuilder

/**
 * 导出相关单测的公共夹具（TASKS.md T64）。
 *
 * 复现 TDD §11 的真实案例并叠加 AC-02 场景：
 * 第 2 任务先有一次 3:5 被否决的提案，再一次 7:1 通过，随后出 1 张失败牌。
 * 「7 票赞成 / 1 票反对」与「1 张失败牌」必须能被分别导出、分别断言。
 */
object ExportFixtures {

    /** 固定导出时刻，保证 Markdown 输出确定。 */
    const val EXPORTED_AT: Long = 1_700_000_900_000L

    /** 身份揭晓时刻。 */
    const val REVEALED_AT: Long = 1_700_000_800_000L

    /** 私人笔记正文（用于断言默认脱敏后不出现）。 */
    const val PRIVATE_NOTE_TEXT: String = "我判断 3 号像莫甘娜"

    /** 两轮任务 + 一次否决 + 一次通过 + 1 张失败牌 + 一条声称 + 一条私人笔记。 */
    fun builder(): MatchBuilder = MatchBuilder.eightPlayerMerlinMordred()
        // 第 1 任务：3 人全票通过，0 张失败牌 → 成功
        .questCycle(
            questNo = 1,
            leaderSeat = 1,
            teamSeats = listOf(1, 2, 3),
            failCount = 0,
            observedOutcome = MissionOutcome.SUCCESS
        )
        // 第 2 任务第 1 次提案：3 赞成 / 5 反对 → 否决
        .questStarted(2, 2)
        .teamProposed(2, 1, 2, listOf(2, 3, 4, 6))
        .teamVoteRecorded(2, 1, approveCount = 3, rejectCount = 5)
        .teamProposalResolved(2, 1, ProposalDecision.REJECTED, approveCount = 3, rejectCount = 5)
        // 第 2 任务第 2 次提案：7 赞成 / 1 反对 → 通过
        .teamProposed(2, 2, 3, listOf(2, 3, 4, 6))
        .teamVoteRecorded(2, 2, approveCount = 7, rejectCount = 1)
        .teamProposalResolved(2, 2, ProposalDecision.APPROVED, approveCount = 7, rejectCount = 1)
        // 1 张失败牌 → 第 2 任务失败
        .missionResolved(2, listOf(2, 3, 4, 6), 1, MissionOutcome.FAILED)
        .playerClaimed(
            speakerSeat = 7,
            claimType = ClaimType.ROLE_CLAIM,
            questNo = 2,
            claimedRole = RoleId.PERCIVAL
        )
        .privateNoteAdded(
            observerSeat = 1,
            beliefType = BeliefType.SUSPECT,
            targetSeats = listOf(3),
            questNo = 2,
            text = PRIVATE_NOTE_TEXT
        )

    /** 完整快照（[revealed] 为 true 时角色全部 `REVEALED`）。 */
    fun bundle(revealed: Boolean = false): MatchBundle {
        val match = builder().build(revealedAtEpochMs = if (revealed) REVEALED_AT else null)
        return MatchBundle(
            summary = MatchSummary(
                matchId = match.matchId,
                title = match.config.title,
                playerCount = match.config.playerCount,
                status = if (revealed) MatchStatus.FINISHED_GOOD else MatchStatus.IN_PROGRESS,
                rulesetId = match.config.rulesetId,
                updatedAtEpochMs = 1_700_000_700_000L,
                questsStarted = 2,
                mySeat = match.config.mySeat
            ),
            config = match.config,
            participants = match.participants,
            roleAssignments = match.roleAssignments,
            events = match.events,
            revealedAt = match.revealedAt
        )
    }

    /** 已揭晓的完整快照。 */
    fun revealedBundle(): MatchBundle = bundle(revealed = true)
}
