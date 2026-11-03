package com.roundtrace.app.feature.replay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.roundtrace.app.R
import com.roundtrace.app.core.replay.PlayerTraceCategory
import com.roundtrace.app.core.replay.PlayerTraceFactLevel
import com.roundtrace.app.core.replay.PlayerTraceItem
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.PanelShape
import com.roundtrace.app.core.ui.theme.PillShape

/** 玩家时间线的列表测试标签（仅用于滚动断言，不参与业务逻辑）。 */
internal const val PLAYER_TRACE_LIST_TAG: String = "player_trace_list"

/**
 * 玩家行为时间线（F05-01 Phase C）。
 *
 * 无状态 Composable：座位 / 任务 / 类别三个筛选与列表数据全部由外部传入，
 * 「该显示什么」由 `PlayerTraceBuilder` 决定 —— 视图不做任何过滤或推断。
 *
 * 空态文案固定为「该玩家在当前时间范围内暂无可展示的记录」：
 * 「暂无」既包含「这个人真的没参与」，也包含「当前时间点还没轮到」，
 * 不给用户「他的记录丢了」的错觉。
 *
 * @param onSelectItem 点击条目 → 交出 `occurredOrder`，由 ViewModel 走既有的
 *        `ReplayAction.SelectPosition`，不新增导航路由
 */
@Composable
internal fun PlayerTraceSection(
    playerCount: Int,
    selectedSeat: Int?,
    questFilter: Int?,
    categoryFilter: PlayerTraceCategory?,
    items: List<PlayerTraceItem>,
    onSelectSeat: (Int) -> Unit,
    onSelectQuest: (Int?) -> Unit,
    onSelectCategory: (PlayerTraceCategory?) -> Unit,
    onSelectItem: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        SectionLabel(R.string.replay_trace_seat_section)
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.PageHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
        ) {
            items((1..playerCount).toList()) { seat ->
                val description = stringResource(R.string.replay_trace_seat_desc, seat)
                TraceChip(
                    label = stringResource(R.string.seat_number, seat),
                    selected = selectedSeat == seat,
                    description = description,
                    onClick = { onSelectSeat(seat) }
                )
            }
        }

        SectionLabel(R.string.replay_trace_quest_section)
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.PageHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
        ) {
            item {
                TraceChip(
                    label = stringResource(R.string.filter_all),
                    selected = questFilter == null,
                    description = stringResource(R.string.replay_trace_quest_all_desc),
                    onClick = { onSelectQuest(null) }
                )
            }
            items((1..MAX_QUEST).toList()) { questNo ->
                TraceChip(
                    label = stringResource(R.string.quest_stepper_current, questNo),
                    selected = questFilter == questNo,
                    description = stringResource(R.string.replay_trace_quest_desc, questNo),
                    onClick = { onSelectQuest(questNo) }
                )
            }
        }

        SectionLabel(R.string.replay_trace_category_section)
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.PageHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
        ) {
            item {
                TraceChip(
                    label = stringResource(R.string.filter_all),
                    selected = categoryFilter == null,
                    description = stringResource(R.string.replay_trace_category_all_desc),
                    onClick = { onSelectCategory(null) }
                )
            }
            items(PlayerTraceCategory.entries.toList()) { category ->
                TraceChip(
                    label = category.displayName,
                    selected = categoryFilter == category,
                    description = stringResource(
                        R.string.replay_trace_category_desc,
                        category.displayName
                    ),
                    onClick = { onSelectCategory(category) }
                )
            }
        }

        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.replay_trace_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.PageHorizontalPadding, vertical = Dimens.Spacing24)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(PLAYER_TRACE_LIST_TAG),
                contentPadding = PaddingValues(
                    start = Dimens.PageHorizontalPadding,
                    end = Dimens.PageHorizontalPadding,
                    top = Dimens.Spacing12,
                    bottom = Dimens.Spacing32
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
            ) {
                itemsIndexed(
                    items = items,
                    key = { _, item -> "${item.sourceEventId.raw}#${item.category.name}" }
                ) { _, item ->
                    PlayerTraceCard(item = item, onSelectItem = onSelectItem)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(resId: Int) {
    Text(
        text = stringResource(resId),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = Dimens.PageHorizontalPadding,
            end = Dimens.PageHorizontalPadding,
            top = Dimens.Spacing8,
            bottom = Dimens.Spacing4
        )
    )
}

@Composable
private fun TraceChip(
    label: String,
    selected: Boolean,
    description: String,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label, style = MaterialTheme.typography.labelMedium) },
        shape = PillShape,
        modifier = Modifier
            .heightIn(min = Dimens.MinTouchTarget)
            .semantics { contentDescription = description },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

/**
 * 单条记录卡片。
 *
 * 三行固定结构：类别 + 任务 + 事实等级 → 摘要 → 明细。
 * 「事实等级」只在非 `RECORDED` 时出现，避免每张卡片都挂一个「记录事实」的噪声标签；
 * 一旦是「记录为未知」，必须显式写出来，不能让用户以为是明确事实。
 */
@Composable
private fun PlayerTraceCard(
    item: PlayerTraceItem,
    onSelectItem: (Int) -> Unit
) {
    Surface(
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = Dimens.CardElevation,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(R.string.replay_trace_item_desc, item.occurredOrder)
            ) { onSelectItem(item.occurredOrder) }
    ) {
        Column(
            modifier = Modifier.padding(Dimens.Spacing12),
            verticalArrangement = Arrangement.spacedBy(Dimens.Spacing4)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.category.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                item.questNo?.let { questNo ->
                    Text(
                        text = stringResource(R.string.quest_stepper_current, questNo),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (item.factLevel != PlayerTraceFactLevel.RECORDED) {
                    Text(
                        text = stringResource(R.string.replay_trace_fact_unknown),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = item.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 阿瓦隆标准任务数（1..5）；用于任务筛选条，不从事件里猜最大任务号。 */
private const val MAX_QUEST = 5
