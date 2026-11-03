package com.roundtrace.app.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.GamePosition
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.replay.PlayerTraceBuilder
import com.roundtrace.app.core.replay.PlayerTraceCategory
import com.roundtrace.app.core.replay.PlayerTraceFactLevel
import com.roundtrace.app.core.replay.PlayerTraceItem
import com.roundtrace.app.core.replay.PlayerTraceRelationship
import com.roundtrace.app.core.ui.theme.RoundTraceTheme
import com.roundtrace.app.feature.replay.PLAYER_TRACE_LIST_TAG
import com.roundtrace.app.feature.replay.PlayerTraceSection
import com.roundtrace.app.feature.replay.ReplayAction
import com.roundtrace.app.feature.replay.ReplayScreen
import com.roundtrace.app.feature.replay.ReplayUiState
import com.roundtrace.app.util.MatchBuilder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 玩家行为时间线的渲染级断言（F05-01 Phase C）。
 *
 * 边界声明（与 `RecordEditImpactPreviewTest` 同口径，避免被当成真机验收）：
 * Robolectric + `createComposeRule` 只验证「该出现的出现、不该出现的没出现、能滚动」，
 * **不验证**像素、字号、触摸命中与深色模式对比度。
 *
 * 夹具走真实链路：事件 → `PlayerTraceBuilder` → `PlayerTraceSection`，
 * 因此「筛选后列表变化」不是拿假数据装样子，而是座位/任务/类别真的过了同一套过滤。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class PlayerTraceSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private var clickedOrder: Int? = null

    /**
     * 8 人局夹具：每个任务都是「1 号当队长 + 提出 1/2/3 + 记录任务结果」，
     * 再加一条 1 号的公开发言。
     */
    private fun events(quests: Int = 2): List<MatchEvent> =
        MatchBuilder.eightPlayerMerlinMordred().apply {
            (1..quests).forEach { questNo ->
                questStarted(questNo, 1)
                teamProposed(questNo, 1, 1, listOf(1, 2, 3))
                missionResolved(
                    questNo = questNo,
                    teamSeats = listOf(1, 2, 3),
                    failCount = 0,
                    observedOutcome = MissionOutcome.SUCCESS
                )
            }
            playerClaimed(
                speakerSeat = 1,
                claimType = ClaimType.SUSPECT,
                questNo = 1,
                targetSeats = listOf(4),
                text = "4 号很可疑"
            )
        }.events()

    private fun show(allEvents: List<MatchEvent>, initialSeat: Int = 1) {
        compose.setContent {
            RoundTraceTheme {
                var seat by remember { mutableStateOf(initialSeat) }
                var quest by remember { mutableStateOf<Int?>(null) }
                var category by remember { mutableStateOf<PlayerTraceCategory?>(null) }

                val items = remember(seat, quest, category) {
                    PlayerTraceBuilder.build(
                        events = allEvents,
                        playerSeat = seat,
                        atOrder = 999,
                        mode = Perspective.PUBLIC,
                        observerSeat = null,
                        playerCount = 8,
                        questFilter = quest,
                        categoryFilter = category?.let { setOf(it) }
                    )
                }

                PlayerTraceSection(
                    playerCount = 8,
                    selectedSeat = seat,
                    questFilter = quest,
                    categoryFilter = category,
                    items = items,
                    onSelectSeat = { seat = it },
                    onSelectQuest = { quest = it },
                    onSelectCategory = { category = it },
                    onSelectItem = { clickedOrder = it }
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun selectedSeatChipReflectsState() {
        show(events(), initialSeat = 2)

        compose.onNodeWithContentDescription("查看 2 号的记录").assertIsSelected()
        compose.onNodeWithContentDescription("查看 1 号的记录").assertIsNotSelected()
    }

    @Test
    fun clickingSeatChipSwitchesSelection() {
        show(events(), initialSeat = 1)

        compose.onNodeWithContentDescription("查看 3 号的记录").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("查看 3 号的记录").assertIsSelected()
        compose.onNodeWithContentDescription("查看 1 号的记录").assertIsNotSelected()
    }

    @Test
    fun questFilterRemovesOtherQuests() {
        show(events(quests = 2))

        // 先把任务 2 的条目滚进视野，证明它原本存在
        compose.onNodeWithTag(PLAYER_TRACE_LIST_TAG).performScrollToNode(hasText("第 2 任务队长（记录为队长）"))
        compose.onNodeWithText("第 2 任务队长（记录为队长）").assertIsDisplayed()

        compose.onNodeWithContentDescription("筛选第 1 任务").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("第 2 任务队长（记录为队长）").assertDoesNotExist()
        compose.onNodeWithText("第 1 任务队长（记录为队长）").assertIsDisplayed()
        compose.onNodeWithContentDescription("筛选第 1 任务").assertIsSelected()
    }

    @Test
    fun categoryFilterRemovesOtherCategories() {
        show(events())

        compose.onNodeWithText("第 1 任务队长（记录为队长）").assertIsDisplayed()

        compose.onNodeWithContentDescription("筛选类别：发言").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("第 1 任务队长（记录为队长）").assertDoesNotExist()
        compose.onNodeWithText("1 号发言：怀疑").assertIsDisplayed()
        compose.onNodeWithContentDescription("筛选类别：发言").assertIsSelected()
    }

    @Test
    fun emptyStateIsShownWhenNothingIsRecorded() {
        // 8 号在夹具里没有队长/上车/发言记录
        show(events(), initialSeat = 8)

        compose.onNodeWithText("该玩家在当前时间范围内暂无可展示的记录").assertIsDisplayed()
    }

    @Test
    fun clickingItemReportsItsOccurredOrder() {
        show(events())

        // 第 1 个事件就是「任务 1 开始 / 队长 1」
        compose.onNodeWithText("第 1 任务队长（记录为队长）").performClick()
        compose.waitForIdle()

        assertEquals(1, clickedOrder)
    }

    @Test
    fun longListScrollsAndOffscreenItemsAreNotRendered() {
        val allEvents = events(quests = 5)
        show(allEvents)

        // 未滚动时列表尾部不在组合树里（LazyColumn 惰性渲染）
        compose.onNodeWithText("第 5 任务队长（记录为队长）").assertDoesNotExist()

        compose.onNodeWithTag(PLAYER_TRACE_LIST_TAG).performScrollToNode(hasText("第 5 任务队长（记录为队长）"))
        compose.waitForIdle()

        compose.onNodeWithText("第 5 任务队长（记录为队长）").assertIsDisplayed()
    }

    @Test
    fun clickingItemAfterScrollStillUsesTheItemOrder() {
        show(events(quests = 2))

        compose.onNodeWithTag(PLAYER_TRACE_LIST_TAG).performScrollToNode(hasText("1 号发言：怀疑"))
        compose.onNodeWithText("1 号发言：怀疑").performClick()
        compose.waitForIdle()

        // 夹具里言论是第 7 个事件（2 个任务 × 3 条 + 1 条发言）
        assertEquals(7, clickedOrder)
    }

    /**
     * 页内接线的唯一一处：`ReplayScreen` 必须把条目点击转成既有的
     * `ReplayAction.SelectPosition(occurredOrder)`，而不是新造一个动作。
     */
    @Test
    fun replayScreenRoutesTraceItemClickToSelectPosition() {
        val actions = mutableListOf<ReplayAction>()

        compose.setContent {
            RoundTraceTheme {
                ReplayScreen(
                    state = ReplayUiState(
                        isLoading = false,
                        matchTitle = "测试对局",
                        playerCount = 8,
                        at = GamePosition(42),
                        latestOrder = 42,
                        playerTraceSeat = 1,
                        playerTraceItems = listOf(
                            PlayerTraceItem(
                                sourceEventId = EventId("trace-e1"),
                                occurredOrder = 7,
                                questNo = 1,
                                category = PlayerTraceCategory.LEADER,
                                relationship = PlayerTraceRelationship.LED_QUEST,
                                title = "第 1 任务队长（记录为队长）",
                                detail = "任务开始记录 · 仅为队长身份记录，不推断阵营",
                                factLevel = PlayerTraceFactLevel.RECORDED
                            )
                        )
                    ),
                    onAction = { actions += it },
                    onBackToRecord = {}
                )
            }
        }

        compose.onNodeWithText("玩家时间线").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("第 1 任务队长（记录为队长）").performClick()
        compose.waitForIdle()

        assertEquals(listOf<ReplayAction>(ReplayAction.SelectPosition(7)), actions)
    }
}
