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
import com.roundtrace.app.core.ui.identity.SeatDisplayResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

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

        // 配额只从本局 config.roleSlots 合计；不从人数硬编码，也不重建标准预设。
        val quotaPolicy = RevealRoleQuotaPolicy(bundle.config.roleSlots)
        val mismatch = quotaPolicy.planMismatch(rows)

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
            errorMessage = null,
            roleQuota = quotaPolicy.quotaMap(),
            seatIdentity = SeatDisplayResolver.resolve(bundle.participants, playerCount),
            planMismatchWarning = mismatch.description
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
            RevealAction.ConfirmPlanMismatch ->
                _state.value = _state.value.copy(planMismatchConfirmed = true)
            RevealAction.SaveAssassination -> saveAssassination()
        }
    }

    private fun updateRow(seat: Int, roleId: RoleId) {
        val current = _state.value
        val updated = current.rows.map { if (it.seat == seat) it.copy(roleId = roleId) else it }
        val policy = RevealRoleQuotaPolicy.fromQuotaMap(current.roleQuota)
        val mismatch = policy.planMismatch(updated)

        // 超额**不硬拒绝**：计划只是创建时的配置，真实身份可能不同。
        // 写入草稿、给出差异警告，由 UI 引导「按实际身份记录」二次确认后才揭晓。
        _state.value = current.copy(
            rows = updated,
            errorMessage = null,
            planMismatchWarning = mismatch.description,
            // 一旦行内容变化，之前那次确认作废，必须重新确认
            planMismatchConfirmed = false
        )
    }

    /** 角色、揭晓时间和状态由仓储一次原子提交；失败保留当前输入。 */
    private fun reveal() {
        val current = _state.value
        if (!current.canReveal) return
        // 计划与真实身份不一致时必须先由用户明确确认；确认只放行本次写入，
        // 既不修改 roleSlots，也不篡改已录入的 roleAssignments。
        if (current.requiresPlanMismatchConfirmation) {
            _state.value = current.copy(errorMessage = "请先确认「按实际身份记录」后再揭晓")
            return
        }
        val assignments = current.rows.map { row ->
            RoleAssignment(
                seat = row.seat,
                roleId = row.roleId,
                source = RoleSource.REVEALED_AFTER_END,
                revealState = RevealState.REVEALED
            )
        }
        // 在 launch 之前设置闸门，重复点击无法排入第二次揭晓。
        _state.value = _state.value.copy(saveStatus = SaveStatus.SAVING)
        viewModelScope.launch {
            val result = try {
                Result.success(repository.revealMatch(matchId, assignments))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            if (result.isFailure || result.getOrNull()?.isSuccess != true) {
                _state.value = _state.value.copy(
                    saveStatus = SaveStatus.FAILED,
                    errorMessage = "揭晓失败，输入已保留，可重试"
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
