package com.roundtrace.app.feature.claim

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundtrace.app.core.data.MatchRepository
import com.roundtrace.app.core.model.BeliefType
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EventFactory
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.ui.component.SaveStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 发言/笔记录入 ViewModel（TASKS.md T55）。
 *
 * 硬约束：
 * 1. `PLAYER_CLAIMED` **只写事件，永不修改 `role_assignments`**（K08 / AC-05）——
 *    声称必须在数据层就与真实身份分离；
 * 2. 私人笔记写 `PRIVATE_NOTE_ADDED`，`availableFromOrder` 允许设为更早的位置
 *    （回填历史），但**不改变任何公开事实**。
 */
class ClaimViewModel(
    private val matchId: MatchId,
    private val repository: MatchRepository,
    private val eventFactory: EventFactory
) : ViewModel() {

    private val _state = MutableStateFlow(ClaimUiState())
    val state: StateFlow<ClaimUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val bundle = repository.getBundle(matchId)
            _state.value = if (bundle == null) {
                ClaimUiState(isLoading = false, errorMessage = "对局不存在")
            } else {
                ClaimUiState(
                    isLoading = false,
                    matchTitle = bundle.summary.title,
                    playerCount = bundle.config.playerCount,
                    questNo = bundle.summary.questsStarted.takeIf { it > 0 },
                    speakerSeat = bundle.config.mySeat
                )
            }
        }
    }

    fun onAction(action: ClaimAction) {
        val current = _state.value
        when (action) {
            is ClaimAction.SelectTag -> _state.value = current.copy(
                selectedTag = action.tag,
                // 从「声称身份」切到普通标签时清掉角色，避免残留脏字段
                claimedRole = current.claimedRole.takeIf {
                    action.tag.isClaimType()
                }
            )

            is ClaimAction.SelectSpeaker -> _state.value = current.copy(speakerSeat = action.seat)

            is ClaimAction.ToggleTarget -> _state.value = current.copy(
                targetSeats = if (current.targetSeats.contains(action.seat)) {
                    current.targetSeats - action.seat
                } else {
                    current.targetSeats + action.seat
                }
            )

            is ClaimAction.SelectClaimedRole -> _state.value = current.copy(claimedRole = action.role)

            is ClaimAction.UpdateText -> _state.value = current.copy(text = action.text)

            is ClaimAction.SetPrivateMode -> _state.value = current.copy(privateMode = action.enabled)

            is ClaimAction.SetBeliefType -> _state.value = current.copy(beliefType = action.type)

            ClaimAction.Save -> save()
        }
    }

    private fun save() {
        val current = _state.value
        if (!current.canSave) return

        val speaker = current.speakerSeat ?: return
        val tag = current.selectedTag ?: return

        val event = if (current.privateMode) {
            eventFactory.privateNoteAdded(
                observerSeat = speaker,
                beliefType = current.beliefType,
                // 回填历史：允许指定更早的可见位置；默认从现在开始可见
                availableFromOrder = 1,
                questNo = current.questNo,
                targetSeats = current.targetSeats.sorted(),
                text = current.text.takeIf { it.isNotBlank() }
            )
        } else {
            eventFactory.playerClaimed(
                speakerSeat = speaker,
                claimType = tag,
                questNo = current.questNo,
                claimedRole = current.claimedRole.takeIf { tag.isClaimType() },
                targetSeats = current.targetSeats.sorted(),
                text = current.text.takeIf { it.isNotBlank() }
            )
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(saveStatus = SaveStatus.SAVING, errorMessage = null)
            val result = runCatching { repository.appendEvent(matchId, event) }
            val success = result.isSuccess && result.getOrNull()?.isSuccess == true
            _state.value = _state.value.copy(
                saveStatus = if (success) SaveStatus.SAVED else SaveStatus.FAILED,
                lastSavedClaimId = if (success) event.eventId.raw else null,
                errorMessage = if (success) null else "保存失败",
                // 保存成功后清空表单，但保留发言者（现场连续记录同一人的话更快）
                selectedTag = if (success) null else _state.value.selectedTag,
                targetSeats = if (success) emptySet() else _state.value.targetSeats,
                claimedRole = if (success) null else _state.value.claimedRole,
                text = if (success) "" else _state.value.text
            )
        }
    }

    private fun ClaimType.isClaimType(): Boolean =
        this == ClaimType.ROLE_CLAIM || this == ClaimType.COUNTER_CLAIM
}
