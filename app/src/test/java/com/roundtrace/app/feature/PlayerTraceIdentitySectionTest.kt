package com.roundtrace.app.feature

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.roundtrace.app.core.model.Participant
import com.roundtrace.app.core.ui.identity.SeatDisplayResolver
import com.roundtrace.app.core.ui.theme.RoundTraceTheme
import com.roundtrace.app.feature.replay.PLAYER_TRACE_SEAT_ROW_TAG
import com.roundtrace.app.feature.replay.PLAYER_TRACE_SEAT_CHIP_TAG
import com.roundtrace.app.feature.replay.PlayerTraceSection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 复盘玩家筛选横滑 Chip 的身份展示（UX-ID-02 / Phase B）。
 *
 * 钉住 ID-01 / 02 / 03 / 04：
 * - Chip 显示头像 + 昵称 + 常驻座位号；
 * - 同名玩家仍能区分；切换玩家不串头像；
 * - 10 人局横滑可达最后座位；无头像/无昵称正常降级。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class PlayerTraceIdentitySectionTest {

    @get:Rule
    val compose = createComposeRule()

    private fun identity(playerCount: Int = 10) = SeatDisplayResolver.resolve(
        (1..playerCount).map { seat ->
            Participant(
                seat = seat,
                displayName = when (seat) {
                    1, 2 -> "阿华"
                    3 -> null
                    10 -> "这是一个非常长的昵称长到一行放不下"
                    else -> "玩家$seat"
                },
                avatarUri = if (seat % 2 == 1) "content://avatar/$seat" else null
            )
        },
        playerCount
    )

    private fun show(playerCount: Int = 10) {
        compose.setContent {
            RoundTraceTheme {
                PlayerTraceSection(
                    playerCount = playerCount,
                    selectedSeat = 1,
                    questFilter = null,
                    categoryFilter = null,
                    items = emptyList(),
                    onSelectSeat = {},
                    onSelectQuest = {},
                    onSelectCategory = {},
                    onSelectItem = {},
                    seatIdentity = identity(playerCount)
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun seatChipShowsNameAndSeatNumberTogether() {
        show()
        // 1、2 号同名，两个 Chip 都必须显示昵称
        compose.onAllNodesWithText("阿华", substring = true).assertCountEquals(2)
        compose.onNodeWithText("1号", substring = true).assertIsDisplayed()
    }

    @Test
    fun duplicateNamesAreDistinguishedBySeatNumber() {
        show()
        compose.onNodeWithContentDescription("阿华，1号座位", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("阿华，2号座位", substring = true).assertIsDisplayed()
    }

    @Test
    fun noNameChipShowsSeatNumberAndDoesNotCrash() {
        show()
        compose.onNodeWithContentDescription("3号座位", substring = true).assertIsDisplayed()
        compose.onNodeWithText("3号", substring = true).assertIsDisplayed()
    }

    @Test
    fun longNicknameHasFullAccessibilityDescription() {
        show()
        compose.onNodeWithTag(PLAYER_TRACE_SEAT_ROW_TAG)
            .performScrollToNode(hasTestTag("${PLAYER_TRACE_SEAT_CHIP_TAG}10"))
        compose.onNodeWithContentDescription(
            "这是一个非常长的昵称长到一行放不下",
            substring = true
        ).assertIsDisplayed()
    }

    @Test
    fun tenPlayerRosterScrollsToLastSeat() {
        show(10)
        compose.onNodeWithTag(PLAYER_TRACE_SEAT_ROW_TAG)
            .performScrollToNode(hasTestTag("${PLAYER_TRACE_SEAT_CHIP_TAG}10"))
        compose.onNodeWithContentDescription("10号座位", substring = true).assertIsDisplayed()
    }

    @Test
    fun selectedSeatStillUsesExistingSelectionSemantics() {
        show()
        compose.onNodeWithContentDescription("查看 1 号的记录", substring = true).assertIsSelected()
    }

    /**
     * Phase C：筛选 Chip 必须把外层「查看 N 号的记录」与内层「阿华，N号座位」两层
     * contentDescription 合并成**一条**，否则读屏会把同一个座位号先以 Chip 节点播报一次、
     * 再深入内层把昵称/座位号再播报一次。
     *
     * 失败态（修复前）：存在一个独立的内层节点，其 contentDescription 恰好为「阿华，1号座位」，
     * 而合并后的「查看 1 号的记录，阿华，1号座位」尚不存在 —— 两个断言都会失败。
     * 修复后：单个 Chip 只产出一条合并描述，内层不再有独立节点。
     */
    @Test
    fun seatChipMergesIdentityIntoSingleContentDescription() {
        show()
        // 修复后单个 Chip 只产生一条合并后的无障碍描述（座位动作 + 身份），
        // 外层与内层不再各挂一条说明。
        compose.onNodeWithContentDescription("查看 1 号的记录，阿华，1号座位").assertIsDisplayed()
        compose.onNodeWithContentDescription("查看 2 号的记录，阿华，2号座位").assertIsDisplayed()
        // 关键断言：不存在独立的「阿华，1号座位」节点（此前会与外层说明重复播报）。
        compose.onAllNodesWithContentDescription("阿华，1号座位").assertCountEquals(0)
        // 同名的两个座位仍通过各自的座位号区分，且各自只对应一个合并节点。
        compose.onAllNodesWithContentDescription("查看 1 号的记录", substring = true).assertCountEquals(1)
        compose.onAllNodesWithContentDescription("查看 2 号的记录", substring = true).assertCountEquals(1)
    }

    @Test
    fun clickingSeatChipStillReportsRawSeatNumber() {
        var clicked: Int? = null
        compose.setContent {
            RoundTraceTheme {
                PlayerTraceSection(
                    playerCount = 5,
                    selectedSeat = 1,
                    questFilter = null,
                    categoryFilter = null,
                    items = emptyList(),
                    onSelectSeat = { clicked = it },
                    onSelectQuest = {},
                    onSelectCategory = {},
                    onSelectItem = {},
                    seatIdentity = identity(5)
                )
            }
        }
        compose.onNodeWithContentDescription("查看 4 号的记录", substring = true).performClick()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(4, clicked)
    }

    @Test
    fun emptyIdentityKeepsLegacySeatLabels() {
        compose.setContent {
            RoundTraceTheme {
                PlayerTraceSection(
                    playerCount = 5,
                    selectedSeat = 2,
                    questFilter = null,
                    categoryFilter = null,
                    items = emptyList(),
                    onSelectSeat = {},
                    onSelectQuest = {},
                    onSelectCategory = {},
                    onSelectItem = {}
                )
            }
        }
        compose.onNodeWithContentDescription("查看 2 号的记录").assertIsSelected()
        // 旧行为仍可用：没有身份映射时显示「N 号」纯文字
        compose.onNodeWithText("2 号").assertIsDisplayed()
    }
}
