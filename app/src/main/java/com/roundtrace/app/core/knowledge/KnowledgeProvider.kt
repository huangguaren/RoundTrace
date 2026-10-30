package com.roundtrace.app.core.knowledge

import com.roundtrace.app.core.model.GamePosition
import com.roundtrace.app.core.model.KnowledgeSnapshot
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.ReconstructedMatch
import com.roundtrace.app.core.model.SeatId

/**
 * 视角引擎接口（ARCHITECTURE §3 / TASKS.md T51）。
 *
 * **纯函数**：无 `suspend`、无 IO、无 Android 依赖。
 * 因此 K01–K10 可以全部落在 JVM 单测里（TASKS.md §8.2）。
 *
 * 三道闸门（实现必须同时满足，缺一不可）：
 * 1. **时间边界**：只用 `occurredOrder <= at.occurredOrder` 的事件；
 * 2. **可见性边界**：`PRIVATE_OBSERVER` 事件只对本人可见；
 * 3. **知识来源边界**：每条私有事实必须带 `KnowledgeBasis`，
 *    能区分「开局规则给的」与「按规则重建的初始信息」。
 */
interface KnowledgeProvider {

    /**
     * 构建某个时间点、某个视角下的信息快照。
     *
     * @param match 重放输入（`events` 已过滤 retracted）
     * @param observer 观察座位；`PUBLIC` / `GOD_AFTER_REVEAL` 模式可为 `null`
     * @param at 时间边界（硬边界，不可越过）
     * @param mode 视角
     * @throws IllegalStateException `GOD_AFTER_REVEAL` 但 `match.revealedAt == null`
     */
    fun snapshot(
        match: ReconstructedMatch,
        observer: SeatId?,
        at: GamePosition,
        mode: Perspective
    ): KnowledgeSnapshot
}
