package com.roundtrace.app.data

import com.roundtrace.app.core.data.ProjectionRefreshCoordinator
import com.roundtrace.app.core.data.ProjectionRefreshResult
import com.roundtrace.app.core.model.MatchId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F5：给「`isComplete` → 写版本标记」这行判断补测试缝。
 *
 * 原来这段逻辑写死在 `AppContainer.init` 里，没有任何用例能覆盖：
 * 若被改成「不管 `isComplete` 都写标记」，全量单测一条都不会红，
 * 而后果是**刷新失败一次就永久不再重试**——老对局的首页卡会一直停在旧结论。
 *
 * 抽成纯函数后，三条语义各有名字、各自被断言。
 */
class ProjectionMarkerDecisionTest {

    private fun complete() = ProjectionRefreshResult(processed = 3, failed = emptyList())

    private fun partiallyFailed() = ProjectionRefreshResult(
        processed = 2,
        failed = listOf(MatchId("m-broken"))
    )

    @Test
    fun writesMarkerWhenRefreshFullySucceeded() {
        assertTrue(
            "全成功才允许写标记",
            ProjectionRefreshCoordinator.shouldWriteProjectionMarker(
                result = complete(),
                alreadyMarked = false
            )
        )
    }

    @Test
    fun doesNotWriteMarkerWhenAnyMatchFailed() {
        assertFalse(
            "任一局失败就**不写**标记 → 下次启动重试（写早了等于永久放弃那局）",
            ProjectionRefreshCoordinator.shouldWriteProjectionMarker(
                result = partiallyFailed(),
                alreadyMarked = false
            )
        )
    }

    @Test
    fun skipsWhenAlreadyMarked() {
        assertFalse(
            "已标记过就不必再写（幂等，避免每次启动重复写 DataStore）",
            ProjectionRefreshCoordinator.shouldWriteProjectionMarker(
                result = complete(),
                alreadyMarked = true
            )
        )
    }

    @Test
    fun refreshIsNeededOnlyBelowRequiredVersion() {
        assertTrue("版本落后时必须刷新", ProjectionRefreshCoordinator.shouldRefresh(0, 1))
        assertFalse("已到版本时不得重复刷新", ProjectionRefreshCoordinator.shouldRefresh(1, 1))
        assertFalse("高于要求版本也不刷新", ProjectionRefreshCoordinator.shouldRefresh(2, 1))
    }
}
