package com.roundtrace.app.feature.claim

import com.roundtrace.app.core.model.BeliefType
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.ui.component.SaveStatus

/**
 * 发言/笔记录入 UiState（TASKS.md T55）。
 *
 * 主路径只需三步：选标签 → 选发言者 →（可选）选目标，**不需要键盘**。
 * `privateMode = true` 时写 `PRIVATE_NOTE_ADDED`（仅本人可见），
 * 否则写 `PLAYER_CLAIMED`（公开且**永不修改真实身份**）。
 */
data class ClaimUiState(
    val isLoading: Boolean = true,
    val matchTitle: String = "",
    val playerCount: Int = 0,
    /** 当前任务语境（可为空 = 不属于任何任务） */
    val questNo: Int? = null,
    val selectedTag: ClaimType? = null,
    val speakerSeat: Int? = null,
    val targetSeats: Set<Int> = emptySet(),
    /** 仅 ROLE_CLAIM / COUNTER_CLAIM 使用 */
    val claimedRole: RoleId? = null,
    val text: String = "",
    val privateMode: Boolean = false,
    val beliefType: BeliefType = BeliefType.SUSPECT,
    val saveStatus: SaveStatus = SaveStatus.SAVED,
    val lastSavedClaimId: String? = null,
    val errorMessage: String? = null
) {

    /** 断言：必须选了标签和发言者才能保存（T55②：主路径不留空）。 */
    val canSave: Boolean
        get() = selectedTag != null &&
            speakerSeat != null &&
            saveStatus != SaveStatus.SAVING &&
            (if (selectedTag?.let { it.name == "ROLE_CLAIM" || it.name == "COUNTER_CLAIM" } == true) {
                claimedRole != null
            } else {
                true
            })
}

/** 发言录入动作。 */
sealed interface ClaimAction {

    data class SelectTag(val tag: ClaimType) : ClaimAction

    data class SelectSpeaker(val seat: Int) : ClaimAction

    data class ToggleTarget(val seat: Int) : ClaimAction

    data class SelectClaimedRole(val role: RoleId?) : ClaimAction

    data class UpdateText(val text: String) : ClaimAction

    data class SetPrivateMode(val enabled: Boolean) : ClaimAction

    data class SetBeliefType(val type: BeliefType) : ClaimAction

    data object Save : ClaimAction
}
