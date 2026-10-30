package com.roundtrace.app.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.roundtrace.app.R
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.PillShape

/**
 * 视角切换（DESIGN_SPEC §4.9 / TASKS.md T58①）。
 *
 * 三段：公开 / 当时 / 上帝。
 * 「上帝」在 `godViewEnabled == false` 时为**禁用态**，点击只提示
 * 「对局结束后可查看」，**不改变选中值**——这是 UI 层的最后一道闸门
 * （数据层的闸门是 `AvalonKnowledgeProvider` 抛 `IllegalStateException`）。
 *
 * @param godViewEnabled 由 `KnowledgeSnapshot.canOpenGodView` 决定（数据条件，不是状态猜测）
 * @param onGodViewBlocked 上帝视角被禁用时的点击回调（用于弹 Snackbar 提示）
 */
@Composable
fun PerspectiveSwitcher(
    selected: Perspective,
    godViewEnabled: Boolean,
    onSelect: (Perspective) -> Unit,
    onGodViewBlocked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
    ) {
        Perspective.entries.forEach { candidate ->
            val label = when (candidate) {
                Perspective.PUBLIC -> stringResource(R.string.perspective_public)
                Perspective.OBSERVER_AT_TIME -> stringResource(R.string.perspective_observer)
                Perspective.GOD_AFTER_REVEAL -> stringResource(R.string.perspective_god)
            }
            val enabled = candidate != Perspective.GOD_AFTER_REVEAL || godViewEnabled
            FilterChip(
                selected = selected == candidate,
                onClick = {
                    if (enabled) {
                        onSelect(candidate)
                    } else {
                        onGodViewBlocked()
                    }
                },
                enabled = true,
                label = {
                    Text(text = label, style = MaterialTheme.typography.labelMedium)
                },
                shape = PillShape,
                modifier = Modifier.heightIn(min = Dimens.MinTouchTarget),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = if (candidate == Perspective.GOD_AFTER_REVEAL) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    selectedLabelColor = if (candidate == Perspective.GOD_AFTER_REVEAL) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    labelColor = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
                    }
                )
            )
        }
    }
}

/** 禁用态透明度（与 SeatPicker 保持一致）。 */
private const val DISABLED_ALPHA = 0.4f
