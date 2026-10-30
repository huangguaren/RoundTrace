package com.roundtrace.app.core.util

import java.time.Clock
import java.util.UUID

/**
 * 可注入的 ID 生成器。
 *
 * 领域层不直接调用 `UUID.randomUUID()`，而是依赖该端口，便于单测产出稳定 ID。
 * 纯 Kotlin（仅依赖 JDK），禁止任何 android.* 依赖。
 */
fun interface IdGenerator {
    /** 生成一个新的唯一 ID 字符串（生产实现为 UUID v4）。 */
    fun newId(): String
}

/** 生产实现：UUID v4。 */
class UuidIdGenerator : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}

/** 固定序列实现：仅供单测使用，产出 `prefix-1`、`prefix-2`… 便于断言。 */
class SequentialIdGenerator(private val prefix: String = "id") : IdGenerator {
    private var counter: Int = 0

    override fun newId(): String {
        counter += 1
        return "$prefix-$counter"
    }
}

/** 固定时刻时钟：单测里替代 `Clock.systemUTC()`，保证 `recordedAt` 可断言。 */
fun fixedClock(epochMillis: Long): Clock = Clock.fixed(
    java.time.Instant.ofEpochMilli(epochMillis),
    java.time.ZoneOffset.UTC
)
