package com.roundtrace.app.core.model

/**
 * 领域基础 ID 类型。
 *
 * - [MatchId] / [EventId] 用 `@JvmInline value class` 包裹字符串，避免把两个不同实体的 id 互相传错。
 * - [SeatId] 只是 `Int` 的别名：座位号 1..10，非法值由**规则层**拒绝（不在类型层拒绝，
 *   否则反序列化坏数据会直接抛异常，违背「事件先保存再校验」的设计）。
 *
 * 纯 Kotlin：本文件禁止 import android.* / androidx.*。
 */
@JvmInline
value class MatchId(val raw: String) {
    override fun toString(): String = raw
}

@JvmInline
value class EventId(val raw: String) {
    override fun toString(): String = raw
}

/** 座位号，1..10（上限由 ruleset 决定，阿瓦隆标准为 5..10 人）。 */
typealias SeatId = Int
