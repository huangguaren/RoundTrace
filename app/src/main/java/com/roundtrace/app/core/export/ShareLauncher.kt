package com.roundtrace.app.core.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 分享与文件选择（TASKS.md T62）。
 *
 * 三条硬约束：
 * 1. **不申请存储权限**：分享走 `FileProvider` + `ACTION_SEND`；导出到用户选择的位置走
 *    `ACTION_CREATE_DOCUMENT`；导入走 `ACTION_OPEN_DOCUMENT`（三者都不需要权限）；
 * 2. **必须写到 `cacheDir/exports/`**：`res/xml/file_paths.xml` 只声明了
 *    `<cache-path name="exports" path="exports/"/>`，写到别处 `getUriForFile` 会抛
 *    `IllegalArgumentException`；
 * 3. 分享前必须让用户知道**包含范围**（由 UI 的确认弹窗负责，本类只产出
 *    [ShareScope] 供文案使用）。
 *
 * 纯函数式设计（除文件写入）：Intent 由本类构造，`startActivity` 交给 Compose 层，
 * 这样 `ActivityResultContracts` 可以直接复用返回的 Intent。
 */
object ShareLauncher {

    /** 导出目录名，必须与 `file_paths.xml` 里的 `<cache-path path="exports/">` 一致。 */
    const val EXPORT_DIR: String = "exports"

    /** FileProvider authority，必须与 Manifest 一致。 */
    const val FILE_PROVIDER_AUTHORITY: String = "com.roundtrace.app.fileprovider"

    /** JSON 的 MIME。 */
    const val MIME_JSON: String = "application/json"

    /** Markdown 的 MIME（用 text/plain，避免部分邮件客户端不认 text/markdown）。 */
    const val MIME_MARKDOWN: String = "text/plain"

    /**
     * 本次分享/导出的内容范围（供 UI 弹窗展示「将包含 / 不含」）。
     */
    data class ShareScope(
        val includeSecrets: Boolean,
        val includePrivateNotes: Boolean
    ) {
        /** 是否含私有信息（决定弹窗文案是「已脱敏」还是「含私有信息」）。 */
        val containsPrivateInfo: Boolean
            get() = includeSecrets || includePrivateNotes
    }

    /** 由导出选项推导分享范围。 */
    fun scopeOf(options: ExportOptions): ShareScope = ShareScope(
        includeSecrets = options.includeSecrets,
        includePrivateNotes = options.includePrivateNotes
    )

    /**
     * 把导出内容写进 `cacheDir/exports/`，返回可分享的文件。
     *
     * 覆盖同名文件：导出文件名带时间戳或对局 id，正常情况下不会冲突；
     * 即使冲突也应当用新内容覆盖旧内容（旧文件是临时缓存）。
     */
    fun writeToCache(
        context: Context,
        fileName: String,
        content: String
    ): File {
        val dir = File(context.cacheDir, EXPORT_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            // 目录建不出来时直接抛，让上层转成可读错误，而不是静默把文件写到别处
            throw IllegalStateException("无法创建导出目录：${dir.absolutePath}")
        }
        val file = File(dir, sanitizeFileName(fileName))
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    /** 用 FileProvider 把缓存文件转成可被其它 App 读取的 Uri。 */
    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(
        context,
        FILE_PROVIDER_AUTHORITY,
        file
    )

    /**
     * 系统分享面板的 Intent（`ACTION_SEND`，单一文件）。
     *
     * `grantUriPermissions` 与 `FLAG_GRANT_READ_URI_PERMISSION` 同时给，
     * 避免部分 ROM 在 chooser 里丢失授权。
     */
    fun shareIntent(
        context: Context,
        file: File,
        mimeType: String,
        subject: String
    ): Intent {
        val uri = uriFor(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, subject).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * 「导出到用户选择的位置」的 Intent。
     *
     * 由 Compose 层用 `ActivityResultContracts.CreateDocument(mimeType).createIntent(...)`
     * 发起；这里额外提供一个裸 `ACTION_CREATE_DOCUMENT` 版本，便于不依赖 Compose 的调用方
     * （以及仪器测试）直接使用。
     */
    fun createDocumentIntent(fileName: String, mimeType: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, sanitizeFileName(fileName))
        }

    /** 「从用户选择的位置导入」的 Intent（不需要任何存储权限）。 */
    fun openDocumentIntent(mimeType: String = MIME_JSON): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            // 部分文件管理器把 .json 识别成 octet-stream，这里放宽一次
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(mimeType, "application/octet-stream"))
        }

    /** 文件名消毒：去掉路径分隔符与非法字符，避免越权写到 `exports/` 之外。 */
    fun sanitizeFileName(raw: String): String {
        val cleaned = raw
            .replace('/', '_')
            .replace('\\', '_')
            .replace(':', '_')
            .replace('\u0000', '_')
            .trim()
        return cleaned.ifBlank { "roundtrace-export" }
    }
}
