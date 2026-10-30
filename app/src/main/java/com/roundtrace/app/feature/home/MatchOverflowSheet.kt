package com.roundtrace.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.roundtrace.app.R
import com.roundtrace.app.core.ui.theme.ButtonShape
import com.roundtrace.app.core.ui.theme.Dimens

/**
 * 对局卡片的二级操作菜单（TASKS.md T42①）。
 *
 * 四项：重命名 / 删除 / 导出 / 查看详情。
 * **导出**在 T63 之前没有真实实现，因此按 T42 的要求**先跳转对局详情**，
 * 绝不渲染「点了没反应」的按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchOverflowSheet(
    matchTitle: String,
    onDismiss: () -> Unit,
    onOpenDetail: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.PageHorizontalPadding, vertical = Dimens.Spacing8),
            verticalArrangement = Arrangement.spacedBy(Dimens.Spacing4)
        ) {
            Text(
                text = matchTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = Dimens.Spacing8)
            )
            SheetAction(
                icon = Icons.Outlined.Info,
                label = stringResource(R.string.card_action_detail),
                onClick = onOpenDetail
            )
            SheetAction(
                icon = Icons.Outlined.DriveFileRenameOutline,
                label = stringResource(R.string.card_action_rename),
                onClick = onRename
            )
            SheetAction(
                icon = Icons.Outlined.IosShare,
                label = stringResource(R.string.card_action_export),
                onClick = onExport
            )
            SheetAction(
                icon = Icons.Outlined.Delete,
                label = stringResource(R.string.card_action_delete),
                onClick = onDelete,
                destructive = true
            )
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false
) {
    val contentColor = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    TextButton(
        onClick = onClick,
        shape = ButtonShape,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing12)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(Dimens.TabIconSize)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor
            )
        }
    }
}
