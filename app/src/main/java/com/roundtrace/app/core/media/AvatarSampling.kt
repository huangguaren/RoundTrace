package com.roundtrace.app.core.media

/**
 * 头像解码的采样计算（**纯 Kotlin，无 Android 依赖**，因此可在 JVM 单测里直接验证）。
 *
 * 为什么要采样：用户选的头像可能是 6000×4000 的原图。`BitmapFactory.decodeStream`
 * 若不设 `inSampleSize`，会按原图尺寸解码——一个 24MP 图约需 96MB 内存，
 * 在记录页滚动时足以触发 OOM。本文件只负责「该按多少倍降采样」的纯计算，
 * 真正的解码在 `core/ui/avatar/SeatAvatar.kt`（需要 Android 的 Bitmap）。
 */
object AvatarSampling {

    /** 头像显示的最大边长（px）。座位格子实际显示远小于此，留足余量避免糊。 */
    const val DEFAULT_MAX_SIZE_PX: Int = 512

    /** 解码后允许的最大像素数（约 100 万 ≈ 1024×1024），超过则继续降采样。 */
    const val DEFAULT_MAX_PIXELS: Long = 1_048_576L

    /**
     * 计算 `BitmapFactory.Options.inSampleSize`。
     *
     * 规则：样本尺寸只能是 2 的幂，逐次折半，直到「按该样本解码后的宽高都 ≥ 目标尺寸」为止。
     * - 原图已小于目标 → 返回 1（不放大、不降采样）；
     * - 输入非法（宽高 ≤ 0）→ 返回 1，交给解码器自己报错，不在这里抛。
     */
    fun calculateInSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        if (srcWidth <= 0 || srcHeight <= 0) return 1
        if (reqWidth <= 0 || reqHeight <= 0) return 1

        var sampleSize = 1
        val halfWidth = srcWidth / 2
        val halfHeight = srcHeight / 2
        while (halfWidth / sampleSize >= reqWidth && halfHeight / sampleSize >= reqHeight) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * 在采样之外再加一道**像素预算**兜底。
     *
     * 极端长图（例如 4000×3000 的全景图）即使按目标边长采样，仍可能超出像素预算，
     * 这时再折半一次，直到落进预算内。
     *
     * 注意：预算检查**不能**要求 `sample > 1` 才做——像 1200×900（108 万像素）
     * 这种「按目标尺寸不需要降采样、但仍略超预算」的图，正是最容易被漏掉的一档。
     */
    fun calculateSampleSizeWithinBudget(
        srcWidth: Int,
        srcHeight: Int,
        reqWidth: Int,
        reqHeight: Int,
        maxPixels: Long = DEFAULT_MAX_PIXELS
    ): Int {
        var sample = calculateInSampleSize(srcWidth, srcHeight, reqWidth, reqHeight)
        // 解码器会向上取整；不能把窄边截成 0，漏掉极端长图的内存预算。
        while (exceedsPixelBudget(
                sampledDimension(srcWidth, sample),
                sampledDimension(srcHeight, sample),
                maxPixels.coerceAtLeast(1L)
            )
        ) {
            val next = sample * 2
            if (next <= sample) break // 溢出保护
            sample = next
        }
        return sample
    }

    private fun sampledDimension(size: Int, sample: Int): Int =
        if (size <= 0) 0 else ((size.toLong() + sample - 1L) / sample).toInt()

    /** 解码后的像素数是否超出预算。 */
    fun exceedsPixelBudget(width: Int, height: Int, maxPixels: Long): Boolean {
        if (width <= 0 || height <= 0) return false
        return width.toLong() * height.toLong() > maxPixels
    }
}
