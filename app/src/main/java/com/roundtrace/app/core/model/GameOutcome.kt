package com.roundtrace.app.core.model

/**
 * 终局结果（ARCHITECTURE §2.4）。
 *
 * @param winner **只允许 GOOD / EVIL**（TASKS.md T15④：用 require 拒绝 UNKNOWN）
 * @param decidedAtOrder 产生该结果的事件 occurredOrder
 * @param evidenceEventIds 支撑该结果的事件 id（审计用）
 */
data class GameOutcome(
    val winner: Alignment,
    val reason: WinReason,
    val decidedAtOrder: Int,
    val evidenceEventIds: List<EventId> = emptyList()
) {

    init {
        require(winner == Alignment.GOOD || winner == Alignment.EVIL) {
            "GameOutcome.winner 只允许 GOOD 或 EVIL，实际为 $winner"
        }
    }
}
