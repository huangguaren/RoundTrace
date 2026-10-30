package com.roundtrace.app.core.rules

import com.roundtrace.app.core.model.GameState
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.ReconstructedMatch
import com.roundtrace.app.core.model.RuleIssue

/**
 * 重放/推进接口（ARCHITECTURE §3）。
 *
 * 与 [GameRules] 的分工：`GameRules` 定义「规则是什么」，`GameReducer` 定义「如何喂事件」。
 * `step` 内部先 `validateEvent`，再 `reduce`；返回**本次新增**的 issue，绝不抛异常。
 */
interface GameReducer {

    /** 构造初始状态 */
    fun initialState(match: ReconstructedMatch): GameState

    /** 单个事件推进：内部先 validateEvent，再 reduce；返回累积 issue，绝不抛异常。 */
    fun step(state: GameState, event: MatchEvent): ReductionResult

    /** 全量/部分重放；`upToOccurredOrder == null` 表示到最新。 */
    fun replay(match: ReconstructedMatch, upToOccurredOrder: Int? = null): ReductionResult
}

/** 推进结果：新状态 + 本次产生的问题。 */
data class ReductionResult(
    val state: GameState,
    val issues: List<RuleIssue>
)
