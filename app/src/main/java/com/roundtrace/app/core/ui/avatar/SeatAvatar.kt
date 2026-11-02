package com.roundtrace.app.core.ui.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.roundtrace.app.core.media.AvatarSampling
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 座位头像（RT-009 / RT-010）。
 *
 * ## 为什么抽这一个组件
 * 审计发现同一段「在 `remember` 里同步 `BitmapFactory.decodeStream`」的代码
 * 在 `SeatPicker`、`VoteRecorder`、`CreateMatchScreen` **各写了一份**：
 * - **主线程 IO**：解码发生在组合期间，滚动列表时会掉帧；
 * - **全分辨率解码**：不设 `inSampleSize`，6000px 原图会按原尺寸进内存（可 OOM）；
 * - **无缓存**：同一个头像在多个座位/多个页面反复解码。
 *
 * 现在三处共用本组件：**后台线程 + 采样解码 + LRU 缓存 + 失败占位**。
 *
 * ## 授权失效怎么办
 * 选图改用 `OpenDocument`（RT-009）并申请持久读取授权；但用户仍可能删除源文件
 * 或在系统里撤销授权。此时解码返回 null → 显示 [fallback] 占位，**不崩溃、不卡 UI**。
 */
@Composable
fun SeatAvatarImage(
    uri: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxSizePx: Int = AvatarSampling.DEFAULT_MAX_SIZE_PX,
    onLoadStateChange: (loaded: Boolean) -> Unit = {},
    fallback: @Composable () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // key 重建状态，切换 URI 的第一帧即显示新占位，不短暂沿用上一位玩家的头像。
    val bitmap by key(uri, maxSizePx) {
        produceState<Bitmap?>(initialValue = null) {
            if (!uri.isNullOrBlank()) {
                value = loadAvatar(context.applicationContext, uri, maxSizePx)
            }
        }
    }

    // 供调用方决定「叠加内容」（如座位号角标）是否画在头像上
    val loaded = bitmap != null
    val notifyLoadState by rememberUpdatedState(onLoadStateChange)
    androidx.compose.runtime.LaunchedEffect(uri, maxSizePx, loaded) { notifyLoadState(loaded) }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        fallback()
    }
}

/**
 * 采样解码一张头像。**必须在 IO 线程调用**（[SeatAvatarImage] 已保证）。
 *
 * 两段式解码：先只读 bounds（`inJustDecodeBounds`）拿到原图尺寸，算出 `inSampleSize`，
 * 再真正解码。这样大图不会按原尺寸进内存。
 */
fun decodeSampledAvatar(context: Context, uri: String, maxSizePx: Int): Bitmap? {
    val source = readAvatarSource(context, uri) ?: return null
    return decodePixels(context, source, maxSizePx)
}

private data class AvatarSource(val uri: Uri, val width: Int, val height: Int, val version: String?)

private fun readAvatarSource(context: Context, uri: String): AvatarSource? {
    val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val opened = runCatching {
        context.contentResolver.openInputStream(parsed)?.use { stream ->
            // Android 在 inJustDecodeBounds 模式下正常返回 null；成功与否由宽高判断。
            // use 的返回值必须独立表示「流读取成功」，不能复用 decodeStream 的位图返回值。
            BitmapFactory.decodeStream(stream, null, bounds)
            true
        }
    }.getOrDefault(false)
    if (opened != true) return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return AvatarSource(parsed, bounds.outWidth, bounds.outHeight, sourceVersion(context, parsed))
}

/** 未提供修改时间的来源不复用缓存，避免同 URI 的图片被替换后持续显示旧内容。 */
private fun sourceVersion(context: Context, uri: Uri): String? = runCatching {
    when (uri.scheme) {
        "file" -> {
            val file = File(uri.path ?: return@runCatching null)
            file.lastModified().takeIf { it > 0 }?.let { "$it:${file.length()}" }
        }
        "content" -> context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (modifiedColumn < 0 || cursor.isNull(modifiedColumn)) return@use null
            val modified = cursor.getLong(modifiedColumn)
            if (modified <= 0) return@use null
            val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn) else -1
            "$modified:$size"
        }
        else -> null
    }
}.getOrNull()

