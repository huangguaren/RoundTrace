package com.roundtrace.app.core.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.core.ui.avatar.decodeSampledAvatar
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 真正打开 PNG 并经过 bounds + pixels 两次解码，覆盖仅测采样公式遗漏的边界。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarDecodeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files = mutableListOf<File>()

    @After
    fun cleanup() {
        files.forEach { it.delete() }
    }

    private fun image(width: Int, height: Int): File {
        val file = File.createTempFile("avatar-decode-", ".png", context.cacheDir)
        files += file
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { stream ->
            assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        source.recycle()
        return file
    }

    @Test
    fun realPngIsDecodedAfterReadingBounds() {
        val file = image(80, 60)
        val bitmap = decodeSampledAvatar(context, Uri.fromFile(file).toString(), 128)

        assertNotNull("bounds 解码返回 null 是正常行为，不能因此拒绝有效图片", bitmap)
        assertEquals(80, bitmap!!.width)
        assertEquals(60, bitmap.height)
    }

    @Test
    fun largePngIsSampledBeforePixelDecode() {
        val file = image(2048, 1024)
        val bitmap = decodeSampledAvatar(context, Uri.fromFile(file).toString(), 128)

        assertNotNull(bitmap)
        assertEquals(256, bitmap!!.width)
        assertEquals(128, bitmap.height)
        assertTrue(bitmap.width.toLong() * bitmap.height <= AvatarSampling.DEFAULT_MAX_PIXELS)
    }

    @Test
    fun unreadableOrDeletedSourceFallsBackToPlaceholder() {
        val file = image(80, 60)
        assertTrue(file.delete())

        assertNull(decodeSampledAvatar(context, Uri.fromFile(file).toString(), 128))
    }

    @Test
    fun corruptImageFallsBackToPlaceholder() {
        val file = File.createTempFile("avatar-corrupt-", ".png", context.cacheDir)
        files += file
        file.writeText("this is not an image")

        assertNull(decodeSampledAvatar(context, Uri.fromFile(file).toString(), 128))
    }
}
