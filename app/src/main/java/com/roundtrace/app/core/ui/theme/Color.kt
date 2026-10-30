package com.roundtrace.app.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 设计 Token —— 颜色。
 *
 * 本文件是全工程**唯一**允许出现 `Color(0xFF...)` 字面量的文件（DESIGN_SPEC §8 最后一条 / D5）。
 *
 * ⚠️ 本副本由 design 侧生成（2026-10-30）：48 个色值已换为硅粉方案，常量名与结构一字未动，
 *    可直接覆盖 `app/src/main/java/com/roundtrace/app/core/ui/theme/Color.kt`。
 * 取值逐值对齐 `docs/PALETTE_SILICON_PINK.md` §2.2（浅色）与 §2.3（深色）——
 * 即 v2「硅粉」配色；DESIGN_SPEC §1.1/§1.2 的旧蓝色值已由该文档取代。
 *
 * 命名约定：`Light*` / `Dark*` 前缀表示两套配色；不带前缀的 `val` 是对外暴露的语义 token
 * （仅供 Theme.kt 装配 ColorScheme 与 ExtendedColors 使用）。
 */

// ——————————————————————————————————————————————————————————————
// 浅色（默认）
// ——————————————————————————————————————————————————————————————

/** 页面底色 */
val LightBackground = Color(0xFFF8F7F8)

/** 卡片、输入区 */
val LightSurface = Color(0xFFFFFFFF)

/** 次级容器、未选中座位底 */
val LightSurfaceVariant = Color(0xFFF1EFF1)

/** 主文字 */
val LightOnSurface = Color(0xFF221A1D)

/** 主文字（与 onSurface 同值，语义分开便于未来分化） */
val LightOnBackground = Color(0xFF221A1D)

/** 次要文字、说明 */
val LightOnSurfaceVariant = Color(0xFF6E6670)

/** 描边、分隔线 */
val LightOutline = Color(0xFFDCD8DC)

/** 更弱的分隔线（由 outline 派生，DESIGN_SPEC 未单列） */
val LightOutlineVariant = Color(0xFFEAE6EA)

/** 主操作、选中态、进行中 */
val LightPrimary = Color(0xFFAD4D68)

/** 主按钮文字 */
val LightOnPrimary = Color(0xFFFFFFFF)

/** 选中座位底、主色浅底 */
val LightPrimaryContainer = Color(0xFFFBE9EE)

/** 选中座位文字 */
val LightOnPrimaryContainer = Color(0xFF6D2338)

/** 任务成功 */
val LightSuccess = Color(0xFF17795F)

/** 成功浅底 */
val LightSuccessContainer = Color(0xFFE3F2EC)

/** 任务失败、删除 */
val LightError = Color(0xFFC8483C)

/** 失败浅底 */
val LightErrorContainer = Color(0xFFFBE9E6)

/** 特殊身份点缀（梅林/派西维尔）、警示性中性 */
val LightAccent = Color(0xFF3F6E8C)

/** 身份标签浅底 */
val LightAccentContainer = Color(0xFFE4EEF4)

/** 未知 / 未记录 */
val LightUnknown = Color(0xFF8A8A94)

/** 未知浅底 */
val LightUnknownContainer = Color(0xFFEFEFF2)

/** accent 作为 secondary 时的前景色（派生值，DESIGN_SPEC 未单列） */
val LightOnSecondary = Color(0xFFFFFFFF)

/** accent 作为 secondary 容器时的前景色（派生值） */
val LightOnSecondaryContainer = Color(0xFF1E3B4D)

/** error 前景色（派生值） */
val LightOnError = Color(0xFFFFFFFF)

/** error 容器前景色（派生值） */
val LightOnErrorContainer = Color(0xFF4A1712)

/** success 前景色（派生值：白色文字压在 #17795F 上，实测 5.33:1） */
val LightOnSuccess = Color(0xFFFFFFFF)

/** success 容器前景色（派生值） */
val LightOnSuccessContainer = Color(0xFF0C3B2E)

/** unknown 前景色（派生值） */
val LightOnUnknown = Color(0xFFFFFFFF)

/** unknown 容器前景色（派生值） */
val LightOnUnknownContainer = Color(0xFF3A3B42)

/** 遮罩色（Scaffold scrim，唯一一个非语义位的固定色） */
val ScrimBlack = Color(0xFF000000)

// ——————————————————————————————————————————————————————————————
// 深色（不是生硬反色：主要色在深色下提亮以保证 4.5:1）
// ——————————————————————————————————————————————————————————————

val DarkBackground = Color(0xFF121114)
val DarkSurface = Color(0xFF1A181C)
val DarkSurfaceVariant = Color(0xFF242227)
val DarkOnSurface = Color(0xFFF4F1F3)
val DarkOnBackground = Color(0xFFF4F1F3)
val DarkOnSurfaceVariant = Color(0xFFA79FA5)
val DarkOutline = Color(0xFF332F35)
val DarkOutlineVariant = Color(0xFF2B282D)
val DarkPrimary = Color(0xFFE89BB0)
val DarkOnPrimary = Color(0xFF451024)
val DarkPrimaryContainer = Color(0xFF4A2130)
val DarkOnPrimaryContainer = Color(0xFFFBD9E2)
val DarkSuccess = Color(0xFF4FC79B)
val DarkSuccessContainer = Color(0xFF13352A)
val DarkError = Color(0xFFF0786B)
val DarkErrorContainer = Color(0xFF47211D)
val DarkAccent = Color(0xFF8FB6D0)
val DarkAccentContainer = Color(0xFF2A3F4C)
val DarkUnknown = Color(0xFF9A9AA4)
val DarkUnknownContainer = Color(0xFF26242A)
val DarkOnSecondary = Color(0xFF1B2A33)
val DarkOnSecondaryContainer = Color(0xFFCFE4F0)
val DarkOnError = Color(0xFF3A0F0D)
val DarkOnErrorContainer = Color(0xFFF6C3BC)

/** 深色下 success 前景色（派生值） */
val DarkOnSuccess = Color(0xFF06281C)

/** 深色下 success 容器前景色（派生值） */
val DarkOnSuccessContainer = Color(0xFFC9F0DE)

/** 深色下 unknown 前景色（派生值） */
val DarkOnUnknown = Color(0xFF10141B)

/** 深色下 unknown 容器前景色（派生值） */
val DarkOnUnknownContainer = Color(0xFFC7C2C7)
