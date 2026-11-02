package com.roundtrace.app.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.semantics.SemanticsActions
import com.roundtrace.app.core.ui.theme.RoundTraceTheme
import com.roundtrace.app.feature.record.ProposalSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 通过实际弹窗控件的点击语义验证回调，防止 ViewModel 单测漏掉 UI 的错误分支。
 * Robolectric 的 Dialog 窗口触摸坐标不等价于真机；本测试不声称验证像素或触摸命中。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class ProposalSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private var leader by mutableStateOf<Int?>(null)
    private var team by mutableStateOf(emptySet<Int>())
    private var saving by mutableStateOf(false)

    private fun show(initialLeader: Int? = null, initialTeam: Set<Int> = emptySet()) {
        leader = initialLeader
        team = initialTeam
        compose.setContent {
            RoundTraceTheme {
                ProposalSheet(
                    questNo = 1,
                    expectedTeamSize = 3,
                    playerCount = 8,
                    leaderSeat = leader,
                    teamSeats = team,
                    canSubmit = leader != null && team.size == 3,
                    isSaving = saving,
                    isEditing = initialLeader != null,
                    onSelectLeader = { leader = it },
                    onToggleTeamSeat = { seat ->
                        team = if (seat in team) team - seat else team + seat
                    },
                    onSaveDraft = {},
                    onSubmit = {},
                    onDismiss = {}
                )
            }
        }
        compose.waitForIdle()
    }

    private fun clickSeat(seat: Int) {
        compose.onNode(
            hasContentDescription("$seat 号座位", substring = true) and hasClickAction()
        ).activate()
    }

    private fun SemanticsNodeInteraction.activate() =
        performSemanticsAction(SemanticsActions.OnClick) { it() }

    private fun assertSelection(expectedLeader: Int, expectedTeam: Set<Int>) {
        compose.runOnIdle {
            assertEquals(expectedLeader, leader)
            assertEquals(expectedTeam, team)
        }
    }

    @Test
    fun captainCanBoardAndLeaveWithoutLosingCaptaincy() {
        show()
        clickSeat(1)
        assertSelection(1, emptySet())
        clickSeat(1)
        assertSelection(1, setOf(1))
        clickSeat(1)
        assertSelection(1, emptySet())
        clickSeat(1)
        assertSelection(1, setOf(1))
    }

    @Test
    fun changingCaptainPreservesAllTeamMembersIncludingFormerCaptain() {
        show(initialLeader = 1, initialTeam = setOf(1, 2, 3))
        compose.onNodeWithText("更换队长").activate()
        clickSeat(4)
        assertSelection(4, setOf(1, 2, 3))
        clickSeat(1)
        clickSeat(4)
        assertSelection(4, setOf(2, 3, 4))
    }

    @Test
    fun choosingAnExistingMemberAsCaptainDoesNotRemoveThemFromTeam() {
        show(initialLeader = 1, initialTeam = setOf(2, 3, 4))
        compose.onNodeWithText("更换队长").activate()
        clickSeat(2)
        assertSelection(2, setOf(2, 3, 4))
        clickSeat(2)
        assertSelection(2, setOf(3, 4))
    }

    @Test
    fun choosingSameCaptainIsIdempotentAndReturnsToMemberSelection() {
        show(initialLeader = 1, initialTeam = setOf(1, 2))
        compose.onNodeWithText("更换队长").activate()
        clickSeat(1)
        assertSelection(1, setOf(1, 2))
        clickSeat(3)
        assertSelection(1, setOf(1, 2, 3))
    }

    @Test
    fun cancelChangingCaptainKeepsPreviousCaptainAndTeam() {
        show(initialLeader = 1, initialTeam = setOf(1, 2))
        compose.onNodeWithText("更换队长").activate()
        compose.onNodeWithText("取消更换").activate()
        assertSelection(1, setOf(1, 2))
        clickSeat(3)
        assertSelection(1, setOf(1, 2, 3))
    }

    @Test
    fun editingAnExistingProposalCanToggleCaptainMembershipDirectly() {
        show(initialLeader = 5, initialTeam = setOf(2, 3, 5))
        clickSeat(5)
        assertSelection(5, setOf(2, 3))
        clickSeat(5)
        assertSelection(5, setOf(2, 3, 5))
    }

    @Test
    fun aFullTeamCanSubmitAgainAfterCaptainChangeFinishesOrIsCancelled() {
        show(initialLeader = 1, initialTeam = setOf(2, 3, 4))
        compose.onNodeWithText("提交提案").assertIsEnabled()
        compose.onNodeWithText("更换队长").activate()
        compose.onNodeWithText("提交提案").assertIsNotEnabled()
        clickSeat(5)
        assertSelection(5, setOf(2, 3, 4))
        compose.onNodeWithText("提交提案").assertIsEnabled()
        compose.onNodeWithText("更换队长").activate()
        compose.onNodeWithText("取消更换").activate()
        compose.onNodeWithText("提交提案").assertIsEnabled()
    }

    @Test
    fun savingLocksCaptainAndTeamToKeepTheSubmittedSnapshotStable() {
        show(initialLeader = 1, initialTeam = setOf(1, 2, 3))
        compose.runOnIdle { saving = true }
        compose.onNodeWithText("更换队长").assertIsNotEnabled()
        compose.onNodeWithText("提交提案").assertIsNotEnabled()
        compose.onNode(
            hasContentDescription("1 号座位", substring = true) and hasClickAction()
        ).assertIsNotEnabled().performClick()
        assertSelection(1, setOf(1, 2, 3))
    }
}
