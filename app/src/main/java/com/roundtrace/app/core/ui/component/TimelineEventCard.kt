package com.roundtrace.app.core.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.roundtrace.app.R
import com.roundtrace.app.core.replay.TimelineEntry
import com.roundtrace.app.core.ui.theme.CardShape
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.LocalExtendedColors

/**
 * 时间线事件卡片（DESIGN_SPEC §4.8 / TASKS.md T58）。
 *
 * - 左侧 2dp 竖线 + 节点圆点，按 `occurredOrder` 排序（排序由 `TimelineBuilder` 完成）；
 * - 两行：首行事件摘要，次行「第 N 任务 · 第 M 次提案」；
 * - 未记录字段显示 **「—— 未记录」**（`unknown` 色），**不留空白**；
 * - 点击展开来源事件 id，便于与证据对照。
 */
@Composable
fun TimelineEventCard(
    entry: TimelineEntry,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val extended = LocalExtendedColors.current

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
    ) {
        // 左侧竖线 + 节点
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = Dimens.Spacing12)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimens.Spacing8)
            ) {}
            Surface(
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(width = Dimens.DividerThickness, height = LINE_HEIGHT)
            ) {}
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.MinTouchTarget)
                .clickable { expanded = !expanded }
                .semantics {
                    contentDescription = "${entry.phaseLabel}，${entry.title}"
                },
            shape = CardShape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = Dimens.CardElevation
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dimens.CardPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.Spacing4)
            ) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${entry.phaseLabel} · ${entry.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (entry.hasUnrecordedFields) {
                    Text(
                        text = stringResource(R.string.timeline_unrecorded) +
                            " " + entry.unrecordedFields.joinToString("、"),
                        style = MaterialTheme.typography.bodySmall,
                        color = extended.unknown
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing4)) {
                        Text(
                            text = stringResource(R.string.timeline_source_events) +
                                " " + entry.sourceEventIds.joinToString(", ") { it.raw },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.timeline_occurred_order) +
                                " " + entry.occurredOrder,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 节点之间的竖线高度。 */
private val LINE_HEIGHT = androidx.compose.ui.unit.Dp(32f)
