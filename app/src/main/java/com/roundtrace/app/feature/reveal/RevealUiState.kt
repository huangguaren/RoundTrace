package com.roundtrace.app.feature.reveal

import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.WinReason
import com.roundtrace.app.core.ui.component.SaveStatus

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
    val errorMessage: String? = null
) {

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

    /** 记录刺杀结果（写 `ASSASSINATION_RECORDED`）。 */
    data object SaveAssassination : RevealAction
}
