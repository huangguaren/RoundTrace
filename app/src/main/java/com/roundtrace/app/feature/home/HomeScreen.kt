package com.roundtrace.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.roundtrace.app.R
import com.roundtrace.app.core.ui.component.ConfirmDialog
import com.roundtrace.app.core.ui.component.EmptyState
import com.roundtrace.app.core.ui.component.MatchCard
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.RoundTraceTheme

/**
 * 首页（对局列表 / 空态 / 错误态 / 加载态）。
 *
 * 硬约束（TASKS.md T10③ + T41⑤）：本函数是**无状态**可组合函数——
 * 列表数据全部来自 [HomeUiState]，**没有任何硬编码样例数据**。
 * 内部只持有「哪个弹层打开」这类瞬态 UI 状态。
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onCreateMatch: () -> Unit,
    onOpenMatch: (String) -> Unit,
    onFilterChange: (HomeFilter) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    onRenameMatch: (String, String) -> Unit = { _, _ -> },
    onDeleteMatch: (String) -> Unit = { },
    onOpenDetail: (String) -> Unit = onOpenMatch
) {
    var sheetMatchId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameMatchId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deleteMatchId by rememberSaveable { mutableStateOf<String?>(null) }

    val sheetMatch = state.items.firstOrNull { it.matchId == sheetMatchId }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HomeFilterRow(
                selected = state.filter,
                onFilterChange = onFilterChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = Dimens.PageHorizontalPadding,
                        vertical = Dimens.Spacing8
                    )
            )

            when {
                state.isLoading -> LoadingContent()

                state.errorMessage != null -> EmptyState(
                    icon = Icons.Outlined.ErrorOutline,
                    title = stringResource(R.string.state_error_title),
                    description = state.errorMessage,
                    actionLabel = stringResource(R.string.state_retry),
                    onAction = onRetry,
                    modifier = Modifier.fillMaxSize()
                )

                state.isEmpty -> EmptyState(
                    icon = Icons.Outlined.History,
                    title = stringResource(R.string.home_empty_title),
                    description = stringResource(R.string.home_empty_desc),
                    actionLabel = stringResource(R.string.home_empty_action),
                    onAction = onCreateMatch,
                    modifier = Modifier.fillMaxSize()
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Dimens.PageHorizontalPadding,
                        end = Dimens.PageHorizontalPadding,
                        top = Dimens.Spacing4,
                        bottom = FAB_CLEARANCE
                    ),
                    verticalArrangement = Arrangement.spacedBy(Dimens.ItemGap)
                ) {
                    items(items = state.items, key = { it.matchId }) { item ->
                        MatchCard(
                            title = item.title,
                            playerCount = item.playerCount,
                            questsStarted = item.questsStarted,
                            status = item.status,
                            updatedAtLabel = relativeTimeLabel(item.updatedAtEpochMs),
                            onClick = { onOpenMatch(item.matchId) },
                            onOverflowClick = { sheetMatchId = item.matchId }
                        )
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onCreateMatch,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Dimens.PageHorizontalPadding),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            icon = {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.home_empty_action)
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.home_empty_action),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        )
    }

    if (sheetMatch != null) {
        MatchOverflowSheet(
            matchTitle = sheetMatch.title,
            onDismiss = { sheetMatchId = null },
            onOpenDetail = {
                sheetMatchId = null
                onOpenDetail(sheetMatch.matchId)
            },
            onRename = {
                sheetMatchId = null
                renameMatchId = sheetMatch.matchId
                renameText = sheetMatch.title
            },
            onExport = {
                // 导出需要选择脱敏范围 → 直接进详情页的导出区（不放无响应按钮）
                sheetMatchId = null
                onOpenDetail(sheetMatch.matchId)
            },
            onDelete = {
                sheetMatchId = null
                deleteMatchId = sheetMatch.matchId
            }
        )
    }

    if (renameMatchId != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameMatchId = null },
            title = { Text(text = stringResource(R.string.card_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.card_rename_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = renameMatchId
                        if (target != null && renameText.isNotBlank()) {
                            onRenameMatch(target, renameText)
                        }
                        renameMatchId = null
                    }
                ) {
                    Text(text = stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameMatchId = null }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (deleteMatchId != null) {
        ConfirmDialog(
            title = stringResource(R.string.card_delete_title),
            message = stringResource(R.string.card_delete_message),
            onConfirm = {
                val target = deleteMatchId
                if (target != null) onDeleteMatch(target)
                deleteMatchId = null
            },
            onDismiss = { deleteMatchId = null }
        )
    }
}

/** 筛选行：全部 / 进行中 / 已结束 */
@Composable
private fun HomeFilterRow(
    selected: HomeFilter,
    onFilterChange: (HomeFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HomeFilter.entries.forEach { filter ->
            val label = when (filter) {
                HomeFilter.ALL -> stringResource(R.string.filter_all)
                HomeFilter.IN_PROGRESS -> stringResource(R.string.filter_in_progress)
                HomeFilter.FINISHED -> stringResource(R.string.filter_finished)
            }
            FilterChip(
                selected = filter == selected,
                onClick = { onFilterChange(filter) },
                label = {
                    Text(text = label, style = MaterialTheme.typography.labelMedium)
                },
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.heightIn(min = Dimens.MinTouchTarget),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

/** 加载态 */
@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

/** 相对时间文案；无第三方库，纯本地计算。 */
@Composable
private fun relativeTimeLabel(updatedAtEpochMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = (now - updatedAtEpochMs).coerceAtLeast(0L)
    val minutes = diff / 60_000L
    val hours = diff / 3_600_000L
    val days = diff / 86_400_000L
    return when {
        minutes < 1 -> stringResource(R.string.card_time_just_now)
        hours < 1 -> stringResource(R.string.card_time_minutes_ago, minutes.toInt())
        days < 1 -> stringResource(R.string.card_time_hours_ago, hours.toInt())
        else -> stringResource(R.string.card_time_days_ago, days.toInt())
    }
}

/** FAB 遮挡区高度，保证最后一张卡片不被 FAB 盖住。 */
private val FAB_CLEARANCE = androidx.compose.ui.unit.Dp(88f)

@Preview(name = "首页空态-浅色", showBackground = true)
@Composable
private fun HomeScreenEmptyPreview() {
    RoundTraceTheme(darkTheme = false) {
        HomeScreen(
            state = HomeUiState.empty(),
            onCreateMatch = {},
            onOpenMatch = {},
            onFilterChange = {},
            onRetry = {}
        )
    }
}

@Preview(name = "首页空态-深色", showBackground = true)
@Composable
private fun HomeScreenEmptyDarkPreview() {
    RoundTraceTheme(darkTheme = true) {
        HomeScreen(
            state = HomeUiState.empty(),
            onCreateMatch = {},
            onOpenMatch = {},
            onFilterChange = {},
            onRetry = {}
        )
    }
}
