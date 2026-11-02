package com.roundtrace.app.core.knowledge

import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.rules.avalon.AvalonRules

/**
 * 单个任务结果（MissionResolved）的「证据前提」（NX-01 阶段 A）。
 *
 * 分析器在 MissionResolved 循环开头对每个任务**求值一次**，
 * 之后 A03/A04/A06/A07/A12 全部依据这份前提决定是否触发、用哪段文案。
 *
 * 解决的四类现状缺陷（见 docs/audit/next-iteration-progress.md 阶段 A 研究结论）：
 * 1. A04 文案越权：`failCount` 未知时硬说「失败牌为 0」（门槛 N 的任务可以有 N-1 张失败牌仍成功）；
 * 2. A12-fail 文案与已记录结果矛盾：结果已知时说「无法判断成败」；
 * 3. A12-team 文案违反 B34：队员不完整说「不参与好人/坏人计数」
 *    （B34：结果明确即计数，verified 仅表示可复核性）；
 * 4. 无冲突/畸形判定：failCount 与 observedOutcome 矛盾时 A06/A04 给出互相矛盾的结论，
 *    座位越界/重复时依赖名单的推论未被抑制。
 *
 * 判定口径与 `AvalonReducer.onMissionResolved` 对齐（同一事实只有一种读法）：
 * 失败牌数在 0..队伍规模内时可推导结果；推导结果与观察结果不一致即冲突；
 * 队伍规模按去重后的座位数与期望人数比较。
 */
data class MissionEvidencePremise(
    val questNo: Int,
    /** 用户记录的观察结果（成功/失败）；null = 未记录。 */
    val observedOutcome: MissionOutcome?,
    /** 任务结果是否明确：记录了观察结果，或可由失败牌数推导。 */
    val outcomeKnown: Boolean,
    /** 失败牌数是否已记录。 */
    val failCountKnown: Boolean,
    /** 已记录的失败牌数；未记录为 null。 */
    val failCount: Int?,
    /** 该任务的失败牌门槛（规则矩阵：7-10 人局第 4 任务为 2，其余为 1）。 */
    val threshold: Int,
    /** 队伍成员是否已记录（teamSeats 非 null）。 */
    val teamRecorded: Boolean,
    /** 记录的队伍人数（去重后）是否与规则矩阵的期望人数一致；未记录为 false。 */
    val teamSizeMatchesExpected: Boolean,
    /** 规则矩阵给出的期望队伍人数；人数/任务号非法时为 -1。 */
    val expectedTeamSize: Int,
    /** 座位名单是否畸形（越界或重复）。 */
    val seatsMalformed: Boolean,
    /** 失败牌数与记录结果是否冲突（两者都有且推导结果 ≠ 观察结果）。 */
    val outcomeFailConflict: Boolean,
    /** 由失败牌数推导的任务结果；牌数未知/超界时为 null。 */
    val derivedOutcome: MissionOutcome?
)

/**
 * 对一个任务结果事件求值证据前提。
 *
 * 纯函数：不读状态、不改状态；`playerCount <= 0` 时按「无从判定」处理。
 */
fun missionEvidencePremise(
    mission: MissionResolved,
    playerCount: Int,
    rules: AvalonRules
): MissionEvidencePremise {
    val failCount = mission.failCount
    val failCountKnown = failCount != null
    val threshold = rules.failThreshold(playerCount, mission.questNo)
    val expectedTeamSize = rules.expectedTeamSizeFor(playerCount, mission.questNo)

    val teamSeats = mission.teamSeats
    val teamRecorded = teamSeats != null
    val seatsMalformed = teamRecorded && (
        teamSeats!!.any { it !in 1..playerCount } ||
            teamSeats.distinct().size != teamSeats.size
        )
    val teamSizeMatchesExpected = teamRecorded && expectedTeamSize > 0 &&
        teamSeats!!.distinct().size == expectedTeamSize

    // 与 reducer 相同的推导口径：牌数在 0..队伍规模（去重）内才可推导
    val teamSizeForCheck = teamSeats?.distinct()?.size ?: mission.teamSizeExpected
    val derivedOutcome: MissionOutcome? =
        if (failCount != null && failCount in 0..teamSizeForCheck) {
            if (failCount >= threshold) MissionOutcome.FAILED else MissionOutcome.SUCCESS
        } else {
            null
        }

    val observedOutcome = mission.observedOutcome
    val outcomeFailConflict = derivedOutcome != null && observedOutcome != null &&
        derivedOutcome != observedOutcome
    val outcomeKnown = observedOutcome != null || derivedOutcome != null

    return MissionEvidencePremise(
        questNo = mission.questNo,
        observedOutcome = observedOutcome,
        outcomeKnown = outcomeKnown,
        failCountKnown = failCountKnown,
        failCount = failCount,
        threshold = threshold,
        teamRecorded = teamRecorded,
        teamSizeMatchesExpected = teamSizeMatchesExpected,
        expectedTeamSize = expectedTeamSize,
        seatsMalformed = seatsMalformed,
        outcomeFailConflict = outcomeFailConflict,
        derivedOutcome = derivedOutcome
    )
}
