package com.roundtrace.app.core.replay

import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.ReconstructedMatch
import com.roundtrace.app.core.model.RuleIssue
import com.roundtrace.app.core.rules.GameReducer
import com.roundtrace.app.core.rules.ReductionResult

/**
 * 重放引擎（TASKS.md T21 / ARCHITECTURE §8.6）。
 *
 * 职责（且仅此）：过滤 `retracted` → 按 `occurredOrder` 升序 → 逐步 `step` → 汇总 issue。
 * 它**不含任何阿瓦隆规则**：规则全部在 [GameReducer] 的实现里。
 *
 * 保证：
 * - `upTo == null` 表示重放到最新；
 * - `issues` 去重（同一 `code + eventId` 只保留一条，保留首次出现顺序）；
 * - **幂等**：同一输入两次 `replay` 结果 `==`（不依赖任何外部状态、不读时钟）。
 */
class ReplayEngine(private val reducer: GameReducer) {

    /**
     * 重放。
     *
     * @param match 重放输入（`events` 可含 `retracted`，本方法会过滤）
     * @param upTo 只重放 `occurredOrder <= upTo` 的事件；null = 全部
     */
    fun replay(match: ReconstructedMatch, upTo: Int? = null): ReductionResult {
        val activeEvents: List<MatchEvent> = match.events
            .asSequence()
            .filter { !it.retracted }
            .filter { upTo == null || it.occurredOrder <= upTo }
            .sortedWith(compareBy({ it.occurredOrder }, { it.recordedAt }, { it.eventId.raw }))
            .toList()

        var state = reducer.initialState(match)
        val collected = LinkedHashMap<String, RuleIssue>()

        for (event in activeEvents) {
            val result = reducer.step(state, event)
            state = result.state
            for (issue in result.issues) {
                collected.putIfAbsent(dedupeKey(issue), issue)
            }
        }

        val dedupedIssues = collected.values.toList()
        // 状态里累积的 issue 也统一成去重后的视图，保证 state.issues 与返回值一致
        val finalState = state.copy(issues = dedupedIssues)
        return ReductionResult(state = finalState, issues = dedupedIssues)
    }

    /** 去重键：`code + eventId`（同一事件同一问题只报一次）。 */
    private fun dedupeKey(issue: RuleIssue): String =
        "${issue.code.name}|${issue.eventId?.raw ?: "-"}"
}
