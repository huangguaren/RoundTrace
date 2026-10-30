package com.roundtrace.app.feature.create

import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.RoleSlotSpec
import com.roundtrace.app.core.model.RuleIssue

/**
 * 创建对局的分步表单（TASKS.md T38 / T40①）。
 *
 * 五个步骤严格按 PRD FR-02 的顺序：人数 → 角色构成 → 我的座位 → 首任队长 → 标题。
 */
enum class CreateStep {
    PLAYER_COUNT,
    ROLES,
    SEAT_LAYOUT,
    MY_SEAT,
    LEADER,
    TITLE;

    /** 步骤序号（0-based），用于进度显示。 */
    val index: Int
        get() = ordinal

    companion object {
        val total: Int = entries.size
    }
}

/**
 * 创建对局 UiState。
 *
 * [canCreate] 只在**无 BLOCKING issue** 时为 true（T38③）——
 * 阵营警告（`ALIGNMENT_COUNT_MISMATCH`）不会挡住创建，因为现场可能先按计划记录、事后揭晓。
 */
data class CreateMatchUiState(
    val step: CreateStep = CreateStep.PLAYER_COUNT,
    val title: String = "",
    val playerCount: Int = DEFAULT_PLAYER_COUNT,
    val roleSlots: List<RoleSlotSpec> = emptyList(),
    val mySeat: Int? = null,
    val initialLeaderSeat: Int = 1,
    /** 座位名：seat -> 名字（null/空 = 未命名）。 */
    val seatNames: Map<Int, String?> = emptyMap(),
    /** 座位拖拽位置：seat -> (x, y) 归一化 0..1；空 = 默认网格位置。 */
    val seatPositions: Map<Int, Pair<Float, Float>> = emptyMap(),
    /** 座位头像 URI：seat -> URI 字符串；空 = 无头像。 */
    val seatAvatars: Map<Int, String?> = emptyMap(),
    val issues: List<RuleIssue> = emptyList(),
    val isSaving: Boolean = false,
    val createdMatchId: MatchId? = null,
    val errorMessage: String? = null
) {

    /** 计划好人数量。 */
    val goodCount: Int
        get() = roleSlots.sumOf { if (it.roleId.alignment == Alignment.GOOD) it.count else 0 }

    /** 计划坏人数量。 */
    val evilCount: Int
        get() = roleSlots.sumOf { if (it.roleId.alignment == Alignment.EVIL) it.count else 0 }

    /** 计划角色总数。 */
    val roleTotal: Int
        get() = roleSlots.sumOf { it.count }

    /** 仅有提示级问题（用于展示黄色提示而不是红色阻断）。 */
    val warnings: List<RuleIssue>
        get() = issues.filter { !it.isBlocking }

    /** 阻断级问题。 */
    val blockingIssues: List<RuleIssue>
        get() = issues.filter { it.isBlocking }

    /** 是否允许创建（T38③）。 */
    val canCreate: Boolean
        get() = blockingIssues.isEmpty() && !isSaving && createdMatchId == null

    /** 是否可以进入下一步（角色总数必须与人数一致）。 */
    val canGoNext: Boolean
        get() = when (step) {
            CreateStep.PLAYER_COUNT -> true
            CreateStep.ROLES -> roleTotal == playerCount
            CreateStep.SEAT_LAYOUT -> true
            CreateStep.MY_SEAT -> true
            CreateStep.LEADER -> initialLeaderSeat in 1..playerCount
            CreateStep.TITLE -> canCreate
        }

    /** 某个角色的计划数量。 */
    fun countOf(role: RoleId): Int =
        roleSlots.firstOrNull { it.roleId == role }?.count ?: 0

    companion object {
        /** 与 `SettingsRepository.DEFAULT_PLAYER_COUNT` 保持一致。 */
        const val DEFAULT_PLAYER_COUNT: Int = 8
    }
}
