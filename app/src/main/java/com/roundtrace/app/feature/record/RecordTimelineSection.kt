package com.roundtrace.app.feature.record

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.roundtrace.app.R
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.replay.TimelineEntry
import com.roundtrace.app.core.ui.component.EmptyState
import com.roundtrace.app.core.ui.theme.CardShape
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.LocalExtendedColors

/**
 * 记录页内嵌时间线（TASKS.md T49③）。
 *
 * 复用 `TimelineBuilder` 产出的 [TimelineEntry]，与复盘页共用同一份中间模型。
 * **未记录字段必须显示「—— 未记录」**，不能留空白（DESIGN_SPEC §4.8）。
 *
 * 点卡片右上角删除按钮可删除该条事件（及其后续重算）。
 */
@Composable
fun RecordTimelineSection(
    entries: List<TimelineEntry>,
    onEntryClick: ((TimelineEntry) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
    ) {
        Text(
            text = stringResource(R.string.timeline_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        if (entries.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Timeline,
                title = stringResource(R.string.timeline_empty_title),
                description = stringResource(R.string.timeline_empty_desc),
                modifier = Modifier.fillMaxWidth()
            )
            return@Column
        }

        entries.forEach { entry ->
            TimelineRow(entry = entry, onClick = { onEntryClick?.invoke(entry) })
        }
    }
}

@Composable
private fun TimelineRow(entry: TimelineEntry, onClick: () -> Unit) {
    val extended = LocalExtendedColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
    ) {
        // 左侧竖线 + 节点（DESIGN_SPEC §4.8）
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = Dimens.Spacing8)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimens.Spacing8)
            ) {}
            Surface(
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .size(width = Dimens.DividerThickness, height = TIMELINE_LINE_HEIGHT)
            ) {}
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
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
                    text = entry.phaseLabel + " · " + entry.detail,
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
            }
        }
    }
}

/** 时间线节点之间的竖线高度。 */
private val TIMELINE_LINE_HEIGHT = 28.dp
