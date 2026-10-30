package com.roundtrace.app.feature.reveal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundtrace.app.core.data.MatchBundle
import com.roundtrace.app.core.data.MatchRepository
import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.EventFactory
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.RevealState
import com.roundtrace.app.core.model.RoleAssignment
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.RoleSource
import com.roundtrace.app.core.model.WinReason
import com.roundtrace.app.core.replay.ReplayEngine
import com.roundtrace.app.core.rules.GameReducer
import com.roundtrace.app.core.ui.component.SaveStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 结局揭晓 ViewModel（TASKS.md T56）。
 *
 * 关键语义：
 * - 填角色只写 `role_assignments`（真实身份），**不改任何事件**；
 * - 「揭晓」= `revealState = REVEALED` + 写 `matches.revealedAt`（上帝视角的开关条件）；
 * - 三次任务成功后状态是 `AWAITING_ASSASSINATION`，**不能**直接判好人胜（T56④）；
 * - 刺杀用事件 `ASSASSINATION_RECORDED` 表达，胜负由 reducer 按规则裁决。
 */
class RevealViewModel(
    private val matchId: MatchId,
    private val repository: MatchRepository,
    private val reducer: GameReducer,
    private val eventFactory: EventFactory
) : ViewModel() {

    private val _state = MutableStateFlow(RevealUiState())
    val state: StateFlow<RevealUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            val bundle = repository.getBundle(matchId)
            _state.value = if (bundle == null) {
                RevealUiState(isLoading = false, errorMessage = "对局不存在")
            } else {
                toUiState(bundle, _state.value)
            }
        }
    }

    private fun toUiState(bundle: MatchBundle, current: RevealUiState): RevealUiState {
        val playerCount = bundle.config.playerCount
        val gameState = ReplayEngine(reducer).replay(bundle.toReconstructedMatch()).state

        val rows = (1..playerCount).map { seat ->
            RevealSeatRow(
                seat = seat,
                roleId = bundle.roleAssignments[seat]?.roleId
                    ?: current.rows.firstOrNull { it.seat == seat }?.roleId
                    ?: RoleId.UNKNOWN
            )
        }

        return current.copy(
            isLoading = false,
            matchTitle = bundle.summary.title,
            playerCount = playerCount,
            rows = rows,
            status = gameState.status,
            isAwaitingAssassination = gameState.status == MatchStatus.AWAITING_ASSASSINATION,
            revealedAt = bundle.revealedAt,
            winner = gameState.outcome?.winner ?: current.winner,
            winReason = gameState.outcome?.reason ?: current.winReason,
            errorMessage = null
        )
    }

    fun onAction(action: RevealAction) {
        when (action) {
            is RevealAction.SetSeatRole -> updateRow(action.seat, action.role)
            is RevealAction.SetWinner -> _state.value = _state.value.copy(winner = action.winner)
            is RevealAction.SetWinReason -> _state.value =
                _state.value.copy(winReason = action.reason)

            is RevealAction.SetAssassinSeat -> _state.value =
                _state.value.copy(assassinSeat = action.seat)

            is RevealAction.SetTargetSeat -> _state.value =
                _state.value.copy(targetSeat = action.seat)

            RevealAction.Reveal -> reveal()
            RevealAction.SaveAssassination -> saveAssassination()
        }
    }

    private fun updateRow(seat: Int, roleId: RoleId) {
        val current = _state.value
        _state.value = current.copy(
            rows = current.rows.map { if (it.seat == seat) it.copy(roleId = roleId) else it }
        )
    }

    /** 保存角色 → 揭晓。两步都在仓储事务里完成。 */
    private fun reveal() {
        val current = _state.value
        if (!current.canReveal) return
        val assignments = current.rows.map { row ->
            RoleAssignment(
                seat = row.seat,
                roleId = row.roleId,
                source = RoleSource.REVEALED_AFTER_END,
                revealState = RevealState.REVEALED
            )
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(saveStatus = SaveStatus.SAVING)
            val result = runCatching {
                repository.saveRoleAssignments(matchId, assignments)
                repository.revealAllRoles(matchId)
            }
            if (result.isFailure) {
                _state.value = _state.value.copy(
                    saveStatus = SaveStatus.FAILED,
                    errorMessage = result.exceptionOrNull()?.message ?: "揭晓失败"
                )
                return@launch
            }
            _state.value = _state.value.copy(saveStatus = SaveStatus.SAVED)
            refresh()
        }
    }

    private fun saveAssassination() {
        val current = _state.value
        val assassin = current.assassinSeat ?: return
        val target = current.targetSeat ?: return
        val event = eventFactory.assassinationRecorded(
            assassinSeat = assassin,
            targetSeat = target,
            hitMerlin = null
        )
        viewModelScope.launch {
            _state.value = _state.value.copy(saveStatus = SaveStatus.SAVING)
            val result = runCatching { repository.appendEvent(matchId, event) }
            val success = result.isSuccess && result.getOrNull()?.isSuccess == true
            _state.value = _state.value.copy(
                saveStatus = if (success) SaveStatus.SAVED else SaveStatus.FAILED,
                errorMessage = if (success) null else "记录刺杀失败"
            )
            if (success) refresh()
        }
    }
}
