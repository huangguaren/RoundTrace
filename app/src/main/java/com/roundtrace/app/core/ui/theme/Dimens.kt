package com.roundtrace.app.core.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设计 Token —— 间距 / 触控下限 / 组件尺寸。
 *
 * 依据 `docs/DESIGN_SPEC.md` §3（间距梯度 4/8/12/16/24/32，页面水平内边距 16）
 * 与 §8（所有可点击元素 ≥ 48dp）、§4.1（底部 Tab ≥ 64dp）、§4.2（座位格 ≥ 56dp）。
 */
object Dimens {
    // —— 间距梯度 ——
    val Spacing4: Dp = 4.dp
    val Spacing8: Dp = 8.dp
    val Spacing12: Dp = 12.dp
    val Spacing16: Dp = 16.dp
    val Spacing24: Dp = 24.dp
    val Spacing32: Dp = 32.dp

    /** 页面水平内边距 */
    val PageHorizontalPadding: Dp = 16.dp

    /** 卡片内边距 */
    val CardPadding: Dp = 16.dp

    /** 列表项之间 / 卡片之间 */
    val ItemGap: Dp = 12.dp

    // —— 触控与组件尺寸 ——
    /** 全局最小触控目标（Android 硬下限） */
    val MinTouchTarget: Dp = 48.dp

    /** 座位格最小边长（DESIGN_SPEC §4.2 要求 56dp） */
    val SeatMinSize: Dp = 56.dp

    /** 底部 Tab 最小高度 */
    val TabHeight: Dp = 64.dp

    /** 底部 Tab 图标尺寸 */
    val TabIconSize: Dp = 24.dp

    /** 选中座位描边宽度 */
    val SelectedStroke: Dp = 2.dp

    /** 卡片阴影档位（仅一档） */
    val CardElevation: Dp = 1.dp

    /** 分隔线宽度 */
    val DividerThickness: Dp = 1.dp

    /** 徽标图标最大尺寸 */
    val BadgeIconSize: Dp = 14.dp
}
