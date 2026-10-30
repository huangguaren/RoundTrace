package com.roundtrace.app.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 设计 Token —— 形状。
 *
 * 依据 `docs/DESIGN_SPEC.md` §3：卡片 16dp / 按钮 12dp / 座位格 12dp / 标签 8dp / 胶囊 999dp。
 * TASKS.md T06③ 要求提供 12 / 16 / 20dp 与胶囊。
 */

/** 卡片圆角 16dp */
val CardShape = RoundedCornerShape(16.dp)

/** 按钮圆角 12dp */
val ButtonShape = RoundedCornerShape(12.dp)

/** 座位格圆角 12dp */
val SeatShape = RoundedCornerShape(12.dp)

/** 标签圆角 8dp */
val TagShape = RoundedCornerShape(8.dp)

/** 较大容器圆角 20dp */
val PanelShape = RoundedCornerShape(20.dp)

/** 胶囊形（999dp，等价于全圆角） */
val PillShape = RoundedCornerShape(999.dp)

/** 无圆角 */
val FlatShape = RoundedCornerShape(0.dp)

/** Material 3 Shapes 装配 */
val RoundTraceShapes = Shapes(
    extraSmall = TagShape,
    small = ButtonShape,
    medium = CardShape,
    large = PanelShape,
    extraLarge = RoundedCornerShape(28.dp)
)
