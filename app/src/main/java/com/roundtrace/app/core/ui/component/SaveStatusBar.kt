package com.roundtrace.app.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.roundtrace.app.R
import com.roundtrace.app.core.ui.theme.ButtonShape
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.LocalExtendedColors

/**
 * 保存状态三态（DESIGN_SPEC §4.11）。
 *
 * 定义在 UI 层：这是「界面需要反映的写入状态」，不是领域概念。
 */
enum class SaveStatus {
    SAVED,
    SAVING,
    FAILED
}

/**
 * 保存状态栏（TASKS.md T45③）。
 *
 * 必须是**真实状态**：调用方只在真正发起/完成写入时改变它。
 * 失败态必须提供「重试」按钮，而不是只变个颜色。
 */
@Composable
fun SaveStatusBar(
    status: SaveStatus,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extended = LocalExtendedColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.PageHorizontalPadding, vertical = Dimens.Spacing8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
    ) {
        when (status) {
            SaveStatus.SAVED -> {
                Text(
                    text = stringResource(R.string.save_status_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = extended.unknown
                )
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = extended.unknown,
                    modifier = Modifier.size(Dimens.BadgeIconSize)
                )
            }

            SaveStatus.SAVING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(Dimens.BadgeIconSize),
                    strokeWidth = Dimens.DividerThickness,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Text(
                    text = stringResource(R.string.save_status_saving),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SaveStatus.FAILED -> {
                Text(
                    text = stringResource(R.string.save_status_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = onRetry, shape = ButtonShape) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(Dimens.BadgeIconSize)
                    )
                    Text(
                        text = stringResource(R.string.save_status_retry),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
