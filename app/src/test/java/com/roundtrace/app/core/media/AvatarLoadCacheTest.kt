package com.roundtrace.app.core.media

import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.core.ui.avatar.AvatarBitmapCache
import com.roundtrace.app.core.ui.avatar.AvatarCacheKey
import com.roundtrace.app.core.ui.avatar.loadAvatar
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

/** Native BitmapFactory，验证后台加载、源失效与缓存预算，不用 Legacy 的宽松假解码。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarLoadCacheTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files = mutableListOf<File>()

    @Before
    fun before() = AvatarBitmapCache.clear()

    @After
    fun cleanup() {
        AvatarBitmapCache.clear()
        files.forEach { it.delete() }
    }

    private fun image(width: Int, height: Int): File {
        val file = File.createTempFile("avatar-cache-", ".png", context.cacheDir)
        files += file
        writeImage(file, width, height)
        return file
    }

    private fun writeImage(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun key(id: String) = AvatarCacheKey("content://test/$id", 512, "version-1")

    @Test
    fun sameSourceAndSizeReusesBitmapButDifferentSizesDoNot() = runBlocking {
        val uri = Uri.fromFile(image(2048, 1024)).toString()
        val small = loadAvatar(context, uri, 128)
        assertNotNull(small)
        assertSame(small, loadAvatar(context, uri, 128))

        val large = loadAvatar(context, uri, 512)
        assertNotNull(large)
        assertNotSame(small, large)
        assertEquals(256, small!!.width)
        assertEquals(1024, large!!.width)
    }

    @Test
    fun deletingSourceInvalidatesCachedBitmap() = runBlocking {
        val file = image(64, 64)
        val uri = Uri.fromFile(file).toString()
        assertNotNull(loadAvatar(context, uri, 128))
        assertTrue(AvatarBitmapCache.sizeBytes > 0)
        assertTrue(file.delete())

        assertNull(loadAvatar(context, uri, 128))
        assertEquals(0L, AvatarBitmapCache.sizeBytes)
    }

    @Test
    fun replacingSourceAtSameUriDoesNotReuseOldBitmap() = runBlocking {
        val file = image(64, 64)
        val uri = Uri.fromFile(file).toString()
        val old = loadAvatar(context, uri, 128)
        assertNotNull(old)
        val modified = file.lastModified()
        writeImage(file, 64, 64)
        assertTrue(file.setLastModified(modified + 2000))

        val replacement = loadAvatar(context, uri, 128)
        assertNotNull(replacement)
        assertNotSame(old, replacement)
    }

    @Test
    fun cacheEvictsByAllocatedBytesAndDoesNotRecycleDisplayedBitmap() {
        val first = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        AvatarBitmapCache.put(key("first"), first)
        AvatarBitmapCache.put(key("second"), Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888))
        AvatarBitmapCache.put(key("third"), Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888))

        assertNull(AvatarBitmapCache.get(key("first")))
        assertNotNull(AvatarBitmapCache.get(key("third")))
        assertTrue(AvatarBitmapCache.sizeBytes <= AvatarBitmapCache.MAX_BYTES)
        assertTrue("淘汰缓存不能回收仍在界面中的位图", !first.isRecycled)
    }

    @Test
    fun individuallyOversizedBitmapIsNotCached() {
        AvatarBitmapCache.put(key("huge"), Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888))

        assertNull(AvatarBitmapCache.get(key("huge")))
        assertEquals(0L, AvatarBitmapCache.sizeBytes)
    }

    @Test
    fun recycledBitmapIsNotReturnedFromCache() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        AvatarBitmapCache.put(key("recycled"), bitmap)
        bitmap.recycle()

        assertNull(AvatarBitmapCache.get(key("recycled")))
        assertEquals(0L, AvatarBitmapCache.sizeBytes)
    }

    @Test
    fun entryCountIsBoundedAndReadsRefreshLruOrder() {
        (0 until 16).forEach { index ->
            AvatarBitmapCache.put(key("$index"), Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
        }
        assertNotNull(AvatarBitmapCache.get(key("0")))
        AvatarBitmapCache.put(key("16"), Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))

        assertNotNull(AvatarBitmapCache.get(key("0")))
        assertNull(AvatarBitmapCache.get(key("1")))
    }

    @Test
    fun concurrentLoadsRemainReadableAndWithinCacheBudget() = runBlocking {
        val uri = Uri.fromFile(image(512, 256)).toString()
        val results = (0 until 12).map { async { loadAvatar(context, uri, 128) } }.awaitAll()

        results.forEach { bitmap ->
            assertNotNull(bitmap)
            assertEquals(256, bitmap!!.width)
            assertEquals(128, bitmap.height)
        }
        assertTrue(AvatarBitmapCache.sizeBytes <= AvatarBitmapCache.MAX_BYTES)
    }

    private class AvatarProvider(private val file: File) : ContentProvider() {
        var readable = true
        var providesVersion = true
        val accessThreads = mutableListOf<Thread>()

        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String = "image/png"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            accessThreads += Thread.currentThread()
            if (!readable) throw SecurityException("read permission revoked")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            accessThreads += Thread.currentThread()
            return MatrixCursor(arrayOf("last_modified", "_size")).apply {
                addRow(arrayOf(if (providesVersion) file.lastModified() else 0L, file.length()))
            }
        }
    }

    private fun provider(file: File): AvatarProvider = AvatarProvider(file).also {
        it.attachInfo(context, ProviderInfo().apply { authority = "avatar-test" })
        ShadowContentResolver.registerProviderInternal("avatar-test", it)
    }

    @Test
    fun contentSourceReadsAndMetadataQueriesRunOffCallingThread() = runBlocking {
        val provider = provider(image(64, 64))
        val callingThread = Thread.currentThread()
        val uri = "content://avatar-test/image"
        assertNotNull(loadAvatar(context, uri, 128))

        assertTrue(provider.accessThreads.isNotEmpty())
        assertTrue(provider.accessThreads.all { it != callingThread })
    }

    @Test
    fun revokedContentPermissionDoesNotExposeCachedImage() = runBlocking {
        val provider = provider(image(64, 64))
        val uri = "content://avatar-test/image"
        assertNotNull(loadAvatar(context, uri, 128))
        assertTrue(AvatarBitmapCache.sizeBytes > 0)
        provider.readable = false

        assertNull(loadAvatar(context, uri, 128))
        assertEquals(0L, AvatarBitmapCache.sizeBytes)
    }

    @Test
    fun sourceWithoutReliableVersionIsDecodedWithoutReusingOldImage() = runBlocking {
        val provider = provider(image(64, 64))
        provider.providesVersion = false
        val uri = "content://avatar-test/image"
        val first = loadAvatar(context, uri, 128)
        assertNotNull(first)
        val second = loadAvatar(context, uri, 128)
        assertNotNull(second)
        assertNotSame(first, second)
        assertEquals(0L, AvatarBitmapCache.sizeBytes)
    }
}
