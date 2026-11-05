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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.roundtrace.app.R
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.replay.PlayerTraceCategory
import com.roundtrace.app.core.replay.PlayerTraceFactLevel
import com.roundtrace.app.core.replay.PlayerTraceItem
import com.roundtrace.app.core.ui.identity.SeatIdentity
import com.roundtrace.app.core.ui.identity.SeatIdentityView
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.PanelShape
import com.roundtrace.app.core.ui.theme.PillShape

/** 玩家时间线的列表测试标签（仅用于滚动断言，不参与业务逻辑）。 */
internal const val PLAYER_TRACE_LIST_TAG: String = "player_trace_list"

/** 玩家筛选横滑行的测试标签（用于「10 人局可达最后座位」的滚动断言）。 */
internal const val PLAYER_TRACE_SEAT_ROW_TAG: String = "player_trace_seat_row"

/** 单个座位 Chip 的测试标签前缀；完整标签为 `前缀+seat`。 */
internal const val PLAYER_TRACE_SEAT_CHIP_TAG: String = "player_trace_seat_"

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
    modifier: Modifier = Modifier,
    seatLabels: Map<Int, String?> = emptyMap(),
    /** 统一座位身份映射（UX-ID-02）；null = 沿用旧纯文字 Chip，保证既有测试零变化。 */
    seatIdentity: SeatIdentity? = null
) {
    Column(modifier = modifier.fillMaxSize()) {
        SectionLabel(R.string.replay_trace_seat_section)
        LazyRow(
            modifier = Modifier.testTag(PLAYER_TRACE_SEAT_ROW_TAG),
            contentPadding = PaddingValues(horizontal = Dimens.PageHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
        ) {
            items((1..playerCount).toList()) { seat ->
                val description = stringResource(R.string.replay_trace_seat_desc, seat)
                // 有昵称显示昵称，否则显示座位号
                val label = seatLabels[seat]?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.seat_number, seat)
                if (seatIdentity != null) {
                    SeatTraceChip(
                        display = seatIdentity.display(seat),
                        selected = selectedSeat == seat,
                        description = description,
                        tag = "$PLAYER_TRACE_SEAT_CHIP_TAG$seat",
                        onClick = { onSelectSeat(seat) }
                    )
                } else {
                    TraceChip(
                        label = label,
                        selected = selectedSeat == seat,
                        description = description,
                        onClick = { onSelectSeat(seat) }
                    )
                }
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

/**
 * 带头像的玩家筛选 Chip（UX-ID-02 / Phase C 修复）。
 *
 * 复用 Phase A 的 [SeatIdentityView]（头像 + 昵称 + 常驻座位号 + 无障碍说明），
 * 但把外层「查看 N 号的记录」与内层「阿华，N号座位」两层描述合并成**一条**
 * `contentDescription`（"查看 N 号的记录，阿华，N号座位"），并用
 * `mergeDescendants = true` 让内层文字节点被吸收进同一个语义节点——
 * 否则读屏会先把 Chip 当成一个节点播报一次座位号，再深入内层把昵称/座位号再播报一次。
 *
 * 内层 [SeatIdentityView] 因此传 `includeContentDescription = false`，
 * 避免它再单独挂一条说明与外层重复。
 */
@Composable
private fun SeatTraceChip(
    display: com.roundtrace.app.core.ui.identity.SeatDisplay,
    selected: Boolean,
    description: String,
    tag: String,
    onClick: () -> Unit
) {
    // 外层动作描述 + 内层身份描述合并为单条，座位号只出现一次，杜绝重复播报。
    val mergedDescription = "$description，${display.contentDescription}"
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            // 横滑列表里 Chip 宽度必须有限：否则超长昵称会把整行撑到不可滚动，
            // 也轮不到 Text 的 ellipsis 生效。昵称在 [SEAT_CHIP_MAX_WIDTH] 内单行省略，
            // 完整昵称仍由合并后的 contentDescription 提供（不依赖内层单独说明）。
            SeatIdentityView(
                display = display,
                selected = selected,
                avatarSize = Dimens.Spacing24,
                modifier = Modifier.widthIn(max = SEAT_CHIP_MAX_WIDTH),
                includeContentDescription = false
            )
        },
        shape = PillShape,
        modifier = Modifier
            .heightIn(min = Dimens.MinTouchTarget)
            .testTag(tag)
            .semantics(mergeDescendants = true) { contentDescription = mergedDescription },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
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
 * 三行固定结构：类别 + 任务 + 事实等级 + 私有来源标记 → 摘要 → 明细。
 * 「事实等级」只在非 `RECORDED` 时出现，避免每张卡片都挂一个「记录事实」的噪声标签；
 * 一旦是「记录为未知」，必须显式写出来，不能让用户以为是明确事实。
 * 「来自私有记录 · 仅本人可见」同理：只在来源事件是 `PRIVATE_OBSERVER` 时出现。
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
                // 私有来源必须显式标注：它只在本人视角下合法可见，
                // 不带标记会让用户把「只有我记得的东西」当成公开历史。
                if (item.sourceVisibility == EventVisibility.PRIVATE_OBSERVER) {
                    Text(
                        text = stringResource(R.string.replay_trace_private_source),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
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

/** 玩家筛选 Chip 的最大宽度：保证 5–10 人横滑列表不被长昵称撑爆。 */
private val SEAT_CHIP_MAX_WIDTH = 168.dp
