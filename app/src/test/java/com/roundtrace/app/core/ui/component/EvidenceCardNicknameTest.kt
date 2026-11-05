package com.roundtrace.app.core.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.roundtrace.app.core.model.EvidenceFinding
import com.roundtrace.app.core.model.EvidenceLevel
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.Participant
import com.roundtrace.app.core.ui.identity.SeatDisplayResolver
import com.roundtrace.app.core.ui.theme.RoundTraceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 证据卡片昵称（UX-P2-02 / Phase B）。
 *
 * 钉住 P2-02 / ID-02：
 * - 提供 `seatIdentity` 时，`relatedSeats` 显示「昵称（N号）」（如「阿华（2号）」）；
 * - 不提供 `seatIdentity` 时，沿用基线「N 号」；
 * - [EvidenceFinding.label] / [EvidenceFinding.explanation] 原文**绝不**被正则改写
 *   （含其中的座位号与自由文本）—— 只格式化 `relatedSeats`，不碰结论与解释。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class EvidenceCardNicknameTest {

    @get:Rule
    val compose = createComposeRule()

    private fun identity() = SeatDisplayResolver.resolve(
        listOf(
            Participant(1, "小明", avatarUri = null),
            Participant(2, "阿华", avatarUri = null),
            Participant(3, "小林", avatarUri = null),
            Participant(4, "阿华", avatarUri = null),
            Participant(5, "小五", avatarUri = null),
            Participant(6, "小六", avatarUri = null),
            Participant(7, "小七", avatarUri = null),
            Participant(8, "小八", avatarUri = null)
        ),
        8
    )

    private fun finding() = EvidenceFinding(
        id = "f-1",
        label = "2 号与 4 号同属一支队伍",
        level = EvidenceLevel.RULE_CERTAIN,
        explanation = "复盘显示 2号 和 4号 在同一队伍执行任务，两人昵称相同需凭座位号区分。",
        ruleId = "A05_TEAM_CONTAINS_EVIL",
        sourceEventIds = listOf(EventId("e-1")),
        relatedSeats = listOf(2, 4)
    )

    /** 渲染卡片并展开解释区块（relatedSeats 在 AnimatedVisibility 内，默认折叠）。 */
    private fun show(seatIdentity: com.roundtrace.app.core.ui.identity.SeatIdentity?) {
        compose.setContent {
            RoundTraceTheme {
                EvidenceCard(finding = finding(), seatIdentity = seatIdentity)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(finding().label, substring = true).performClick()
        compose.waitForIdle()
    }

    @Test
    fun relatedSeatsShowNicknamesWhenIdentityProvided() {
        show(identity())
        // 同名座位也要能区分：2 号与 4 号都是阿华，但座位号不同
        compose.onNodeWithText("阿华（2号）", substring = true).assertIsDisplayed()
        compose.onNodeWithText("阿华（4号）", substring = true).assertIsDisplayed()
    }

    @Test
    fun relatedSeatsFallbackToSeatNumberWithoutIdentity() {
        show(null)
        compose.onNodeWithText("2 号", substring = true).assertIsDisplayed()
        compose.onNodeWithText("4 号", substring = true).assertIsDisplayed()
        // 不提供映射时不得臆造昵称
        compose.onNodeWithText("阿华（2号）", substring = true).assertDoesNotExist()
    }

    @Test
    fun labelAndExplanationAreNeverRewritten() {
        show(identity())
        // explanation 原文（含「2号」「4号」）必须原样显示，绝不变成「阿华（2号）」
        compose.onNodeWithText(finding().explanation, substring = false).assertIsDisplayed()
        // 结论 label 同样原样
        compose.onNodeWithText(finding().label, substring = true).assertIsDisplayed()
    }
}
