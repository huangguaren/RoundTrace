package com.roundtrace.app.core.model

/**
 * 重放的输入（ARCHITECTURE §2.5）。
 *
 * 领域层只依赖这个快照，不依赖 Room：`MatchBundle.toReconstructedMatch()` 负责映射。
 *
 * @param roleAssignments 真实身份（可能整体 UNKNOWN；可能部分 KNOWN_TO_SELF）
 * @param events **已过滤 retracted，按 occurredOrder 升序**
 * @param revealedAt 身份揭晓动作完成时刻；`null` = 尚未揭晓
 */
data class ReconstructedMatch(
    val matchId: MatchId,
    val config: GameConfig,
    val participants: List<Participant>,
    val roleAssignments: Map<SeatId, RoleAssignment>,
    val events: List<MatchEvent>,
    val revealedAt: Long?
) {

    /** 角色真值快照（座位 → 角色），供给规则层裁决刺杀命中，**不做任何推断**。 */
    fun roleTruth(): Map<SeatId, RoleId> =
        roleAssignments.mapValues { (_, assignment) -> assignment.roleId }

    /** 是否已揭晓（上帝视角的前置条件）。 */
    val isRevealed: Boolean
        get() = revealedAt != null
}

/**
 * 回放位置（ARCHITECTURE §2.5）。
 *
 * [occurredOrder] 是唯一权威：只应用 `occurredOrder <= occurredOrder` 的事件。
 * [questNo] / [proposalNo] 仅作展示提示，可由事件推导，不参与过滤判定。
 */
data class GamePosition(
    val occurredOrder: Int,
    val questNo: Int? = null,
    val proposalNo: Int? = null
) {

    /**
     * 该位置是否包含指定顺序的事件。
     *
     * 语义：`order <= occurredOrder`（TASKS.md T13①）。
     */
    fun includes(order: Int): Boolean = order <= occurredOrder

    companion object {

        /** 开局位置：order = 0，此时 `includes(0)` 为 true、`includes(1)` 为 false。 */
        fun start(): GamePosition = GamePosition(occurredOrder = 0)
    }
}
