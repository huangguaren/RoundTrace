package com.roundtrace.app.core.model

/**
 * 创建对局的输入草稿（覆盖 PRD FR-02 的全部输入，TASKS.md T13③）。
 *
 * 与 [GameConfig] 的区别：草稿是「用户正在填的表单」，允许暂时不合法（例如人数与角色总数不符），
 * 只有通过 `GameRules.validateSetup` 且无 BLOCKING 问题时才允许落库。
 */
data class MatchCreationDraft(
    /** 对局标题；空字符串时由调用方补默认标题 */
    val title: String,
    /** 5..10 */
    val playerCount: Int,
    /** 计划角色构成 */
    val roleSlots: List<RoleSlotSpec>,
    /** 记录者自己的座位 */
    val mySeat: SeatId?,
    /** 首任队长 */
    val initialLeaderSeat: SeatId,
    /** 座位参与人（未命名也应有座位条目） */
    val participants: List<Participant> = emptyList(),
    /** 预设标识，默认 `custom`；使用标准预设时填 `avalon.standard.Np` */
    val presetId: String = PRESET_CUSTOM,
    /** 规则集 id */
    val rulesetId: String = DEFAULT_RULESET_ID,
    /** 规则集版本 */
    val rulesetVersion: Int = DEFAULT_RULESET_VERSION,
    val gameType: GameType = GameType.AVALON
) {

    /** 角色总数（用于 UI 实时显示「好人 X / 坏人 Y / 共 Z」）。 */
    val roleTotal: Int
        get() = roleSlots.sumOf { it.count }

    /** 计划好人数量。 */
    val goodCount: Int
        get() = roleSlots.sumOf { if (it.roleId.alignment == Alignment.GOOD) it.count else 0 }

    /** 计划坏人数量。 */
    val evilCount: Int
        get() = roleSlots.sumOf { if (it.roleId.alignment == Alignment.EVIL) it.count else 0 }

    /**
     * 转换成不可变的 [GameConfig]。
     *
     * @param createdAtEpochMs 创建时刻（由调用方从 `Clock` 取，便于测试）
     */
    fun toGameConfig(createdAtEpochMs: Long): GameConfig = GameConfig(
        gameType = gameType,
        rulesetId = rulesetId,
        rulesetVersion = rulesetVersion,
        presetId = presetId,
        playerCount = playerCount,
        roleSlots = roleSlots,
        mySeat = mySeat,
        initialLeaderSeat = initialLeaderSeat,
        title = title,
        createdAtEpochMs = createdAtEpochMs
    )

    companion object {
        /** 标准阿瓦隆规则集 id —— 与 `AvalonPreset.RULESET_ID` 保持一致（此处不引用 rules 包以免反向依赖）。 */
        const val DEFAULT_RULESET_ID: String = "avalon.standard"

        /** 标准阿瓦隆规则集版本。 */
        const val DEFAULT_RULESET_VERSION: Int = 1

        /** 自定义变体的预设标识。 */
        const val PRESET_CUSTOM: String = "custom"

        /** 按座位数生成全部未命名的参与人。 */
        fun defaultParticipants(playerCount: Int): List<Participant> =
            (1..playerCount).map { Participant(seat = it) }
    }
}
