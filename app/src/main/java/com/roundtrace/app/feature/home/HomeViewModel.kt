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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页 ViewModel（TASKS.md T41①）。
 *
 * 只做三件事：观察仓储的 `Flow`、映射成 UI 模型、把用户动作转发给仓储。
 * **不做任何规则计算**（规则全在 domain 层，票数/门槛/胜负一律不在这里重算）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repository: MatchRepository
) : ViewModel() {

    private val filterState = MutableStateFlow(HomeFilter.ALL)

    /** 重试触发器：`retry()` 让整个查询链重新订阅。 */
    private val retryTrigger = MutableStateFlow(0)

    private val loading = MutableStateFlow(true)
    private val errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<HomeUiState>

    init {
        val summaries = combine(filterState, retryTrigger) { filter, _ -> filter }
            .flatMapLatest { filter -> repository.observeSummaries(filter.toStatusFilter()) }

        uiState = combine(
            summaries,
            filterState,
            loading,
            errorMessage
        ) { items, filter, isLoading, error ->
            HomeUiState(
                isLoading = isLoading && items.isEmpty(),
                items = items.map { it.toHomeItem() },
                errorMessage = error,
                filter = filter
            )
        }
            .catch { throwable ->
                emit(
                    HomeUiState(
                        isLoading = false,
                        items = emptyList(),
                        errorMessage = throwable.message,
                        filter = filterState.value
                    )
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = HomeUiState(isLoading = true)
            )

        // 首个数据集到达后关闭 loading
        viewModelScope.launch {
            summaries.collect {
                loading.value = false
                errorMessage.value = null
            }
        }
    }

    fun onFilterChange(filter: HomeFilter) {
        filterState.value = filter
    }

    /** 错误态重试：重新订阅查询链。 */
    fun retry() {
        loading.value = true
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

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
