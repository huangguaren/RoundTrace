package com.roundtrace.app.core.data

/**
 * 「一次性投影刷新」的两个判断点（migration-plan §17.1-S3-5）。
 *
 * ## 为什么要把这两行抽出来
 *
 * 原来「`isComplete` → 写版本标记」是 `AppContainer.init` 里的一段不可测代码：
 * 把它改成「不管 `isComplete` 都写标记」，全量单测一条都不会红，而后果是
 * **刷新失败一次就永远不再重试**——老对局的首页卡会永久停在旧结论。
 *
 * 抽成纯函数后，三条语义各有名字、都能被直接断言：
 * 已标记 → 跳过；全成功 → 写；任一失败 → 不写（下次启动重试）。
 */
object ProjectionRefreshCoordinator {

    /**
     * 是否还需要跑一次全量重算。
     *
     * @param current 当前已写入的投影顺序模型版本（0 = 从未写过）
     * @param required 代码要求的版本
     */
    fun shouldRefresh(current: Int, required: Int): Boolean = current < required

    /**
     * 是否允许写版本标记。
     *
     * **只有「全成功」且「尚未标记」才写。** 任一局失败就不写 → 下次启动自动重试；
     * 写早了等于把「这局还没被重算」这件事永久遗忘。
     *
     * @param result 本轮刷新结果
     * @param alreadyMarked 调用前是否已经标记过（已标记则无需再写）
     */
    fun shouldWriteProjectionMarker(
        result: ProjectionRefreshResult,
        alreadyMarked: Boolean
    ): Boolean = !alreadyMarked && result.isComplete
}
