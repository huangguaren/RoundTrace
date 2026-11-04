package com.roundtrace.app.feature.reveal

import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.WinReason
import com.roundtrace.app.core.ui.component.SaveStatus
import com.roundtrace.app.core.ui.identity.SeatDisplayResolver
import com.roundtrace.app.core.ui.identity.SeatIdentity

/** 单个座位的揭晓行（不改动真实身份，只改 `revealState`）。 */
data class RevealSeatRow(
    val seat: Int,
    val roleId: RoleId
)

/**
 * 结局揭晓 UiState（TASKS.md T56）。
 *
 * @param rolesComplete 是否 1..playerCount 全部填了已知角色（否则高级结论区「信息不足」）
 * @param isAwaitingAssassination 三次任务成功后进入刺杀阶段 —— UI 必须明确提示
 *        「不能直接认定好人获胜」（T56④）
 */
data class RevealUiState(
    val isLoading: Boolean = true,
    val matchTitle: String = "",
    val playerCount: Int = 0,
    val rows: List<RevealSeatRow> = emptyList(),
    val status: MatchStatus = MatchStatus.DRAFT,
    val isAwaitingAssassination: Boolean = false,
    val revealedAt: Long? = null,
    val winner: Alignment? = null,
    val winReason: WinReason = WinReason.UNKNOWN,
    val assassinSeat: Int? = null,
    val targetSeat: Int? = null,
    val saveStatus: SaveStatus = SaveStatus.SAVED,
    val errorMessage: String? = null,
    /** 每种角色的最大数量（来自 config.roleSlots）；UNKNOWN 不限数量。 */
    val roleQuota: Map<RoleId, Int> = emptyMap(),
    /** 同一份 bundle 快照派生的座位身份（头像 / 昵称 / 座位号）。 */
    val seatIdentity: SeatIdentity = SeatDisplayResolver.empty(0),
    /** 真实身份与开局计划的差异描述；空串 = 一致或尚未录完。 */
    val planMismatchWarning: String = "",
    /** 用户已明确确认「按实际身份记录（与计划不一致）」。 */
    val planMismatchConfirmed: Boolean = false
) {

    /** 每种角色已分配的数量（不含 UNKNOWN）。 */
    fun usedCount(role: RoleId): Int =
        rows.count { it.roleId == role }

    /** 某角色是否已满额（按计划名额，不是 Set 唯一性）。 */
    fun isRoleFull(role: RoleId): Boolean {
        if (role == RoleId.UNKNOWN) return false
        val quota = roleQuota[role] ?: return false
        return quota > 0 && usedCount(role) >= quota
    }

    /** 是否需要用户先确认「计划与真实身份不一致」才能揭晓。 */
    val requiresPlanMismatchConfirmation: Boolean
        get() = planMismatchWarning.isNotEmpty() && !planMismatchConfirmed

    /** 是否所有座位都已录入角色。 */
    val rolesComplete: Boolean
        get() = rows.isNotEmpty() && rows.all { it.roleId != RoleId.UNKNOWN }

    /** 已揭晓过。 */
    val isRevealed: Boolean
        get() = revealedAt != null

    /** 可以点「揭晓全部身份」。 */
    val canReveal: Boolean
        get() = !isRevealed && saveStatus != SaveStatus.SAVING

    /** 可以记录刺杀（仅在待刺杀阶段）。 */
    val canRecordAssassination: Boolean
        get() = isAwaitingAssassination && assassinSeat != null && targetSeat != null
}

/** 揭晓页动作。 */
sealed interface RevealAction {

    data class SetSeatRole(val seat: Int, val role: RoleId) : RevealAction

    data class SetWinner(val winner: Alignment?) : RevealAction

    data class SetWinReason(val reason: WinReason) : RevealAction

    data class SetAssassinSeat(val seat: Int?) : RevealAction

    data class SetTargetSeat(val seat: Int?) : RevealAction

    /** 保存角色并执行「揭晓」（`revealState = REVEALED` + 写 `revealedAt`）。 */
    data object Reveal : RevealAction

    /** 用户确认「按实际身份记录（与开局计划不一致）」；只影响本次是否放行，不改计划/真相。 */
    data object ConfirmPlanMismatch : RevealAction

    /** 记录刺杀结果（写 `ASSASSINATION_RECORDED`）。 */
    data object SaveAssassination : RevealAction
}
