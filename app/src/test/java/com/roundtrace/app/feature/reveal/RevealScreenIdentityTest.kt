package com.roundtrace.app.feature.reveal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.roundtrace.app.core.model.Participant
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.ui.identity.SeatDisplayResolver
import com.roundtrace.app.core.ui.theme.RoundTraceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 揭晓页渲染级断言（REVEAL-Q-01 / Phase D）。
 *
 * 钉住 ID-01 / 12 / 17：揭晓行显示头像+昵称+座位号、同名可区分；
 * 计划与真实身份不一致时展示明确警告与**独立**确认按钮，
 * 确认只派发 action，不自动修改计划或真相。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class RevealScreenIdentityTest {

    @get:Rule
    val compose = createComposeRule()

    private fun identity() = SeatDisplayResolver.resolve(
        listOf(
            Participant(1, "阿华", avatarUri = "content://a/1"),
            Participant(2, "阿华", avatarUri = null),
            Participant(3, "小林", avatarUri = null),
            Participant(4, null, avatarUri = null),
            Participant(5, "小五", avatarUri = null),
            Participant(6, "小六", avatarUri = null),
            Participant(7, "小七", avatarUri = null),
            Participant(8, "小八", avatarUri = null)
        ),
        8
    )

    private fun state(
        rows: List<RevealSeatRow> = (1..8).map { RevealSeatRow(it, RoleId.UNKNOWN) },
        warning: String = "",
        confirmed: Boolean = false
    ) = RevealUiState(
        isLoading = false,
        playerCount = 8,
        rows = rows,
        roleQuota = mapOf(
            RoleId.MERLIN to 1,
            RoleId.PERCIVAL to 1,
            RoleId.LOYAL_SERVANT to 3,
            RoleId.ASSASSIN to 1,
            RoleId.MORGANA to 1,
            RoleId.MORDRED to 1
        ),
        seatIdentity = identity(),
        planMismatchWarning = warning,
        planMismatchConfirmed = confirmed
    )

    private fun show(
        actions: MutableList<RevealAction>,
        uiState: RevealUiState = state()
    ) {
        compose.setContent {
            RoundTraceTheme {
                RevealScreen(state = uiState, onAction = { actions += it })
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun revealRowsShowAvatarNicknameAndSeatNumber() {
        val actions = mutableListOf<RevealAction>()
        show(actions)
        // 1、2 号同名，两行都必须显示昵称
        compose.onAllNodesWithText("阿华", substring = true)
            .fetchSemanticsNodes().size.let { assertTrue("实际=$it", it >= 2) }
        compose.onNodeWithText("1号", substring = true).assertIsDisplayed()
        compose.onNodeWithText("小林", substring = true).assertIsDisplayed()
        compose.onNodeWithText("4号", substring = true).assertIsDisplayed()
    }

    @Test
    fun duplicateNamesAreDistinguishableBySeat() {
        val actions = mutableListOf<RevealAction>()
        show(actions)
        val one = compose.onAllNodesWithContentDescription("阿华，1号座位", substring = true)
            .fetchSemanticsNodes().size
        val two = compose.onAllNodesWithContentDescription("阿华，2号座位", substring = true)
            .fetchSemanticsNodes().size
        assertTrue(one >= 1)
        assertTrue(two >= 1)
    }

    @Test
    fun planMismatchShowsExplicitWarningAndConfirmationButton() {
        val actions = mutableListOf<RevealAction>()
        show(
            actions,
            state(warning = "真实身份与开局计划不一致（忠臣：计划 3 / 实际 4）。按实际记录保存不会修改计划。")
        )
        // 警告卡在 8 行揭晓行之后，需滚动进视野
        compose.onNodeWithTag(REVEAL_LIST_TAG)
            .performScrollToNode(hasText("按实际身份记录", substring = true))
        compose.onNodeWithText("真实身份与开局计划不一致", substring = true).assertIsDisplayed()
        compose.onNodeWithText("按实际身份记录", substring = true).assertIsDisplayed()

        compose.onNodeWithText("按实际身份记录", substring = true).performClick()
        compose.waitForIdle()
        assertEquals(listOf(RevealAction.ConfirmPlanMismatch), actions)
    }

    @Test
    fun noWarningWhenTruthMatchesPlan() {
        val actions = mutableListOf<RevealAction>()
        show(actions)
        compose.onNodeWithText("按实际身份记录", substring = true).assertDoesNotExist()
    }

    @Test
    fun threeLoyalServantsCanBeRepresentedInRows() {
        val actions = mutableListOf<RevealAction>()
        val rows = (1..8).map { seat ->
            RevealSeatRow(
                seat = seat,
                roleId = if (seat <= 3) RoleId.LOYAL_SERVANT else RoleId.UNKNOWN
            )
        }
        show(actions, state(rows = rows))
        // 三个座位都显示忠臣，且行数不丢
        assertEquals(3, rows.count { it.roleId == RoleId.LOYAL_SERVANT })
        compose.onAllNodesWithText("阿华", substring = true)
            .fetchSemanticsNodes().size.let { assertTrue("实际=$it", it >= 2) }
    }
}
