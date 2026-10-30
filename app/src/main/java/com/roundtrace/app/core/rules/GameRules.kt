package com.roundtrace.app.core.rules

import com.roundtrace.app.core.model.GameConfig
import com.roundtrace.app.core.model.GameOutcome
import com.roundtrace.app.core.model.GameState
import com.roundtrace.app.core.model.GameType
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.ReconstructedMatch
import com.roundtrace.app.core.model.RuleIssue

/**
 * 规则集接口（ARCHITECTURE §3，签名**逐字一致**）。
 *
 * 设计约束（TASKS.md T16④）：接口**不提供任何 default 实现**——
 * 规则一旦在接口里写默认值，就会被各实现「顺手继承」，最终散落到 UI 里。
 *
 * 全部方法必须是**纯函数**：无 IO、无 Android 依赖、不修改入参。
 * 纯 Kotlin：禁止 import android.* / androidx.*。
 */
interface GameRules {

    val gameType: GameType
    val rulesetId: String
    val rulesetVersion: Int

    // —— 规则常量（阿瓦隆：人数矩阵 / 任务人数 / 失败牌门槛 / 半数判定）——

    /** 第 [questNo] 次任务应上车的人数。人数非法时抛 [IllegalArgumentException]。 */
    fun teamSize(playerCount: Int, questNo: Int): Int

    /** 第 [questNo] 次任务的失败牌门槛（阿瓦隆：7–10 人的第 4 任务为 2，其余为 1）。 */
    fun failThreshold(playerCount: Int, questNo: Int): Int

    /** 组队是否通过：`approveCount > playerCount / 2`（平票视为否决）。 */
    fun isTeamApproved(approveCount: Int, playerCount: Int): Boolean

    /** 任务是否失败：`failCount >= failThreshold(playerCount, questNo)`。 */
    fun isMissionFailed(failCount: Int, playerCount: Int, questNo: Int): Boolean

    // —— 校验 ——

    /** 开局配置校验。BLOCKING 项禁止创建对局。 */
    fun validateSetup(config: GameConfig): List<RuleIssue>

    /** 单事件校验（静态可判定部分）。BLOCKING 项表示该事件不应被「确认」，但仍可先存草稿。 */
    fun validateEvent(state: GameState, event: MatchEvent): List<RuleIssue>

    // —— 推进 ——

    /** 构造初始状态（无任何事件）。 */
    fun initialState(match: ReconstructedMatch): GameState

    /** 单个事件推进。**不得抛异常**：非法事件只累积 [RuleIssue]。 */
    fun reduce(state: GameState, event: MatchEvent): GameState

    // —— 结局 ——

    /**
     * 由状态推导终局。
     *
     * 约定：`status == AWAITING_ASSASSINATION` 时必须返回 `null`（还在等刺杀，不能提前判胜负）。
     */
    fun determineOutcome(state: GameState): GameOutcome?
}
