package com.roundtrace.app.core.replay

import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.model.PrivateNoteAdded
import com.roundtrace.app.core.model.SeatId

/**
 * 事件可见性策略（RT-005 的唯一出口）。
 *
 * ## 为什么需要它
 *
 * 视角隔离原来散落在三处：`AvalonKnowledgeProvider` 造公开事实时、
 * 造声称标签时、`AvalonEvidenceAnalyzer` 做推理时。
 * 任何一处漏过滤，`PRIVATE_OBSERVER` 的事件就会从某个缝里漏到公开视角——
 * 这类 bug 不会崩，只会安静地把「只有我记得的东西」当成公开历史展示。
 *
 * 所以把「谁能看到哪条事件」收敛成**一个纯函数**，三处共用。
 *
 * ## 三条规则
 *
 * | 视角 | 可见事件 |
 * |---|---|
 * | `PUBLIC` | 仅 `visibility == PUBLIC` |
 * | `OBSERVER_AT_TIME` | `PUBLIC` + **属于该观察者**的 `PRIVATE_OBSERVER` |
 * | `GOD_AFTER_REVEAL` | 全部（含所有私有事件） |
 *
 * ## 与 B31 的关系（必须保留）
 *
 * 本策略只回答「**谁**能看」，不回答「**什么时候**能看」。
 * `PrivateNoteAdded.availableFromOrder`（回填语义：事后补一条可见时间更早的笔记）
 * 仍由 `AvalonKnowledgeProvider` 的时间过滤负责 —— 这里**不参与**时间判断，
 * 也**不得**因为「事件 occurredOrder 晚于 at」就把它剔掉，否则 B31 会被破坏。
 *
 * ## 「无主」的私有事件
 *
 * 若某条 `PRIVATE_OBSERVER` 事件无法确定归属座位（例如未来的新事件类型），
 * 一律**不对任何观察者可见**，只有上帝视角能看。
 * 这是安全侧默认：宁可少显示，也不要把未知归属的私有信息给错人。
 */
object VisibleEventPolicy {

    /** 该事件在指定视角下是否可见。 */
    fun isVisibleTo(
        event: MatchEvent,
        mode: Perspective,
        observerSeat: SeatId?
    ): Boolean = when (mode) {
        Perspective.PUBLIC -> event.visibility == EventVisibility.PUBLIC

        Perspective.OBSERVER_AT_TIME -> when (event.visibility) {
            EventVisibility.PUBLIC -> true
            EventVisibility.PRIVATE_OBSERVER ->
                observerSeat != null && ownerSeatOf(event) == observerSeat
        }

        Perspective.GOD_AFTER_REVEAL -> true
    }

    /** 按视角过滤事件列表，保持入参顺序。 */
    fun forPerspective(
        events: List<MatchEvent>,
        mode: Perspective,
        observerSeat: SeatId?
    ): List<MatchEvent> = events.filter { isVisibleTo(it, mode, observerSeat) }

    /**
     * 私有事件的归属座位（「这条私有信息属于谁」）。
     *
     * 只对能明确归属的事件返回座位；其余返回 `null`（→ 只有上帝视角可见）。
     */
    fun ownerSeatOf(event: MatchEvent): SeatId? = when (event) {
        is PrivateNoteAdded -> event.observerSeat
        // 私人声称：归发言者本人（他记的是「我说过这句话」）
        is PlayerClaimed -> event.speakerSeat
        else -> null
    }
}
