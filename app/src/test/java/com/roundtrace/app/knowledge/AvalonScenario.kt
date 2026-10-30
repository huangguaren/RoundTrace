package com.roundtrace.app.knowledge

import com.roundtrace.app.core.model.GameConfig
import com.roundtrace.app.core.model.GamePosition
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.ReconstructedMatch
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.util.MatchBuilder
import com.roundtrace.app.util.RoleFixtures

/**
 * 视角/证据测试共用的真实案例场景（TDD §11）。
 *
 * 角色：1 梅林 / 2 忠臣 / 3 莫甘娜 / 4 莫德雷德 / 5 刺客 / 6 忠臣 / 7 派西维尔 / 8 忠臣。
 *
 * 任务链（与 TDD §11.2 对齐，但把「投票未记录」实现为
 * 「记录为通过但票数未知」，以便 reducer 能推进到任务阶段；
 * 票数未知本身仍会触发 `VOTE_DECISION_UNDECIDABLE`，保留真实感）：
 *
 * | 任务 | 队长 | 队伍 | 结果 |
 * |---|---|---|---|
 * | 1 | 2 | 2,4,6 | 成功（0 张失败牌） |
 * | 2 | 3 | 2,3,4,6 | 失败（1 张失败牌） |
 * | 3 | 4 | 4,6,7,8 | 成功（0 张失败牌）；随后 7 号声称派西维尔 |
 * | 4 | 5 | 提案 1,3,4,5,8；实际只记得 1,3,4 | 失败（2 张失败牌，队员不完整） |
 * | 5 | 6 | 2,4,6,7,8 | 未记录失败牌 |
 */
object AvalonScenario {

    const val MERLIN = 1
    const val LOYAL_2 = 2
    const val MORGANA = 3
    const val MORDRED = 4
    const val ASSASSIN = 5
    const val LOYAL_6 = 6
    const val PERCIVAL = 7
    const val LOYAL_8 = 8

    fun eightPlayerConfig(): GameConfig = MatchCreationDraft(
        title = "TDD §11 真实案例",
        playerCount = 8,
        roleSlots = AvalonPreset.standardPreset(8),
        mySeat = MERLIN,
        initialLeaderSeat = 2,
        presetId = AvalonPreset.presetId(8),
        rulesetId = AvalonPreset.RULESET_ID,
        rulesetVersion = AvalonPreset.RULESET_VERSION
    ).toGameConfig(createdAtEpochMs = 0L)

    /** 完整五任务场景（含 7 号声称派西维尔）。 */
    fun fullMatch(revealed: Boolean = false): MatchBuilder {
        val builder = MatchBuilder.eightPlayerMerlinMordred()

        // —— 任务 1：队长 2，队伍 2/4/6，全员同意，成功 ——
        builder
            .questStarted(1, LOYAL_2)
            .teamProposed(1, 1, LOYAL_2, listOf(LOYAL_2, MORDRED, LOYAL_6))
            .teamVoteRecorded(1, 1, approveCount = 8, rejectCount = 0)
            .teamProposalResolved(1, 1, ProposalDecision.APPROVED, approveCount = 8, rejectCount = 0)
            .missionResolved(
                questNo = 1,
                teamSeats = listOf(LOYAL_2, MORDRED, LOYAL_6),
                failCount = 0,
                observedOutcome = MissionOutcome.SUCCESS
            )

        // —— 任务 2：队长 3，队伍 2/3/4/6，7 赞成 1 反对，1 张失败牌 ——
        builder
            .questStarted(2, MORGANA)
            .teamProposed(2, 1, MORGANA, listOf(LOYAL_2, MORGANA, MORDRED, LOYAL_6))
            .teamVoteRecorded(2, 1, approveCount = 7, rejectCount = 1)
            .teamProposalResolved(2, 1, ProposalDecision.APPROVED, approveCount = 7, rejectCount = 1)
            .missionResolved(
                questNo = 2,
                teamSeats = listOf(LOYAL_2, MORGANA, MORDRED, LOYAL_6),
                failCount = 1,
                observedOutcome = MissionOutcome.FAILED
            )

        // —— 任务 3：队长 4，队伍 4/6/7/8，票数未记录，成功 ——
        builder
            .questStarted(3, MORDRED)
            .teamProposed(3, 1, MORDRED, listOf(MORDRED, LOYAL_6, PERCIVAL, LOYAL_8))
            .teamVoteRecorded(3, 1, approveCount = null, rejectCount = null)
            .teamProposalResolved(3, 1, ProposalDecision.APPROVED)
            .missionResolved(
                questNo = 3,
                teamSeats = listOf(MORDRED, LOYAL_6, PERCIVAL, LOYAL_8),
                failCount = 0,
                observedOutcome = MissionOutcome.SUCCESS
            )

        // —— 7 号公开声称派西维尔（Claim，不是事实）——
        builder.playerClaimed(
            speakerSeat = PERCIVAL,
            claimType = com.roundtrace.app.core.model.ClaimType.ROLE_CLAIM,
            questNo = 3,
            claimedRole = RoleId.PERCIVAL,
            targetSeats = listOf(MERLIN, MORGANA)
        )

        // —— 任务 4：队长 5，提案 1/3/4/5/8，实际只记得 1/3/4，2 张失败牌 ——
        builder
            .questStarted(4, ASSASSIN)
            .teamProposed(4, 1, ASSASSIN, listOf(MERLIN, MORGANA, MORDRED, ASSASSIN, LOYAL_8))
            .teamVoteRecorded(4, 1, approveCount = null, rejectCount = null)
            .teamProposalResolved(4, 1, ProposalDecision.APPROVED)
            .missionResolved(
                questNo = 4,
                teamSeats = listOf(MERLIN, MORGANA, MORDRED),
                failCount = 2,
                observedOutcome = MissionOutcome.FAILED
            )

        // —— 任务 5：队长 6，队伍 2/4/6/7/8，失败牌数未记录 ——
        builder
            .questStarted(5, LOYAL_6)
            .teamProposed(5, 1, LOYAL_6, listOf(LOYAL_2, MORDRED, LOYAL_6, PERCIVAL, LOYAL_8))
            .teamVoteRecorded(5, 1, approveCount = null, rejectCount = 2)
            .teamProposalResolved(5, 1, ProposalDecision.APPROVED)
            .missionResolved(
                questNo = 5,
                teamSeats = listOf(LOYAL_2, MORDRED, LOYAL_6, PERCIVAL, LOYAL_8),
                failCount = null,
                observedOutcome = null
            )

        return builder
    }

    /** 构造重放输入。 */
    fun match(revealed: Boolean = false): ReconstructedMatch =
        fullMatch(revealed).build(
            revealedAtEpochMs = if (revealed) 1_700_000_100_000L else null
        )

    /** 角色真值表（供断言）。 */
    fun roleTruth(): Map<Int, RoleId> = RoleFixtures.EIGHT_PLAYER_MERLIN_MORDRED

    /** 规则注册表里的 reducer（视角引擎需要它推导 status）。 */
    fun reducer() = DefaultGameRulesRegistry().avalonReducer

    /** 位置：到最新。 */
    fun end(match: ReconstructedMatch): GamePosition =
        GamePosition(match.events.maxOfOrNull { it.occurredOrder } ?: 0)

    /** 位置：某个任务结束后（该任务最后一个事件的 order）。 */
    fun afterQuest(match: ReconstructedMatch, questNo: Int): GamePosition {
        val orders = match.events
            .filter { it.questNo == questNo }
            .map { it.occurredOrder }
        val last = orders.maxOrNull() ?: 0
        return GamePosition(last)
    }
}
