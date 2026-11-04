package com.roundtrace.app.feature

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.ui.theme.RoundTraceTheme
import com.roundtrace.app.feature.claim.ClaimAction
import com.roundtrace.app.feature.claim.ClaimScreen
import com.roundtrace.app.feature.claim.ClaimUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 发言页两处 SeatPicker 的身份展示与真实 seat 写入（UX-ID-03 / Phase C / ID-05）。
 *
 * 「谁在说」单选 +「指向谁」多选共享同一份座位映射；昵称、头像、座位号都要可见，
 * 点击回调仍交出**数字 seat**（不交昵称、不交索引）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class ClaimScreenIdentityTest {

    @get:Rule
    val compose = createComposeRule()

    private val labels = mapOf<Int, String?>(
        1 to "阿华", 2 to "阿华", 3 to null, 4 to "小林", 5 to "小五"
    )
    private val avatars = mapOf<Int, String?>(
        1 to "content://a/1", 2 to "content://a/2", 3 to null, 4 to null, 5 to null
    )

    private fun show(actions: MutableList<ClaimAction>) {
        compose.setContent {
            RoundTraceTheme {
                ClaimScreen(
                    state = ClaimUiState(
                        isLoading = false,
                        playerCount = 5,
                        selectedTag = ClaimType.SUSPECT,
                        seatLabels = labels,
                        seatAvatars = avatars
                    ),
                    onAction = { actions += it }
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun bothSeatPickersRenderNicknames() {
        val actions = mutableListOf<ClaimAction>()
        show(actions)
        // 两处 picker 都要显示昵称：至少各一个「小林」节点
        val small = compose.onAllNodesWithContentDescription("小林", substring = true)
            .fetchSemanticsNodes().size
        assertTrue("两处 picker 都应显示昵称，实际节点数=$small", small >= 2)
    }

    @Test
    fun seatNumberIsAlwaysVisibleForEverySeat() {
        val actions = mutableListOf<ClaimAction>()
        show(actions)
        // 座位号常驻：5 个座位 × 2 处 picker；无昵称的 3 号也必须能定位
        (1..5).forEach { seat ->
            val nodes = compose.onAllNodesWithContentDescription("$seat 号座位", substring = true)
                .fetchSemanticsNodes().size
            assertTrue("座位 $seat 应在两处 picker 都可定位，实际=$nodes", nodes >= 2)
        }
    }

    @Test
    fun fullAccessibilityDescriptionContainsNameAndSeat() {
        val actions = mutableListOf<ClaimAction>()
        show(actions)
        compose.onAllNodesWithContentDescription("阿华，1号座位", substring = true)
            .fetchSemanticsNodes().size.let { assertTrue("两处 picker 都应可见，实际=$it", it >= 2) }
        compose.onAllNodesWithContentDescription("阿华，2号座位", substring = true)
            .fetchSemanticsNodes().size.let { assertTrue(it >= 2) }
    }

    @Test
    fun clickingSpeakerGridReturnsTheRawSeatNumber() {
        val actions = mutableListOf<ClaimAction>()
        show(actions)
        // 两处 picker 有相同内容描述：第 0 个是「谁在说」（单选），第 1 个是「指向谁」（多选）
        compose.onAllNodesWithContentDescription("2 号座位", substring = true)[0].performClick()
        compose.waitForIdle()
        assertEquals(listOf(ClaimAction.SelectSpeaker(2)), actions)
    }

    @Test
    fun clickingTargetGridReturnsTheRawSeatNumber() {
        val actions = mutableListOf<ClaimAction>()
        show(actions)
        compose.onAllNodesWithContentDescription("4 号座位", substring = true)[1].performClick()
        compose.waitForIdle()
        assertEquals(listOf(ClaimAction.ToggleTarget(4)), actions)
    }

    @Test
    fun duplicateNamesRemainDistinctBySeat() {
        val actions = mutableListOf<ClaimAction>()
        show(actions)
        compose.onAllNodesWithContentDescription("阿华，1号座位", substring = true)
            .fetchSemanticsNodes().size.let { assertTrue(it >= 2) }
        // 同名但点击仍写入各自的数字 seat
        compose.onAllNodesWithContentDescription("2 号座位", substring = true)[0].performClick()
        compose.waitForIdle()
        assertEquals(2, (actions.single() as ClaimAction.SelectSpeaker).seat)
    }
}
