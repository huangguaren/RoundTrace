package com.roundtrace.app.feature.home

import com.roundtrace.app.core.model.MatchStatus

/**
 * 首页筛选条件（对应 `MatchStatusFilter` 的 UI 语义）。
 */
enum class HomeFilter {
    ALL,
    IN_PROGRESS,
    FINISHED
}

/**
 * 首页列表项视图模型。
 *
 * 刻意不使用 domain 的 `MatchSummary`：首页卡片只需要展示用到的字段，
 * 且 `matchId` 在 UI 层用字符串（避免 UI 依赖 value class 的 raw 语义）。
 */
data class HomeMatchItem(
    val matchId: String,
    val title: String,
    val playerCount: Int,
    val questsStarted: Int,
    val status: MatchStatus,
    val updatedAtEpochMs: Long,
    val mySeat: Int?
)

/**
 * 首页 UiState（TASKS.md T10③：`HomeScreen` 必须是只接收 state 的无状态可组合函数）。
 *
 * 四个互斥态由 [isLoading] / [errorMessage] / [items] 组合表达：
 *  - 加载中：isLoading = true
 *  - 错误：errorMessage != null（可重试）
 *  - 空态：isEmpty = true
 *  - 列表：items 非空
 */
data class HomeUiState(
    val isLoading: Boolean = false,
    val items: List<HomeMatchItem> = emptyList(),
    val errorMessage: String? = null,
    val filter: HomeFilter = HomeFilter.ALL
) {

    /** 是否展示空态（非加载、无错误、无数据）。 */
    val isEmpty: Boolean
        get() = !isLoading && errorMessage == null && items.isEmpty()

    /** 是否展示列表。 */
    val hasItems: Boolean
        get() = !isLoading && errorMessage == null && items.isNotEmpty()

    companion object {

        /** 初始空态：M0 阶段（仓储未接入）首页固定处于该状态。 */
        fun empty(filter: HomeFilter = HomeFilter.ALL): HomeUiState = HomeUiState(
            isLoading = false,
            items = emptyList(),
            errorMessage = null,
            filter = filter
        )
    }
}
