package com.roundtrace.app.core.ui.identity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roundtrace.app.core.ui.avatar.SeatAvatarImage
import com.roundtrace.app.core.ui.theme.Dimens

/**
 * 统一的「头像 + 昵称 + 座位号」展示组件（UX-ID-01 / Phase A）。
 *
 * 复盘玩家筛选、揭晓行、以及任何需要定位「这是谁」的地方都应复用本组件，
 * 避免每个页面各写一套昵称/头像降级逻辑。
 *
 * ## 降级链（PRD §B）
 * - 有头像 URI → [SeatAvatarImage] 异步解码；**解码失败/授权被撤权自动回退**到占位；
 * - 占位优先用昵称首字，无昵称用座位号数字 —— 永远不会出现无法定位的「匿名照片」；
 * - 昵称 `maxLines = 1` + 省略号；[SeatDisplay.contentDescription] 保留完整昵称与座位号；
 * - 座位号常驻，与头像/昵称是否加载成功无关。
 *
 * ## 不做什么
 * 不读取角色真相、阵营、私人笔记；不把昵称当身份键；不做任何字符串替换。
 * 头像只用项目已有的 [SeatAvatarImage]（后台解码 + 采样 + LRU + 撤权回退）。
 *
 * @param display 由 [SeatDisplayResolver] 产出的只读展示信息
 * @param selected 选中态（供横滑筛选 Chip 复用）
 * @param onClick 非空时整行可点，最小触控高度 [Dimens.MinTouchTarget]
 * @param testTag 测试用标识
 * @param includeContentDescription 是否在容器上挂载完整无障碍说明。
 *        被外层已聚合描述的组件（如玩家筛选 Chip）包裹、需要避免重复播报时传 `false`，
 *        由外层统一提供合并后的说明；默认 `true` 保持独立使用时的语义不变。
 */
@Composable
fun SeatIdentityView(
    display: SeatDisplay,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    avatarSize: androidx.compose.ui.unit.Dp = AVATAR_SIZE,
    testTag: String? = null,
    includeContentDescription: Boolean = true
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val row: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
            modifier = Modifier
                .heightIn(min = Dimens.MinTouchTarget)
                .padding(horizontal = Dimens.Spacing8, vertical = Dimens.Spacing4)
        ) {
            // 头像：解码失败时 SeatAvatarImage 直接走 fallback，不抛异常、不串图
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(avatarSize)
                    .clip(CircleShape)
                    .background(container)
            ) {
                SeatAvatarImage(
                    uri = display.avatarUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = display.compactLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = content,
                        maxLines = 1
                    )
                }
            }

            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = display.name ?: display.seatNumberText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 座位号常驻：有昵称时也必须能看见「N号」（同名区分的唯一锚点）
                if (display.hasName) {
                    Text(
                        text = display.seatNumberText,
                        style = MaterialTheme.typography.labelSmall,
                        color = content.copy(alpha = 0.75f),
                        maxLines = 1
                    )
                }
            }
        }
    }

    // 默认在容器上挂一条完整的无障碍说明（含未被省略的完整昵称）；
    // 当被外层已聚合描述的组件（如筛选 Chip）包裹、需要避免重复播报时，
    // 传 includeContentDescription = false，由外层统一提供合并后的说明。
    // 刻意不 mergeDescendants：昵称 / 座位号 / 占位首字要能被独立断言。
    val semanticsModifier = Modifier
        .then(
            if (includeContentDescription) {
                Modifier.semantics { contentDescription = display.contentDescription }
            } else {
                Modifier
            }
        )
        .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)

    if (onClick != null) {
        Surface(
            onClick = onClick,
            selected = selected,
            color = container,
            contentColor = content,
            shape = MaterialTheme.shapes.small,
            modifier = modifier.then(semanticsModifier)
        ) { row() }
    } else {
        Box(modifier = modifier.then(semanticsModifier)) { row() }
    }
}

private val AVATAR_SIZE = 40.dp
