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
import com.roundtrace.app.core.model.SeatId
import com.roundtrace.app.core.replay.PlayerTraceBuilder
import com.roundtrace.app.core.replay.PlayerTraceCategory
import com.roundtrace.app.core.replay.TimelineBuilder
import com.roundtrace.app.core.ui.identity.SeatDisplayResolver
import kotlinx.coroutines.flow.Flow
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

    /** 玩家行为时间线（F05-01）：座位与两个筛选条件。 */
    private val traceSeat = MutableStateFlow<SeatId?>(null)
    private val traceQuestFilter = MutableStateFlow<Int?>(null)
    private val traceCategoryFilter = MutableStateFlow<PlayerTraceCategory?>(null)

    /** 三个筛选条件合成一路，避免 `combine` 超过 5 路后失去类型安全。 */
    private val traceFilters: Flow<TraceFilters> =
        combine(traceSeat, traceQuestFilter, traceCategoryFilter) { seat, quest, category ->
            TraceFilters(seat = seat, questNo = quest, category = category)
        }

    val uiState: StateFlow<ReplayUiState> = combine(
        repository.observeBundle(matchId),
        requestedOrder,
        requestedMode,
        godViewBlocked,
        traceFilters
    ) { bundle, order, mode, blocked, filters ->
        buildUiState(bundle, order, mode, blocked, filters)
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

            is ReplayAction.SelectPlayerTraceSeat -> traceSeat.value = action.seat

            is ReplayAction.SetPlayerTraceQuestFilter -> traceQuestFilter.value = action.questNo

            is ReplayAction.SetPlayerTraceCategoryFilter -> traceCategoryFilter.value = action.category
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
        blocked: Boolean,
        filters: TraceFilters
    ): ReplayUiState {
        if (bundle == null) {
            return ReplayUiState(isLoading = false, errorMessage = "对局不存在或已被删除")
        }
        lastBundle = bundle

        val match = bundle.toReconstructedMatch()
        val latestOrder = bundle.events.maxOfOrNull { it.occurredOrder } ?: 0
        val at = GamePosition(requestedOrder ?: latestOrder, questNo = null, proposalNo = null)
        val observer = bundle.config.mySeat
        val playerCount = bundle.config.playerCount
        // 同一份 bundle 快照只解析一次：头像/昵称/座位号三处共用，
        // 不做跨快照缓存（B29），切局自然换成新局的资料。
        val seatIdentity = SeatDisplayResolver.resolve(bundle.participants, playerCount)
        // 没有任何昵称时保持 seatLabel = null：Builder 逐字输出基线文案，
        // 避免「1 / 2 / 3 号」被无谓改写成「1 号 / 2 号 / 3 号」。
        // 有昵称才启用结构化格式化（句式内用 formatSeatInSentence）。
        val seatLabel: ((com.roundtrace.app.core.model.SeatId) -> String)? =
            if (seatIdentity.hasAnyNames) seatIdentity::formatSeatInSentence else null
        val resolvedTraceSeat = resolveTraceSeat(filters.seat, observer, playerCount)

        // 先用公开视角探一次，拿到 canOpenGodView（它不依赖视角本身）
        val probe = runCatching {
            knowledgeProvider.snapshot(match, observer, at, Perspective.PUBLIC)
        }.getOrElse { throwable ->
            return ReplayUiState(
                isLoading = false,
                matchTitle = bundle.summary.title,
                playerCount = playerCount,
                at = at,
                latestOrder = latestOrder,
                mode = requestedMode,
                observerSeat = observer,
                playerTraceSeat = resolvedTraceSeat,
                playerTraceQuestFilter = filters.questNo,
                playerTraceCategoryFilter = filters.category,
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

        // NX-03：PUBLIC 视角直接复用 probe，不重复算 snapshot
        val snapshot = if (effectiveMode == Perspective.PUBLIC) {
            probe
        } else {
            runCatching {
                knowledgeProvider.snapshot(match, observer, at, effectiveMode)
            }.getOrElse { throwable ->
                return ReplayUiState(
                    isLoading = false,
                    matchTitle = bundle.summary.title,
                    playerCount = playerCount,
                    at = at,
                    latestOrder = latestOrder,
                    mode = Perspective.PUBLIC,
                    observerSeat = observer,
                    playerTraceSeat = resolvedTraceSeat,
                    playerTraceQuestFilter = filters.questNo,
                    playerTraceCategoryFilter = filters.category,
                    canOpenGodView = probe.canOpenGodView,
                    errorMessage = throwable.message ?: "视角计算失败"
                )
            }
        }

        // 视角过滤只做一次，findings / timeline / playerTrace 共用同一份可见历史
        // （重复过滤不算错，但会让「同一位置三个视图看到的事件集合不一致」这种 bug 有缝可钻）
        val visibleEvents = com.roundtrace.app.core.replay.VisibleEventPolicy.forPerspective(
            events = bundle.events,
            mode = effectiveMode,
            observerSeat = observer
        )

        // NX-03：直接用已算好的 snapshot，不再让 analyzeAt 内部重复创建快照
        val findings = runCatching {
            analyzer.analyze(snapshot, visibleEvents.filter { it.occurredOrder <= at.occurredOrder })
        }.getOrDefault(emptyList())

        return ReplayUiState(
            isLoading = false,
            matchTitle = bundle.summary.title,
            playerCount = playerCount,
            at = at,
            latestOrder = latestOrder,
            mode = effectiveMode,
            observerSeat = observer,
            snapshot = snapshot,
            findings = findings,
            // 时间线同样受可见性约束：公开视角不得出现私人笔记/私有声称
            // （RecordScreen 那条时间线服务「本人记录」，不受影响，故只在此处过滤）
            timeline = TimelineBuilder.build(
                events = visibleEvents,
                upTo = at.occurredOrder,
                seatLabel = seatLabel
            ),
            playerTraceSeat = resolvedTraceSeat,
            playerTraceQuestFilter = filters.questNo,
            playerTraceCategoryFilter = filters.category,
            // 玩家行为时间线：同一份 bundle 快照 + 同一份可见历史派生，
            // 不新增 DB 读取、不跨快照缓存（B29）
            playerTraceItems = if (resolvedTraceSeat == null) {
                emptyList()
            } else {
                PlayerTraceBuilder.build(
                    events = bundle.events,
                    playerSeat = resolvedTraceSeat,
                    atOrder = at.occurredOrder,
                    mode = effectiveMode,
                    observerSeat = observer,
                    playerCount = playerCount,
                    questFilter = filters.questNo,
                    categoryFilter = filters.category?.let { setOf(it) },
                    seatLabel = seatLabel
                )
            },
            canOpenGodView = probe.canOpenGodView,
            godViewBlocked = blocked,
            errorMessage = null,
            seatLabels = seatIdentity.seatLabels,
            seatIdentity = seatIdentity
        )
    }

    /**
     * 默认座位：观察者座位合法时选观察者，否则 1 号。
     *
     * 用户显式选择优先；但选择越界（人数被改小、导入了别的对局）时**回落到默认**，
     * 而不是把非法座位传下去 —— UI 的座位条只列 1..playerCount，选中态必须能对上。
     */
    private fun resolveTraceSeat(requested: SeatId?, observer: SeatId?, playerCount: Int): SeatId? {
        if (playerCount <= 0) return null
        val valid = 1..playerCount
        return requested?.takeIf { it in valid }
            ?: observer?.takeIf { it in valid }
            ?: 1
    }

    /** 玩家行为时间线的筛选条件（合成一路流用）。 */
    private data class TraceFilters(
        val seat: SeatId?,
        val questNo: Int?,
        val category: PlayerTraceCategory?
    )
}
