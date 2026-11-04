package com.roundtrace.app.core.ui.identity

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.roundtrace.app.core.ui.theme.RoundTraceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 统一玩家展示组件的渲染级断言（UX-ID-01 / Phase A）。
 *
 * 边界声明：Robolectric + createComposeRule 只验证「该出现的出现、不该出现的没出现」，
 * **不验证**像素、字号、触摸命中与深色模式对比度（NX-05 真机验收已取消）。
 *
 * 头像解码在 Robolectric 下对无效 URI 会走 `SeatAvatarImage` 的 fallback，
 * 这正好用来验证 ID-03「URI 失效不崩溃、不串头像」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class SeatIdentityViewTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(display: SeatDisplay, tag: String = "seat") {
        composeRule.setContent {
            RoundTraceTheme {
                SeatIdentityView(display = display, testTag = tag)
            }
        }
    }

    private fun display(seat: Int, name: String?, avatar: String?): SeatDisplay =
        SeatDisplayResolver.resolve(
            listOf(com.roundtrace.app.core.model.Participant(seat, name, avatarUri = avatar)),
            playerCount = 10
        ).display(seat)

    @Test
    fun showsAvatarNameAndSeatNumberTogether() {
        render(display(2, "阿华", "content://avatar/2"))

        composeRule.onNodeWithText("阿华", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("2号", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("seat").assertIsDisplayed()
    }

    @Test
    fun noNicknameStillShowsSeatNumber() {
        render(display(3, null, "content://avatar/3"))

        composeRule.onNodeWithText("3号", substring = true).assertIsDisplayed()
        // 不能出现「匿名照片」这类无法定位的文字
        composeRule.onNodeWithText("匿名", substring = true).assertDoesNotExist()
    }

    @Test
    fun invalidAvatarUriFallsBackWithoutCrash() {
        render(display(4, "小林", "content://revoked/definitely-missing"))

        composeRule.onNodeWithText("小林", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("4号", substring = true).assertIsDisplayed()
    }

    @Test
    fun duplicateNamesAreDistinguishedBySeatInDifferentRows() {
        composeRule.setContent {
            RoundTraceTheme {
                SeatIdentityView(display = display(1, "阿华", null), testTag = "seat1")
                SeatIdentityView(display = display(2, "阿华", null), testTag = "seat2")
            }
        }

        composeRule.onNodeWithTag("seat1").assertIsDisplayed()
        composeRule.onNodeWithTag("seat2").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("阿华，1号座位", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("阿华，2号座位", substring = true).assertIsDisplayed()
    }

    @Test
    fun longNicknameProvidesFullAccessibilityDescription() {
        val longName = "这是一个非常非常长的玩家昵称长到一行根本放不下还需要省略号"
        render(display(7, longName, null))

        composeRule.onNodeWithContentDescription(longName, substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("7号座位", substring = true).assertIsDisplayed()
    }

    @Test
    fun bothDisplayNameAndCompactAvatarInitial() {
        render(display(5, "王小明", null))

        // 无头像时用昵称首字占位（onNodeWithText 默认即为精确匹配）
        composeRule.onNodeWithText("王").assertIsDisplayed()
        composeRule.onNodeWithText("王小明", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("5号", substring = true).assertIsDisplayed()
    }
}
