package com.roundtrace.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundtrace.app.core.data.MatchRepository
import com.roundtrace.app.core.data.MatchStatusFilter
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MatchSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页 ViewModel（TASKS.md T41①）。
 *
 * 只做三件事：观察仓储的 `Flow`、映射成 UI 模型、把用户动作转发给仓储。
 * **不做任何规则计算**（规则全在 domain 层，票数/门槛/胜负一律不在这里重算）。
 *
 * ## NX-03：查询失败必须可恢复
 *
 * 之前 `.catch` 挂在**整条组合流末尾**（`summaries + filter + errorMessage` 之后），
 * 于是上游任何一次异常都会让 `stateIn` 的上游协程**永久结束**：
 * `retry()` 只是把 `retryTrigger` +1，但已经没有任何人在收集这条流，
 * 界面停在上一条错误值上，用户「重试」永远无效。
 *
 * 现在的形态：
 * 1. 每一次 `filter` / `retryTrigger` 变化都由 `flatMapLatest` **新建一条查询流**；
 * 2. 异常处理放在这条**单次查询流**内部（`catch` 在 `flatMapLatest` 里），
 *    所以一次失败只结束这一次订阅，不会污染外层；
 * 3. 每次查询先发 [QueryState.Loading]（`onStart`），保证「筛选切换 / 重试」都能看到加载态，
 *    而不是把上一次的结果或上一次的错误留在屏幕上；
 * 4. `retry()` 依旧只做「清错误 + trigger+1」，语义不变，但现在它能真正触发重新订阅；
 * 5. **筛选条件随查询结果一起流动**（`QueryState` 自带 `filter`），不再与 `filterState`
 *    二次组合——否则切换筛选的瞬间会组合出「新筛选标签 + 旧筛选数据」的一次发射（T-C03）。
 *
 * 刻意**不**使用 `distinctUntilChanged`：它会掩盖「相同列表但底层发生了重新订阅」的事实，
 * 也可能把「错误 → 同内容数据」这类恢复过程吞掉。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repository: MatchRepository
) : ViewModel() {

    private val filterState = MutableStateFlow(HomeFilter.ALL)

    /** 重试触发器：`retry()` 让 `flatMapLatest` 重新新建一条查询流。 */
    private val retryTrigger = MutableStateFlow(0)

    /** rename/delete 这类**用户操作**的错误，与查询错误区分开（两者共用一个 `errorMessage` 出口）。 */
    private val errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<HomeUiState>

    init {
        // 单次查询流的三种状态：加载中 / 数据 / 查询失败。
        //
        // 关键细节：**过滤条件被放进 QueryState 内部**，而不是再与 `filterState` 组合一次。
        // 否则 `combine(queryFlow, filterState)` 在切换筛选的瞬间会拿「旧查询的结果 + 新筛选值」
        // 组合出一次发射，UI 就会在 FINISHED 标签下短暂显示 ALL 的列表（T-C03 的「闪回旧结果」）。
        val queryFlow = combine(filterState, retryTrigger) { filter, _ -> filter }
            .flatMapLatest { filter ->
                repository.observeSummaries(filter.toStatusFilter())
                    .map<List<MatchSummary>, QueryState> { QueryState.Data(filter, it) }
                    // 每次新订阅先进入 Loading：筛选切换不闪回旧筛选的结果，重试也有明确的中间态
                    .onStart { emit(QueryState.Loading(filter)) }
                    // 关键：catch 在单次查询流内部，一次失败只结束这一次订阅
                    .catch { throwable ->
                        emit(QueryState.Error(filter, throwable.message ?: DEFAULT_QUERY_ERROR))
                    }
            }

        uiState = combine(
            queryFlow,
            errorMessage
        ) { query, operationError ->
            when (query) {
                is QueryState.Loading -> HomeUiState(
                    isLoading = true,
                    items = emptyList(),
                    errorMessage = operationError,
                    filter = query.filter
                )

                is QueryState.Data -> HomeUiState(
                    isLoading = false,
                    items = query.items.map { it.toHomeItem() },
                    errorMessage = operationError,
                    filter = query.filter
                )

                is QueryState.Error -> HomeUiState(
                    isLoading = false,
                    items = emptyList(),
                    // 操作错误优先（它是用户刚刚主动触发的、更需要立刻解释的动作）
                    errorMessage = operationError ?: query.message,
                    filter = query.filter
                )
            }
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = HomeUiState(isLoading = true)
            )
    }

    fun onFilterChange(filter: HomeFilter) {
        filterState.value = filter
    }

    /** 错误态重试：清掉错误并让查询链重新订阅（新的订阅 = 新的 `observeSummaries` 收集）。 */
    fun retry() {
        errorMessage.value = null
        retryTrigger.value += 1
    }

    fun rename(matchId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.renameMatch(MatchId(matchId), trimmed) }
                .onFailure { errorMessage.value = it.message }
        }
    }

    fun delete(matchId: String) {
        viewModelScope.launch {
            runCatching { repository.deleteMatch(MatchId(matchId)) }
                .onFailure { errorMessage.value = it.message }
        }
    }

    private fun MatchSummary.toHomeItem(): HomeMatchItem = HomeMatchItem(
        matchId = matchId.raw,
        title = title,
        playerCount = playerCount,
        questsStarted = questsStarted,
        status = status,
        updatedAtEpochMs = updatedAtEpochMs,
        mySeat = mySeat
    )

    private fun HomeFilter.toStatusFilter(): MatchStatusFilter = when (this) {
        HomeFilter.ALL -> MatchStatusFilter.ALL
        HomeFilter.IN_PROGRESS -> MatchStatusFilter.IN_PROGRESS
        HomeFilter.FINISHED -> MatchStatusFilter.FINISHED
    }

    /**
     * 单次查询流的状态。
     *
     * 为什么需要它：`HomeUiState` 只有 `isLoading` / `items` / `errorMessage` 三个字段，
     * 「空列表 + 无错误」既可能是「真的没有对局」，也可能是「还没查完」。
     * 在单次查询流内部用一个显式三态，可以让「失败可恢复」这件事在类型层面成立，
     * 而不是靠字段的偶然组合去猜。
     *
     * 每个状态都带上它**属于哪个筛选**（`filter`）：这样 UI 永远不会看到
     * 「新筛选标签 + 旧筛选数据」的组合（T-C03）。
     */
    private sealed interface QueryState {
        val filter: HomeFilter

        data class Loading(override val filter: HomeFilter) : QueryState
        data class Data(
            override val filter: HomeFilter,
            val items: List<MatchSummary>
        ) : QueryState

        data class Error(
            override val filter: HomeFilter,
            val message: String
        ) : QueryState
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** 底层异常没有 message 时的兜底文案（绝不显示空错误）。 */
        const val DEFAULT_QUERY_ERROR = "查询失败，请重试"
    }
}
