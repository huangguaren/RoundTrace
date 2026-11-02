package com.roundtrace.app.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 头像采样计算的 JVM 单测（RT-010）。
 *
 * 这部分是纯 Kotlin，所以能在 JVM 上直接验证——
 * 不需要设备，也不需要真的解码一张图。
 */
class AvatarSamplingTest {

    @Test
    fun `原图小于目标时不降采样`() {
        // 200×200 头像，显示目标 512 → 不需要降采样
        assertEquals(1, AvatarSampling.calculateInSampleSize(200, 200, 512, 512))
    }

    @Test
    fun `原图刚好等于目标时不降采样`() {
        assertEquals(1, AvatarSampling.calculateInSampleSize(512, 512, 512, 512))
    }

    @Test
    fun `两倍于目标时降一级`() {
        // 1024×1024 → 折半后 512 仍 ≥ 512 → sample = 2
        assertEquals(2, AvatarSampling.calculateInSampleSize(1024, 1024, 512, 512))
    }

    @Test
    fun `六千像素大图按二的幂降采样`() {
        // 6000×4000：halfWidth=3000, halfHeight=2000
        // sample=1 → 3000/1≥512 且 2000/1≥512 → 2
        // sample=2 → 1500/2=750≥512 且 1000/2=500<512 → 停在 4
        val sample = AvatarSampling.calculateInSampleSize(6000, 4000, 512, 512)
        assertEquals(4, sample)
        // 必须是 2 的幂（BitmapFactory 只接受 2 的幂）
        assertEquals(0, sample and (sample - 1))
    }

    @Test
    fun `非法输入返回一而不是抛异常`() {
        assertEquals(1, AvatarSampling.calculateInSampleSize(0, 100, 512, 512))
        assertEquals(1, AvatarSampling.calculateInSampleSize(100, -1, 512, 512))
        assertEquals(1, AvatarSampling.calculateInSampleSize(100, 100, 0, 512))
        assertEquals(1, AvatarSampling.calculateInSampleSize(100, 100, 512, -5))
    }

    @Test
    fun `极端长图在采样后仍超出像素预算时继续降级`() {
        // 20000×20000：按目标 512 采样到 8 → 2500×2500 = 625 万像素，超出 100 万预算
        val sample = AvatarSampling.calculateSampleSizeWithinBudget(
            srcWidth = 20000,
            srcHeight = 20000,
            reqWidth = 512,
            reqHeight = 512,
            maxPixels = AvatarSampling.DEFAULT_MAX_PIXELS
        )
        // 必须继续降级，直到落进预算
        assertFalse(
            "降采样后仍超预算：sample=$sample",
            AvatarSampling.exceedsPixelBudget(
                20000 / sample,
                20000 / sample,
                AvatarSampling.DEFAULT_MAX_PIXELS
            )
        )
    }

    @Test
    fun `略超像素预算的照片会再降一级`() {
        // 1200×900 = 108 万像素，**略超** 100 万像素预算（1,048,576）→ 再降一级
        val sample = AvatarSampling.calculateSampleSizeWithinBudget(1200, 900, 512, 512)
        assertEquals(2, sample)
        assertFalse(
            AvatarSampling.exceedsPixelBudget(600, 450, AvatarSampling.DEFAULT_MAX_PIXELS)
        )
    }

    @Test
    fun `远小于预算的照片不降采样`() {
        // 800×600 = 48 万像素，在预算内；且短边 600 已小于目标 512 的两倍需求 → 保持原尺寸
        val sample = AvatarSampling.calculateSampleSizeWithinBudget(800, 600, 512, 512)
        assertEquals(1, sample)
        assertFalse(
            AvatarSampling.exceedsPixelBudget(800, 600, AvatarSampling.DEFAULT_MAX_PIXELS)
        )
    }

    @Test
    fun `像素预算判定边界`() {
        assertTrue(AvatarSampling.exceedsPixelBudget(1024, 1025, 1_048_576L))
        assertFalse(AvatarSampling.exceedsPixelBudget(1024, 1024, 1_048_576L))
        // 宽高非法时不算超预算（解码器会自己报错）
        assertFalse(AvatarSampling.exceedsPixelBudget(0, 100, 10L))
    }

    @Test
    fun `奇数像素尺寸按向上取整检查预算`() {
        // sample=2 实际可能是 1025×1025，不能用整数除法误当 1024×1024。
        assertEquals(4, AvatarSampling.calculateSampleSizeWithinBudget(2049, 2049, 512, 512))
    }

    @Test
    fun `极窄长图不会因短边被截为零而绕过预算`() {
        val sample = AvatarSampling.calculateSampleSizeWithinBudget(Int.MAX_VALUE, 1, 512, 512)
        val width = (Int.MAX_VALUE.toLong() + sample - 1L) / sample
        assertTrue(width <= AvatarSampling.DEFAULT_MAX_PIXELS)
        assertTrue(sample > 1)
    }
}
