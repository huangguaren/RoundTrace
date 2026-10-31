package com.roundtrace.app.feature.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundtrace.app.core.data.MatchRepository
import com.roundtrace.app.core.data.SettingsRepository
import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.RoleSlotSpec
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.rules.avalon.AvalonRules
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 创建对局 ViewModel（TASKS.md T38）。
 *
 * 三条硬约束：
 * 1. 每次变更都调 `AvalonRules.validateSetup` 并把结果暴露成 `issues`（T38②）；
 * 2. `canCreate` 只在无 BLOCKING 时为 true（T38③）；
 * 3. `confirm()` 调 `createMatch` 并回传 `MatchId`（T38④）。
 *
 * 角色构成的默认值来自 `AvalonPreset.standardPreset(playerCount)`——
 * **不在 UI 里硬编码角色清单**（5/6 人局没有派西维尔，见 docs/decisions.md B17）。
 */
class CreateMatchViewModel(
    private val repository: MatchRepository,
    private val settingsRepository: SettingsRepository,
    private val rules: AvalonRules,
    private val clock: Clock
) : ViewModel() {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<CreateMatchUiState> = _state.asStateFlow()

    private fun initialState(): CreateMatchUiState {
        val count = SettingsRepository.DEFAULT_PLAYER_COUNT
        val slots = AvalonPreset.standardPreset(count)
        return CreateMatchUiState(
            playerCount = count,
            roleSlots = slots,
            initialLeaderSeat = 1,
            issues = validate(count, slots, mySeat = null, leader = 1)
        )
    }

    // ————————————————————————————————————————————————————————
    // 动作
    // ————————————————————————————————————————————————————————

    fun onPlayerCountChange(count: Int) {
        if (count !in AvalonPreset.MIN_PLAYERS..AvalonPreset.MAX_PLAYERS) return
        val slots = AvalonPreset.standardPreset(count)
        _state.value = _state.value.copy(
            playerCount = count,
            roleSlots = slots,
            // 座位随人数收敛，避免留下越界的旧值
            mySeat = _state.value.mySeat?.takeIf { it in 1..count },
            initialLeaderSeat = _state.value.initialLeaderSeat.coerceIn(1, count),
            issues = validate(
                count = count,
                slots = slots,
                mySeat = _state.value.mySeat?.takeIf { it in 1..count },
                leader = _state.value.initialLeaderSeat.coerceIn(1, count)
            )
        )
    }

    /** 角色数量增减；负数与「总数超过人数」在规则层被拦。 */
    fun onRoleCountChange(role: RoleId, delta: Int) {
        val current = _state.value
        val existing = current.roleSlots.firstOrNull { it.roleId == role }
        val slots: List<RoleSlotSpec> = when {
            existing == null -> if (delta > 0) {
                current.roleSlots + RoleSlotSpec(role, delta)
            } else {
                current.roleSlots
            }

            else -> current.roleSlots.map { slot ->
                if (slot.roleId == role) {
                    slot.copy(count = (slot.count + delta).coerceAtLeast(0))
                } else {
                    slot
                }
            }
        }
        _state.value = current.copy(
            roleSlots = sortedSlots(slots),
            issues = validate(
                count = current.playerCount,
                slots = slots,
                mySeat = current.mySeat,
                leader = current.initialLeaderSeat
            )
        )
    }

    fun onMySeatChange(seat: Int?) {
        val current = _state.value
        _state.value = current.copy(
            mySeat = seat,
            issues = validate(
                count = current.playerCount,
                slots = current.roleSlots,
                mySeat = seat,
                leader = current.initialLeaderSeat
            )
        )
    }

    fun onLeaderChange(seat: Int) {
        val current = _state.value
        if (seat !in 1..current.playerCount) return
        _state.value = current.copy(
            initialLeaderSeat = seat,
            issues = validate(
                count = current.playerCount,
                slots = current.roleSlots,
                mySeat = current.mySeat,
                leader = seat
            )
        )
    }

    fun onTitleChange(title: String) {
        _state.value = _state.value.copy(title = title)
    }

    /** 设置某个座位的显示名（空串/null = 清除名字）。 */
    fun onSeatNameChange(seat: Int, name: String?) {
        val current = _state.value
        val cleaned = name?.trim()?.takeIf { it.isNotEmpty() }
        _state.value = current.copy(
            seatNames = current.seatNames + (seat to cleaned)
        )
    }

    /** 设置某个座位的拖拽位置（归一化 0..1）。目标格子被占时自动互换。 */
    fun onSeatPositionChange(seat: Int, x: Float, y: Float) {
        val current = _state.value
        val oldPos = current.seatPositions[seat] ?: (0f to 0f)
        // 找目标格子上已有的座位
        val occupiedBy = current.seatPositions.entries.firstOrNull { (k, v) ->
            k != seat && kotlin.math.abs(v.first - x) < 0.01f && kotlin.math.abs(v.second - y) < 0.01f
        }?.key
        val newMap = if (occupiedBy != null) {
            // 互换：被占座位挪到当前座位原来的位置
            current.seatPositions + (seat to (x to y)) + (occupiedBy to oldPos)
        } else {
            current.seatPositions + (seat to (x to y))
        }
        _state.value = current.copy(seatPositions = newMap)
    }

    /** 设置某个座位的头像 URI。 */
    fun onSeatAvatarChange(seat: Int, uri: String?) {
        val current = _state.value
        _state.value = current.copy(
            seatAvatars = current.seatAvatars + (seat to uri)
        )
    }

    fun onNext() {
        val current = _state.value
        if (!current.canGoNext) return
        val nextIndex = (current.step.index + 1).coerceAtMost(CreateStep.total - 1)
        _state.value = current.copy(step = CreateStep.entries[nextIndex])
    }

    fun onBack() {
        val current = _state.value
        val previousIndex = (current.step.index - 1).coerceAtLeast(0)
        _state.value = current.copy(step = CreateStep.entries[previousIndex])
    }

    /** 确认创建：落库并回传 `MatchId`（T38④）。 */
    fun confirm() {
        val current = _state.value
        if (!current.canCreate) return
        val draft = current.toDraft()
        viewModelScope.launch {
            _state.value = _state.value.copy(isSaving = true, errorMessage = null)
            runCatching { repository.createMatch(draft) }
                .onSuccess { matchId ->
                    _state.value = _state.value.copy(
                        isSaving = false,
                        createdMatchId = matchId
                    )
                }
                .onFailure { throwable ->
                    _state.value = _state.value.copy(
                        isSaving = false,
                        errorMessage = throwable.message ?: "创建对局失败"
                    )
                }
        }
    }

    // ————————————————————————————————————————————————————————
    // 内部
    // ————————————————————————————————————————————————————————

    private fun CreateMatchUiState.toDraft(): MatchCreationDraft = MatchCreationDraft(
        title = title.toTrimmedOrNull() ?: defaultTitle(playerCount),
        playerCount = playerCount,
        roleSlots = roleSlots,
        mySeat = mySeat,
        initialLeaderSeat = initialLeaderSeat,
        participants = (1..playerCount).map { seat ->
            com.roundtrace.app.core.model.Participant(
                seat = seat,
                displayName = seatNames[seat],
                layoutX = seatPositions[seat]?.first ?: -1f,
                layoutY = seatPositions[seat]?.second ?: -1f,
                avatarUri = seatAvatars[seat]
            )
        },
        presetId = AvalonPreset.presetId(playerCount),
        rulesetId = AvalonPreset.RULESET_ID,
        rulesetVersion = AvalonPreset.RULESET_VERSION
    )

    private fun validate(
        count: Int,
        slots: List<RoleSlotSpec>,
        mySeat: Int?,
        leader: Int,
        title: String = ""
    ): List<com.roundtrace.app.core.model.RuleIssue> {
        if (count !in AvalonPreset.MIN_PLAYERS..AvalonPreset.MAX_PLAYERS) {
            // 人数非法时无法构造合法 config（AvalonPreset 会抛），直接交给规则层的范围校验
            return rules.validateSetup(
                MatchCreationDraft(
                    title = title,
                    playerCount = count,
                    roleSlots = slots,
                    mySeat = mySeat,
                    initialLeaderSeat = leader
                ).toGameConfig(clock.millis())
            )
        }
        val draft = MatchCreationDraft(
            title = title,
            playerCount = count,
            roleSlots = slots,
            mySeat = mySeat,
            initialLeaderSeat = leader,
            participants = MatchCreationDraft.defaultParticipants(count),
            presetId = AvalonPreset.presetId(count),
            rulesetId = AvalonPreset.RULESET_ID,
            rulesetVersion = AvalonPreset.RULESET_VERSION
        )
        return rules.validateSetup(draft.toGameConfig(clock.millis()))
    }

    /** 供运行时（_state 已就绪）调用：把当前 title 传入 validate。 */
    private fun validateCurrent(title: String): List<com.roundtrace.app.core.model.RuleIssue> {
        val s = _state.value
        return validate(
            count = s.playerCount,
            slots = s.roleSlots,
            mySeat = s.mySeat,
            leader = s.initialLeaderSeat,
            title = title
        )
    }

    /**
     * 按 ruleset 给出的展示顺序排序。
     *
     * **不在 feature 层维护角色清单**：顺序常量在 `AvalonPreset.roleDisplayOrder`，
     * 保证「预设变化 → UI 顺序自动跟上」。
     */
    private fun sortedSlots(slots: List<RoleSlotSpec>): List<RoleSlotSpec> {
        val order = AvalonPreset.roleDisplayOrder
        return slots.sortedBy { slot ->
            val index = order.indexOf(slot.roleId)
            if (index >= 0) index else order.size
        }
    }

    private fun String.toTrimmedOrNull(): String? = trim().takeIf { it.isNotEmpty() }

    private fun defaultTitle(playerCount: Int): String {
        val today = LocalDate.now(clock)
        return "${today.monthValue}月${today.dayOfMonth}日 ${playerCount}人局"
    }

    /** 仅用于测试断言：判断是否有好人/坏人配额提示。 */
    internal fun alignmentWarnings(): List<com.roundtrace.app.core.model.RuleIssue> =
        _state.value.issues.filter { it.code == com.roundtrace.app.core.model.IssueCode.ALIGNMENT_COUNT_MISMATCH }

    /** 计划中某阵营的数量（供测试与 UI 复用）。 */
    fun plannedAlignmentCount(alignment: Alignment): Int =
        _state.value.roleSlots.sumOf { if (it.roleId.alignment == alignment) it.count else 0 }
}