private fun decodePixels(context: Context, source: AvatarSource, maxSizePx: Int): Bitmap? {
    val size = maxSizePx.coerceIn(1, AvatarSampling.DEFAULT_MAX_SIZE_PX)
    val options = BitmapFactory.Options().apply {
        inSampleSize = AvatarSampling.calculateSampleSizeWithinBudget(
            srcWidth = source.width,
            srcHeight = source.height,
            reqWidth = size,
            reqHeight = size
        )
    }
    return runCatching {
        context.contentResolver.openInputStream(source.uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }.getOrNull()
}

private val avatarDecodeSlots = Semaphore(2)

/** 可读性检查、元数据查询、缓存及解码都在 IO 线程；限制同时解码的数量。 */
internal suspend fun loadAvatar(context: Context, uri: String, maxSizePx: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        avatarDecodeSlots.withPermit {
            val source = readAvatarSource(context, uri)
            if (source == null) {
                AvatarBitmapCache.removeUri(uri)
                return@withPermit null
            }
            val size = maxSizePx.coerceIn(1, AvatarSampling.DEFAULT_MAX_SIZE_PX)
            val cacheKey = source.version?.let {
                AvatarCacheKey(uri, size, "${source.width}x${source.height}:$it")
            }
            // 先检查源是否仍可读，再访问缓存；撤权、删图均不能靠旧缓存假装成功。
            if (cacheKey == null) AvatarBitmapCache.removeUri(uri)
            val cached = cacheKey?.let(AvatarBitmapCache::get)
            cached ?: decodePixels(context, source, size)?.also { bitmap ->
                if (cacheKey != null) AvatarBitmapCache.put(cacheKey, bitmap)
            }
        }
    }

internal data class AvatarCacheKey(val uri: String, val maxSizePx: Int, val sourceVersion: String)

/**
 * 头像位图缓存（LRU，最多 16 张，且实际分配字节数最多 8 MiB）。
 *
 * 座位网格 + 投票面板会同时显示同一批头像，没有缓存就会反复解码同一张图。
 * 键包含请求尺寸与来源版本；淘汰只移除缓存引用，不 recycle 仍可能显示中的位图。
 */
internal object AvatarBitmapCache {
    private const val MAX_ENTRIES = 16
    const val MAX_BYTES: Long = 8L * 1024 * 1024

    private data class Entry(val bitmap: Bitmap, val bytes: Long)
    private val cache = LinkedHashMap<AvatarCacheKey, Entry>(MAX_ENTRIES, 0.75f, true)
    private var bytes = 0L

    val sizeBytes: Long get() = synchronized(cache) { bytes }

    fun get(key: AvatarCacheKey): Bitmap? = synchronized(cache) {
        val entry = cache[key] ?: return@synchronized null
        if (entry.bitmap.isRecycled) {
            cache.remove(key)
            bytes -= entry.bytes
            null
        } else entry.bitmap
    }

    fun put(key: AvatarCacheKey, bitmap: Bitmap) = synchronized(cache) {
        cache.remove(key)?.let { bytes -= it.bytes }
        if (bitmap.isRecycled) return@synchronized
        val allocation = bitmap.allocationByteCount.toLong()
        if (allocation > MAX_BYTES) return@synchronized
        cache[key] = Entry(bitmap, allocation)
        bytes += allocation
        val iterator = cache.entries.iterator()
        while ((cache.size > MAX_ENTRIES || bytes > MAX_BYTES) && iterator.hasNext()) {
            bytes -= iterator.next().value.bytes
            iterator.remove()
        }
    }

    fun removeUri(uri: String) = synchronized(cache) {
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.uri == uri) {
                bytes -= entry.value.bytes
                iterator.remove()
            }
        }
    }

    fun clear() {
        synchronized(cache) {
            cache.clear()
            bytes = 0L
        }
    }
}
