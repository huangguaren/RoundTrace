package com.roundtrace.app.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 设计 Token —— 字体与排版。
 *
 * 依据 `docs/DESIGN_SPEC.md` §2：
 *  页面标题 24sp/32 Medium · 区块标题 18sp/26 Medium · 卡片主文字 16sp/24 Medium ·
 *  正文 15sp/22 Normal · 辅助说明 13sp/18 Normal · 座位数字 20sp/24 Medium · 标签徽标 12sp/16 Medium
 *
 * 硬约束：字号与行高**一律用 sp**，支持系统字体放大 1.3x 不裁切（禁止 dp 写字号）。
 */
val RoundTraceTypography = Typography(
    // 页面标题 24sp / 32sp / Medium
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    // 座位数字 / 大号强调 20sp / 24sp / Medium
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 24.sp
    ),
    // 区块标题 18sp / 26sp / Medium
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 26.sp
    ),
    // 卡片主文字 / 正文重点 16sp / 24sp / Medium
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    // 正文 16sp / 24sp / Normal（大号正文）
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    // 正文 15sp / 22sp / Normal
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    // 辅助说明 13sp / 18sp / Normal
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    // 按钮 / 强调正文 15sp / 20sp / Medium
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    // 标签 / 徽标 12sp / 16sp / Medium
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    // 极小辅助文字 11sp / 14sp / Normal
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
)
