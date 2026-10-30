package com.roundtrace.app.core.model

/**
 * 开局配置 = **计划**，不等于真实身份（ARCHITECTURE §2.2 / §9.3-3）。
 *
 * - [roleSlots] 是「开局选择的计划角色构成」，必填、可校验总数；
 * - 真实身份在 `role_assignments`（[RoleAssignment]），可为 UNKNOWN、可后补；
 * - 两者不一致时只给 `ALIGNMENT_COUNT_MISMATCH` 警告，不阻断录入。
 */
data class GameConfig(
    val gameType: GameType,
    /** 例：`avalon.standard` */
    val rulesetId: String,
    /** 例：1；规则升级不得改变历史结算 */
    val rulesetVersion: Int,
    /** 例：`avalon.standard.8p` / `custom` */
    val presetId: String,
    /** 5..10 */
    val playerCount: Int,
    /** 计划角色构成（可自定义变体） */
    val roleSlots: List<RoleSlotSpec>,
    /** 记录者自己的座位，可为空 */
    val mySeat: SeatId?,
    /** 首任队长 */
    val initialLeaderSeat: SeatId,
    /** 对局标题（默认如「10月30日 8人局」） */
    val title: String,
    val createdAtEpochMs: Long
) {

    /** 计划中某角色的数量；不存在返回 0（不是 null：0 = 已知为零）。 */
    fun plannedCount(role: RoleId): Int =
        roleSlots.firstOrNull { it.roleId == role }?.count ?: 0

    /** 计划中某阵营的总人数，由 [roleSlots] 汇总。`UNKNOWN` 阵营不参与统计。 */
    fun plannedAlignmentCount(alignment: Alignment): Int {
        if (alignment == Alignment.UNKNOWN) return 0
        return roleSlots.sumOf { slot ->
            if (slot.roleId.alignment == alignment) slot.count else 0
        }
    }

    /** 计划中是否包含某角色（数量 ≥ 1）。 */
    fun hasRole(role: RoleId): Boolean = plannedCount(role) > 0

    /** 计划角色总数（用于校验是否等于 [playerCount]）。 */
    val plannedTotalCount: Int
        get() = roleSlots.sumOf { it.count }
}

/** 计划角色构成的一项：角色 + 数量。 */
data class RoleSlotSpec(
    val roleId: RoleId,
    val count: Int
)
