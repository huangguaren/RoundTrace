package com.roundtrace.app.feature.replay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundtrace.app.core.data.MatchBundle
import com.roundtrace.app.core.data.MatchRepository
import com.roundtrace.app.core.knowledge.KnowledgeProvider
import com.roundtrace.app.core.knowledge.ReplayAnalyzer
import com.roundtrace.app.core.model.GamePosition
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.replay.TimelineBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 复盘 ViewModel（TASKS.md T57）。
 *
 * 三条硬约束：
 * 1. 每次切换位置/视角都**重新计算** `snapshot + findings + timeline`，不做任何缓存
 *    （缓存一旦与 `eventRevision` 对不齐，撤销后就是脏数据 —— 见 decisions B29）；
 * 2. `GOD_AFTER_REVEAL` 在 `canOpenGodView == false` 时被**拒绝**并给出提示，
 *    绝不悄悄降级或抛异常到 UI（T57②）；
 * 3. 事件被修订后衍生结论自动刷新：状态全部由 `observeBundle` 派生（T57④）。
 */
class ReplayViewModel(
    private val matchId: MatchId,
    private val repository: MatchRepository,
    private val knowledgeProvider: KnowledgeProvider,
    private val analyzer: ReplayAnalyzer
) : ViewModel() {

    /** `null` = 最新位置。 */
    private val requestedOrder = MutableStateFlow<Int?>(null)
    private val requestedMode = MutableStateFlow(Perspective.PUBLIC)
    private val godViewBlocked = MutableStateFlow(false)

    val uiState: StateFlow<ReplayUiState> = combine(
        repository.observeBundle(matchId),
        requestedOrder,
        requestedMode,
        godViewBlocked
    ) { bundle, order, mode, blocked ->
        buildUiState(bundle, order, mode, blocked)
    }
        .catch { throwable ->
            emit(
                ReplayUiState(
                    isLoading = false,
                    errorMessage = throwable.message ?: "复盘数据加载失败"
                )
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ReplayUiState(isLoading = true)
        )

    fun onAction(action: ReplayAction) {
        when (action) {
            is ReplayAction.SelectPosition -> requestedOrder.value = action.occurredOrder

            is ReplayAction.SelectQuest -> {
                // 该任务的最后一个事件 = 任务结束位置
                val bundle = currentBundle() ?: return
                val orders = bundle.events.filter { it.questNo == action.questNo }
                    .map { it.occurredOrder }
                requestedOrder.value = orders.maxOrNull()
            }

            is ReplayAction.SelectPerspective -> {
                if (action.mode == Perspective.GOD_AFTER_REVEAL && !uiState.value.canOpenGodView) {
                    // 拒绝：不改变模式，只标记「被拒绝」，由 UI 弹提示（T57②）
                    godViewBlocked.value = true
                    return
                }
                godViewBlocked.value = false
                requestedMode.value = action.mode
            }

            ReplayAction.ClearGodViewBlocked -> godViewBlocked.value = false
        }
    }

    /**
     * 当前快照所在的 bundle。
     *
     * 之所以要拿到它：`SelectQuest` 需要事件列表。这里直接读 `uiState` 里已有的位置信息
     * 不够，因此保留一次仓储读取（`getBundle` 是 suspend，这里用 last emitted 的缓存）。
     */
    private var lastBundle: MatchBundle? = null

    private fun currentBundle(): MatchBundle? = lastBundle

    private fun buildUiState(
        bundle: MatchBundle?,
        requestedOrder: Int?,
        requestedMode: Perspective,
        blocked: Boolean
    ): ReplayUiState {
        if (bundle == null) {
            return ReplayUiState(isLoading = false, errorMessage = "对局不存在或已被删除")
        }
        lastBundle = bundle

        val match = bundle.toReconstructedMatch()
        val latestOrder = bundle.events.maxOfOrNull { it.occurredOrder } ?: 0
        val at = GamePosition(requestedOrder ?: latestOrder, questNo = null, proposalNo = null)
        val observer = bundle.config.mySeat

        // 先用公开视角探一次，拿到 canOpenGodView（它不依赖视角本身）
        val probe = runCatching {
            knowledgeProvider.snapshot(match, observer, at, Perspective.PUBLIC)
        }.getOrElse { throwable ->
            return ReplayUiState(
                isLoading = false,
                matchTitle = bundle.summary.title,
                playerCount = bundle.config.playerCount,
                at = at,
                latestOrder = latestOrder,
                mode = requestedMode,
                observerSeat = observer,
                errorMessage = throwable.message ?: "视角计算失败"
            )
        }

        // 闸门：请求上帝视角但条件不满足 → 回落到公开视角（不抛异常、不静默给真相）
        val effectiveMode = if (requestedMode == Perspective.GOD_AFTER_REVEAL &&
            !probe.canOpenGodView
        ) {
            Perspective.PUBLIC
        } else {
            requestedMode
        }

        val snapshot = runCatching {
            knowledgeProvider.snapshot(match, observer, at, effectiveMode)
        }.getOrElse { throwable ->
            return ReplayUiState(
                isLoading = false,
                matchTitle = bundle.summary.title,
                playerCount = bundle.config.playerCount,
                at = at,
                latestOrder = latestOrder,
                mode = Perspective.PUBLIC,
                observerSeat = observer,
                canOpenGodView = probe.canOpenGodView,
                errorMessage = throwable.message ?: "视角计算失败"
            )
        }

        val findings = runCatching {
            analyzer.analyzeAt(
                match = match,
                observer = observer,
                at = at,
                mode = effectiveMode,
                knowledgeProvider = knowledgeProvider
            )
        }.getOrDefault(emptyList())

        return ReplayUiState(
            isLoading = false,
            matchTitle = bundle.summary.title,
            playerCount = bundle.config.playerCount,
            at = at,
            latestOrder = latestOrder,
            mode = effectiveMode,
            observerSeat = observer,
            snapshot = snapshot,
            findings = findings,
            timeline = TimelineBuilder.build(bundle.events, upTo = at.occurredOrder),
            canOpenGodView = probe.canOpenGodView,
            godViewBlocked = blocked,
            errorMessage = null
        )
    }
}
