package com.roundtrace.app.core.model

/**
 * 座位参与人（ARCHITECTURE §2.2）。
 *
 * @param displayName null = 未命名，UI 显示「3 号」
 * @param colorKey 头像/标签色键，可为空
 * @param layoutX 座位在自由画布上的 x 坐标（0..1 归一化），-1 = 默认网格位置
 * @param layoutY 座位在自由画布上的 y 坐标（0..1 归一化），-1 = 默认网格位置
 * @param avatarUri 头像 URI 字符串（content:// 或文件路径），null = 无头像
 */
data class Participant(
    val seat: SeatId,
    val displayName: String? = null,
    val colorKey: String? = null,
    val layoutX: Float = -1f,
    val layoutY: Float = -1f,
    val avatarUri: String? = null
) {
    /** UI 展示名：有昵称用昵称，否则用「N 号」。 */
    fun labelOrSeatLabel(): String =
        displayName?.takeIf { it.isNotBlank() } ?: "$seat 号"

    /** 是否有自定义拖拽位置。 */
    val hasCustomLayout: Boolean get() = layoutX >= 0f && layoutY >= 0f
}
