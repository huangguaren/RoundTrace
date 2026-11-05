package com.roundtrace.app.core.replay

import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventType
import com.roundtrace.app.core.model.SeatId

/**
 * 时间线 UI 中间模型（ARCHITECTURE §8.6）。
 *
 * 由 [TimelineBuilder] 从事件生成，**记录页与复盘页共用**，避免两处各写一套渲染逻辑。
 *
 * @param occurredOrder 事件在游戏内的顺序（唯一排序键）
 * @param phaseLabel 阶段标签，如「第 3 任务」「准备阶段」
 * @param title 事件摘要（如「队长 3 号提出队伍：2 / 3 / 4 / 6」）
 * @param detail 明细（次级行，如「第 2 任务 · 第 1 次提案」）
 * @param unrecordedFields 未记录字段的**展示标签**列表；UI 必须渲染为「—— 未记录」
 * @param sourceEventIds 来源事件 id（1 条 entry 至少 1 个）
 * @param actorSeat 结构化行为人座位（UX-P2-01 / Phase A）。仅当事件明确存在单一行为人时填值：
 *        队长（任务开始 / 组队提案）、发言者、私人笔记观察者、刺客；
 *        汇总投票、组队决议、任务结果、终局等**无单一行为人**的事件保持 `null`，绝不臆造行为人。
 *        默认 `null`，以兼容记录页等不传 `seatIdentity` 的调用点。
 */
data class TimelineEntry(
    val occurredOrder: Int,
    val phaseLabel: String,
    val title: String,
    val detail: String,
    val unrecordedFields: List<String> = emptyList(),
    val sourceEventIds: List<EventId> = emptyList(),
    val eventType: EventType,
    val actorSeat: SeatId? = null
) {

    /** 是否存在未记录字段（UI 据此决定是否显示「—— 未记录」行）。 */
    val hasUnrecordedFields: Boolean
        get() = unrecordedFields.isNotEmpty()
}
