package com.roundtrace.app.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 设计 Token —— 主题装配。
 *
 * 硬约束：
 *  1. 全部语义色来自 Color.kt，Composable 内不得出现 `Color(0xFF...)`（D5）。
 *  2. `dynamicColor` 必须为 **false**：本产品有严格的设计契约，禁止动态取色污染
 *     （TASKS.md T07③ / ARCHITECTURE §9.4）。
 *  3. success / unknown / accent 三个语义位 Material 3 没有对应槽位，
 *     通过 `LocalExtendedColors` 暴露，读取方式：`RoundTraceTheme.extendedColors.success`。
 */

/** Material 3 ColorScheme 之外的扩展语义色（成功 / 未知 / 点缀） */
@Immutable
data class ExtendedColors(
    val success: Color,
    val successContainer: Color,
    val onSuccess: Color,
    val onSuccessContainer: Color,
    val error: Color,
    val errorContainer: Color,
    val onError: Color,
    val onErrorContainer: Color,
    val unknown: Color,
    val unknownContainer: Color,
    val onUnknown: Color,
    val onUnknownContainer: Color,
    val accent: Color,
    val accentContainer: Color,
    val onAccent: Color,
    val onAccentContainer: Color
)

/** 浅色扩展语义色 */
val LightExtendedColors = ExtendedColors(
    success = LightSuccess,
    successContainer = LightSuccessContainer,
    onSuccess = LightOnSuccess,
    onSuccessContainer = LightOnSuccessContainer,
    error = LightError,
    errorContainer = LightErrorContainer,
    onError = LightOnError,
    onErrorContainer = LightOnErrorContainer,
    unknown = LightUnknown,
    unknownContainer = LightUnknownContainer,
    onUnknown = LightOnUnknown,
    onUnknownContainer = LightOnUnknownContainer,
    accent = LightAccent,
    accentContainer = LightAccentContainer,
    onAccent = LightOnSecondary,
    onAccentContainer = LightOnSecondaryContainer
)

/** 深色扩展语义色 */
val DarkExtendedColors = ExtendedColors(
    success = DarkSuccess,
    successContainer = DarkSuccessContainer,
    onSuccess = DarkOnSuccess,
    onSuccessContainer = DarkOnSuccessContainer,
    error = DarkError,
    errorContainer = DarkErrorContainer,
    onError = DarkOnError,
    onErrorContainer = DarkOnErrorContainer,
    unknown = DarkUnknown,
    unknownContainer = DarkUnknownContainer,
    onUnknown = DarkOnUnknown,
    onUnknownContainer = DarkOnUnknownContainer,
    accent = DarkAccent,
    accentContainer = DarkAccentContainer,
    onAccent = DarkOnSecondary,
    onAccentContainer = DarkOnSecondaryContainer
)

/** 扩展语义色的 CompositionLocal；未包裹主题时回退到浅色，避免 Crash */
val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

/** 浅色 ColorScheme（逐值对齐 DESIGN_SPEC §1.1） */
private val LightColorScheme: ColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    inversePrimary = LightPrimaryContainer,
    secondary = LightAccent,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightAccentContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightAccent,
    onTertiary = LightOnSecondary,
    tertiaryContainer = LightAccentContainer,
    onTertiaryContainer = LightOnSecondaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = LightPrimary,
    inverseSurface = LightOnSurface,
    inverseOnSurface = LightBackground,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = ScrimBlack
)

/** 深色 ColorScheme（逐值对齐 DESIGN_SPEC §1.2） */
private val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    inversePrimary = DarkPrimaryContainer,
    secondary = DarkAccent,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkAccentContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkAccent,
    onTertiary = DarkOnSecondary,
    tertiaryContainer = DarkAccentContainer,
    onTertiaryContainer = DarkOnSecondaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = DarkPrimary,
    inverseSurface = DarkOnSurface,
    inverseOnSurface = DarkBackground,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = ScrimBlack
)

/**
 * RoundTrace 主题。
 *
 * @param darkTheme 是否使用深色配色，默认跟随系统。
 * @param dynamicColor **必须保持 false**（Android 12+ 动态取色会破坏设计契约，
 *        导致 success/unknown 等语义色与 primary 撞色）。保留参数只为显式声明禁止。
 * @param content 主题内容。
 */
@Composable
fun RoundTraceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // 本产品禁止动态取色：dynamicColor 恒为 false，仅用于在调用点显式表达该约束。
    @Suppress("UNUSED_EXPRESSION")
    dynamicColor

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = RoundTraceTypography,
            shapes = RoundTraceShapes,
            content = content
        )
    }
}
