package com.roundtrace.app.core.export

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 导入资源预算；文本 API 与两个文件选择入口使用同一份限制。 */
object ImportLimits {
    const val MAX_BYTES: Int = 2 * 1024 * 1024
    const val MAX_EVENTS: Int = 5_000
    const val MAX_PARTICIPANTS: Int = 10
    const val MAX_TEXT_CHARS: Int = 16_384
    const val MAX_DEPTH: Int = 64

    /** 完整解析之前检查 UTF-8 字节数和容器深度，字符串里的括号不参与计数。 */
    internal fun checkText(text: String) {
        if (text.length > MAX_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            throw ImportLimitException("文件超过 2 MiB，请拆分后导入")
        }
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> {
                    depth += 1
                    if (depth > MAX_DEPTH) throw ImportLimitException("JSON 嵌套超过 $MAX_DEPTH 层")
                }
                '}', ']' -> depth -= 1
            }
        }
    }

    /** 在 DTO 反序列化及列表映射之前检查结构预算，未知扩展字段仍可保留兼容性。 */
    internal fun checkTree(root: JsonElement) {
        val pending = ArrayDeque<JsonElement>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            when (val element = pending.removeLast()) {
                is JsonPrimitive -> if (element.isString && element.content.length > MAX_TEXT_CHARS) {
                    throw ImportLimitException("单段文本超过 $MAX_TEXT_CHARS 字符")
                }
                is JsonArray -> {
                    if (element.size > MAX_EVENTS) throw ImportLimitException("数组条目超过 $MAX_EVENTS 条")
                    pending.addAll(element)
                }
                is JsonObject -> pending.addAll(element.values)
            }
        }
        if (root is JsonObject) {
            for (field in listOf("participants", "roles")) {
                val count = (root[field] as? JsonArray)?.size ?: continue
                if (count > MAX_PARTICIPANTS) {
                    throw ImportLimitException("$field 超过 $MAX_PARTICIPANTS 个座位")
                }
            }
        }
    }
}

class ImportLimitException(message: String) : Exception(message)

/** 调用方在 IO 线程打开文件；最多读取预算加一个探测字节，不依赖文件提供方的大小元数据。 */
object ImportTextReader {
    /** 两个 UI 入口共用此边界：文件打开和读取都在 IO 线程，失败有可展示的结果。 */
    suspend fun readDocument(openInput: () -> InputStream?): ImportTextReadResult = withContext(Dispatchers.IO) {
        try {
            val text = openInput()?.use(::read)
                ?: return@withContext ImportTextReadResult.Rejected(ImportFailureReason.READ_FAILED, "无法打开文件")
            ImportTextReadResult.Text(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImportLimitException) {
            ImportTextReadResult.Rejected(ImportFailureReason.RESOURCE_LIMIT_EXCEEDED, e.message)
        } catch (e: Exception) {
            ImportTextReadResult.Rejected(ImportFailureReason.READ_FAILED, "无法读取所选文件，请重新选择")
        }
    }

    fun read(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var count = 0
        while (true) {
            val read = input.read(buffer, 0, minOf(buffer.size, ImportLimits.MAX_BYTES - count + 1))
            if (read == -1) break
            if (read == 0) continue
            count += read
            if (count > ImportLimits.MAX_BYTES) throw ImportLimitException("文件超过 2 MiB，请拆分后导入")
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

sealed interface ImportTextReadResult {
    data class Text(val value: String) : ImportTextReadResult
    data class Rejected(val reason: ImportFailureReason, val detail: String?) : ImportTextReadResult
}
