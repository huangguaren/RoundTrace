package com.roundtrace.app.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roundtrace.app.core.ui.theme.RoundTraceTheme
import com.roundtrace.app.feature.record.ProposalSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 在设备上注入实际触摸，补足本地 Compose 语义回归的窗口坐标验证。 */
@RunWith(AndroidJUnit4::class)
class ProposalSheetInteractionTest {
    @get:Rule val compose = createComposeRule()
    private var leader by mutableStateOf<Int?>(null)
    private var team by mutableStateOf(emptySet<Int>())

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
                    isEditing = initialLeader != null,
                    onSelectLeader = { leader = it },
                    onToggleTeamSeat = { seat -> team = if (seat in team) team - seat else team + seat },
                    onSaveDraft = {},
                    onSubmit = {},
                    onDismiss = {}
                )
            }
        }
        compose.waitForIdle()
    }

    private fun seat(seat: Int) {
        compose.onNode(hasContentDescription("$seat 号座位", substring = true) and hasClickAction())
            .performScrollTo().performClick()
    }

    private fun button(label: String) {
        compose.onNodeWithText(label).performScrollTo().performClick()
    }

    private fun selected(expectedLeader: Int, expectedTeam: Set<Int>) {
        compose.runOnIdle {
            assertEquals(expectedLeader, leader)
            assertEquals(expectedTeam, team)
        }
    }

    @Test
    fun repeatedCaptainTapsToggleBoardingWithoutChangingCaptain() {
        show()
        seat(1)
        selected(1, emptySet())
        seat(1)
        selected(1, setOf(1))
        seat(1)
        selected(1, emptySet())
        seat(1)
        seat(2)
        seat(3)
        selected(1, setOf(1, 2, 3))
    }

    @Test
    fun changeCaptainThenBoardThemWhileKeepingOtherMembers() {
        show(1, setOf(1, 2, 3))
        button("更换队长")
        seat(4)
        selected(4, setOf(1, 2, 3))
        seat(1)
        seat(4)
        selected(4, setOf(2, 3, 4))
    }

    @Test
    fun cancelOrChooseTheSameCaptainDoesNotAlterTheTeam() {
        show(5, setOf(2, 3, 5))
        button("更换队长")
        button("取消更换")
        selected(5, setOf(2, 3, 5))
        button("更换队长")
        seat(5)
        selected(5, setOf(2, 3, 5))
        seat(5)
        selected(5, setOf(2, 3))
    }
}
