package com.roundtrace.app.qa

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.GamePosition
import com.roundtrace.app.core.replay.PlayerTraceCategory
import com.roundtrace.app.core.replay.PlayerTraceFactLevel
import com.roundtrace.app.core.replay.PlayerTraceItem
import com.roundtrace.app.core.replay.PlayerTraceRelationship
import com.roundtrace.app.core.ui.theme.RoundTraceTheme
import com.roundtrace.app.feature.replay.ReplayAction
import com.roundtrace.app.feature.replay.ReplayScreen
import com.roundtrace.app.feature.replay.ReplayUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** QA 第二轮探针：私有来源标记渲染 + 点击跳转仍用条目 occurredOrder（条目定位点 3 / 来源事件 900）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class TempQAVerify2ClickProbeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun item(visibility: EventVisibility) = PlayerTraceItem(
        sourceEventId = EventId("v2-evt-900"),
        occurredOrder = 3,
        questNo = 1,
        category = PlayerTraceCategory.PRIVATE_NOTE,
        relationship = PlayerTraceRelationship.OWN_BELIEF,
        title = "QA 私记条目",
        detail = "仅本人可见",
        factLevel = PlayerTraceFactLevel.RECORDED,
        sourceVisibility = visibility
    )

    @Test
    fun privateSourceItemIsMarkedAndClickUsesItemOrder() {
        val actions = mutableListOf<ReplayAction>()
        compose.setContent {
            RoundTraceTheme {
                ReplayScreen(
                    state = ReplayUiState(
                        isLoading = false,
                        matchTitle = "QA 对局",
                        playerCount = 8,
                        at = GamePosition(900),
                        latestOrder = 900,
                        playerTraceSeat = 1,
                        playerTraceItems = listOf(item(EventVisibility.PRIVATE_OBSERVER))
                    ),
                    onAction = { actions += it },
                    onBackToRecord = {}
                )
            }
        }
        compose.onNodeWithText("玩家时间线").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("来自私有记录 · 仅本人可见").assertExists()
        compose.onNodeWithText("QA 私记条目").performClick()
        compose.waitForIdle()

        assertEquals(
            "必须用条目自身的 occurredOrder 跳转",
            listOf<ReplayAction>(ReplayAction.SelectPosition(3)), actions
        )
        assertFalse("不得跳到来源事件位置 900", actions.contains(ReplayAction.SelectPosition(900)))
        println("[V2] 私有条目渲染标记 OK；点击派发 = $actions")
    }

    @Test
    fun publicSourceItemHasNoPrivateMarker() {
        compose.setContent {
            RoundTraceTheme {
                ReplayScreen(
                    state = ReplayUiState(
                        isLoading = false,
                        matchTitle = "QA 对局",
                        playerCount = 8,
                        at = GamePosition(900),
                        latestOrder = 900,
                        playerTraceSeat = 1,
                        playerTraceItems = listOf(item(EventVisibility.PUBLIC))
                    ),
                    onAction = {},
                    onBackToRecord = {}
                )
            }
        }
        compose.onNodeWithText("玩家时间线").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("来自私有记录 · 仅本人可见").assertDoesNotExist()
        compose.onNodeWithText("QA 私记条目").assertExists()
        println("[V2] 公开来源条目无私有标记 OK")
    }
}
