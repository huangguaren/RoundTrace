package com.roundtrace.app.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.roundtrace.app.core.ui.theme.ButtonShape
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.LocalExtendedColors

/**
 * 统一空态 / 错误态组件（DESIGN_SPEC §5）。
 *
 * 规范要求每个页面都有「图标 + 一句说明 + 主按钮」的空态与「原因 + 重试」的错误态。
 * 本组件只做渲染，文案由调用方通过 `stringResource` 传入，保证文案单一来源。
 *
 * @param icon 顶部矢量图标（禁止用 emoji 充当图标）
 * @param title 主文案（如「还没有对局记录」）
 * @param description 副文案（如「开始记录你的第一局阿瓦隆」）
 * @param actionLabel 主按钮文案；为 null 时不渲染按钮
 * @param onAction 主按钮点击回调；`actionLabel` 非空时必须提供
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null
) {
    val extendedColors = LocalExtendedColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background, ButtonShape)
            .padding(horizontal = Dimens.Spacing24, vertical = Dimens.Spacing32),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clearAndSetSemantics { },
            tint = extendedColors.unknown
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Dimens.Spacing16)
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = Dimens.Spacing8)
                .width(280.dp)
        )
        val label: String? = actionLabel
        val click: (() -> Unit)? = onAction
        val resolvedAction: (@Composable () -> Unit)? = action ?: if (label != null && click != null) {
            {
                androidx.compose.material3.Button(
                    onClick = click,
                    shape = ButtonShape,
                    modifier = Modifier.padding(top = Dimens.Spacing24)
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        } else {
            null
        }
        if (resolvedAction != null) {
            resolvedAction()
        }
    }
}
