package com.roundtrace.app.core.database

import androidx.room.TypeConverter

/**
 * Room 类型转换器（TASKS.md T30②）。
 *
 * M2 的 6 张表全部使用基本类型（String / Int / Long / Boolean / 可空基本类型），
 * 因此**当前没有任何字段需要转换器**。这里预置 `List<Int>` 与 `Map<String,String>`
 * 的转换能力，供 P1（例如把 `analysis_reports.contentJson` 拆成结构化列）使用，
 * 避免届时再改 `@Database` 注解。
 *
 * 约定：`List<Int>` 用逗号分隔字符串；`Map` 用 `k=v;k=v`。**空集合与 null 严格区分**。
 */
object RoundTraceConverters {

    private const val LIST_SEPARATOR = ","
    private const val MAP_ENTRY_SEPARATOR = ";"
    private const val MAP_KV_SEPARATOR = "="

    @TypeConverter
    @JvmStatic
    fun fromIntList(value: List<Int>?): String? {
        if (value == null) return null
        return value.joinToString(LIST_SEPARATOR) { it.toString() }
    }

    @TypeConverter
    @JvmStatic
    fun toIntList(value: String?): List<Int>? {
        if (value == null) return null
        if (value.isEmpty()) return emptyList()
        return value.split(LIST_SEPARATOR).mapNotNull { it.trim().toIntOrNull() }
    }

    @TypeConverter
    @JvmStatic
    fun fromStringMap(value: Map<String, String>?): String? {
        if (value == null) return null
        return value.entries.joinToString(MAP_ENTRY_SEPARATOR) { "${it.key}$MAP_KV_SEPARATOR${it.value}" }
    }

    @TypeConverter
    @JvmStatic
    fun toStringMap(value: String?): Map<String, String>? {
        if (value == null) return null
        if (value.isEmpty()) return emptyMap()
        return value.split(MAP_ENTRY_SEPARATOR).mapNotNull { entry ->
            val index = entry.indexOf(MAP_KV_SEPARATOR)
            if (index <= 0) return@mapNotNull null
            entry.substring(0, index) to entry.substring(index + 1)
        }.toMap()
    }
}
