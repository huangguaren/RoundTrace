package com.roundtrace.app.core.model

/**
 * 座位 → 真实角色的分配（ARCHITECTURE §2.2）。
 *
 * **重要**：「真实身份」与「某玩家当时知道的身份」是两件事。
 * `roleId = UNKNOWN` 表示尚未填写，绝不用人数规则反推填补。
 *
 * @param source 角色信息来源
 * @param revealState 揭晓状态（决定谁能看到）
 * @param assignedFrom 来源事件 id / 备注，可空
 */
data class RoleAssignment(
    val seat: SeatId,
    val roleId: RoleId,
    val source: RoleSource,
    val revealState: RevealState,
    val assignedFrom: String? = null
) {
    /** 阵营由 [RoleId.alignment] 委托，本类不重复判断（TASKS.md T12②）。 */
    val alignment: Alignment
        get() = roleId.alignment

    /** 角色是否仍然未知。 */
    val isUnknownRole: Boolean
        get() = roleId == RoleId.UNKNOWN
}

/** 角色信息来源。 */
enum class RoleSource {
    /** 创建对局时用户手动分配 */
    PRESET_MANUAL,

    /** 用户填写「我自己」的身份 */
    SELF_DECLARED,

    /** 结束后揭晓补录 */
    REVEALED_AFTER_END,

    /** JSON 导入 */
    IMPORTED
}

/** 揭晓状态（三值，TASKS.md T12③）。 */
enum class RevealState {
    /** 所有人都不知道 */
    HIDDEN,

    /** 仅本人知道（记录者的 mySeat） */
    KNOWN_TO_SELF,

    /** 已全局揭晓（上帝视角可用） */
    REVEALED
}
