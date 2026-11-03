package com.roundtrace.app.feature

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.roundtrace.app.core.data.edit.AffectedEvent
import com.roundtrace.app.core.data.edit.EditImpactPlan
import com.roundtrace.app.core.data.edit.ImpactOperation
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventType
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.ui.theme.RoundTraceTheme
import com.roundtrace.app.feature.record.EditImpactPreviewDialog
import com.roundtrace.app.feature.record.PendingEdit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P2：影响预览对话框的**渲染级**断言。
 *
 * ## 为什么之前没有这层测试
 *
 * NX-06 的结论里明确写着「Compose 渲染无自动断言，只有代码路径与 `strings.xml` 证据」。
 * 但仓库其实已经有 Robolectric + `createComposeRule` 的基建（见 `ProposalSheetTest`），
 * 缺的只是「把对话框抽成可调用的组件」这一步。
 *
 * ## 本文件守住什么
 *
 * 1. 分组标题按「任务 · 提案」聚合，不在 UI 层二次乱序；
 * 2. `将撤销` / `仅提示` 两种标签真的被渲染出来（不是只在 plan 里存在）；
 * 3. **不可判定且会撤销时，确认按钮必须不存在**（US-06 的最后一道闸在 UI 上）；
 * 4. 长清单可以滚动到最后一条（否则用户看不到「还会撤销什么」就点了确认）；
 * 5. 取消不触发确认回调；保存中确认按钮不可点（防双击）；
 * 6. 无影响项时明确显示「无需重置其他记录」，而不是留一片空白。
 *
 * 边界声明：Robolectric 不等于真机，本文件不验证像素、字号、触摸命中，
 * 也不替代人工目视；NX-05 设备验收仍然取消。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class RecordEditImpactPreviewTest {

    @get:Rule
    val compose = createComposeRule()

    private var confirmCount = 0
    private var cancelCount = 0

    private fun item(
        id: String,
        questNo: Int?,
        proposalNo: Int?,
        operation: ImpactOperation,
        reason: String,
        type: EventType = EventType.TEAM_VOTE_RECORDED
    ) = AffectedEvent(
        eventId = EventId(id),
        questNo = questNo,
        eventType = type,
        reason = reason,
        operation = operation,
        proposalNo = proposalNo
    )

    private fun pending(
        affected: List<AffectedEvent>,
        manual: List<String> = emptyList(),
        before: String = "队长 1 号 · 队伍 1/2/3",
        after: String = "队长 1 号 · 队伍 1/2/4"
    ) = PendingEdit(
        plan = EditImpactPlan(
            matchId = MatchId("preview-match"),
            targetId = EventId("target"),
            replacement = TeamProposed(
                eventId = EventId("replacement"),
                occurredOrder = 99,
                recordedAt = 0L,
                questNo = 1,
                proposalNo = 1,
                leaderSeat = 1,
                teamSeats = listOf(1, 2, 4)
            ),
            expectedActive = emptyList(),
            affected = affected,
            manualReviewReasons = manual
        ),
        eventType = EventType.TEAM_PROPOSED,
        questNo = 1,
        proposalNo = 1,
        beforeSummary = before,
        afterSummary = after
    )

    private fun show(pending: PendingEdit, isSaving: Boolean = false) {
        confirmCount = 0
        cancelCount = 0
        compose.setContent {
            RoundTraceTheme {
                EditImpactPreviewDialog(
                    pending = pending,
                    isSaving = isSaving,
                    onConfirm = { confirmCount += 1 },
                    onCancel = { cancelCount += 1 }
                )
            }
        }
        compose.waitForIdle()
    }

    // ————————————————————————————————————————————————————————
    // 1. 分组 + 标签 + 汇总行
    // ————————————————————————————————————————————————————————

    @Test
    fun rendersGroupsAndBothOperationTags() {
        show(
            pending(
                affected = listOf(
                    item("a1", 1, 1, ImpactOperation.RETRACT, "同提案的投票不再成立"),
                    item("a2", 2, null, ImpactOperation.REVIEW_ONLY, "第 2 任务结果依赖无法判定")
                )
            )
        )

        // 分组标题：一个带提案号，一个只带任务号
        compose.onNodeWithText("第 1 任务 · 第 1 次提案").assertExists()
        compose.onNodeWithText("第 2 任务").assertExists()

        // 两种操作标签都必须真的出现在文本里：
        // 汇总行各出现一次 + 各自那条受影响记录各出现一次 = 2
        compose.onAllNodesWithText("将撤销", substring = true).assertCountEquals(2)
        compose.onAllNodesWithText("仅提示", substring = true).assertCountEquals(2)

        // 汇总行：受影响 2 条，其中撤销 1、仅提示 1
        compose.onNodeWithText("受影响 2 条：将撤销 1 条，仅提示 1 条").assertExists()

        // 原因文本必须原样展示，用户才能判断「到底为什么撤」
        compose.onNodeWithText("同提案的投票不再成立", substring = true).assertExists()
        compose.onNodeWithText("第 2 任务结果依赖无法判定", substring = true).assertExists()
    }

    @Test
    fun rendersBeforeAndAfterSummaries() {
        show(pending(affected = emptyList()))
        compose.onNodeWithText("修改前：队长 1 号 · 队伍 1/2/3").assertExists()
        compose.onNodeWithText("修改后：队长 1 号 · 队伍 1/2/4").assertExists()
    }

    @Test
    fun reviewOnlyOnlyShowsReviewTagAndNoConfirmResetCount() {
        show(
            pending(
                affected = listOf(
                    item("b1", 1, 1, ImpactOperation.REVIEW_ONLY, "票数与已记录决议矛盾")
                )
            )
        )

        // 「仅提示」既出现在汇总行也出现在条目里（2 次）；「将撤销」只出现在汇总行的
        // 「将撤销 0 条」里，条目里没有 → 1 次。这正是「只有提示、没有撤销」的渲染证据。
        compose.onAllNodesWithText("仅提示", substring = true).assertCountEquals(2)
        compose.onAllNodesWithText("将撤销", substring = true).assertCountEquals(1)
        // 没有任何撤销项时，确认按钮不该显示「重置 N 条」
        compose.onNodeWithText("确认修改").assertExists()
        compose.onNodeWithText("确认修改并重置", substring = true).assertDoesNotExist()
    }

    // ————————————————————————————————————————————————————————
    // 2. US-06：不可判定且会撤销 → 没有确认按钮
    // ————————————————————————————————————————————————————————

    @Test
    fun unresolvedDestructiveCaseHidesConfirmButton() {
        show(
            pending(
                affected = listOf(
                    item("c1", 1, 1, ImpactOperation.RETRACT, "队伍已改，旧投票失去前提")
                ),
                manual = listOf("第 1 任务的任务结果依赖关系无法判定（该任务没有通过决议）")
            )
        )

        // 原因要摆出来
        compose.onNodeWithText("影响范围无法自动判定", substring = true).assertExists()
        compose.onNodeWithText("依赖关系无法判定", substring = true).assertExists()

        // 破坏性确认按钮必须不存在（两种文案都不能出现）
        compose.onNodeWithText("确认修改并重置 1 条").assertDoesNotExist()
        compose.onNodeWithText("确认修改").assertDoesNotExist()

        // 取消仍然可用，用户不会被困住
        compose.onNodeWithText("取消").assertExists().performClick()
        assertEquals("取消必须回调", 1, cancelCount)
        assertEquals("取消不得触发确认", 0, confirmCount)
    }

    @Test
    fun unresolvedButNonDestructiveCaseKeepsConfirmButton() {
        show(
            pending(
                affected = listOf(
                    item("d1", 1, 1, ImpactOperation.REVIEW_ONLY, "只提示，不撤销")
                ),
                manual = listOf("任务结果依赖无法判定")
            )
        )

        // 不撤任何记录时，人工复核只是提醒，不能因此拦住保存
        compose.onNodeWithText("确认修改").assertExists().performClick()
        assertEquals("非破坏性场景应允许确认", 1, confirmCount)
    }

    // ————————————————————————————————————————————————————————
    // 3. 长清单可滚动到最后一条
    // ————————————————————————————————————————————————————————

    @Test
    fun longAffectedListIsScrollableToLastItem() {
        val items = (1..24).map { index ->
            item(
                id = "s$index",
                questNo = index,
                proposalNo = 1,
                operation = ImpactOperation.RETRACT,
                reason = "第 $index 条受影响记录的原因"
            )
        }
        show(pending(affected = items))

        val lastReason = "第 24 条受影响记录的原因"
        // 对话框内容区有 verticalScroll：能滚动到末尾才算「用户看得到全部影响」
        compose.onNode(hasScrollAction())
            .performScrollToNode(hasText(lastReason, substring = true))

        compose.onNodeWithText(lastReason, substring = true).assertExists()
        compose.onNodeWithText("确认修改并重置 24 条").assertExists()
    }

    // ————————————————————————————————————————————————————————
    // 4. 交互：取消不确认、保存中不可点、无影响项说明
    // ————————————————————————————————————————————————————————

    @Test
    fun cancelDoesNotTriggerConfirm() {
        show(pending(affected = listOf(item("e1", 1, 1, ImpactOperation.RETRACT, "会撤销一条"))))

        compose.onNodeWithText("取消").performClick()
        assertEquals("取消只能触发 onCancel", 0, confirmCount)
        assertEquals(1, cancelCount)
    }

    @Test
    fun confirmButtonIsDisabledWhileSaving() {
        show(
            pending(affected = listOf(item("f1", 1, 1, ImpactOperation.RETRACT, "会撤销一条"))),
            isSaving = true
        )

        val label = "确认修改并重置 1 条"
        compose.onNodeWithText(label).assertExists()
        compose.onNodeWithText(label).assertIsNotEnabled()
    }

    @Test
    fun confirmButtonIsEnabledWhenIdle() {
        show(pending(affected = listOf(item("g1", 1, 1, ImpactOperation.RETRACT, "会撤销一条"))))

        val label = "确认修改并重置 1 条"
        compose.onNodeWithText(label).assertIsEnabled().performClick()
        assertEquals(1, confirmCount)
        assertEquals(0, cancelCount)
    }

    @Test
    fun noImpactShowsNoResetHintInsteadOfEmptyList() {
        show(pending(affected = emptyList()))

        compose.onNodeWithText("无需重置其他记录").assertExists()
        // 没有影响项时不得出现汇总行或分组标题
        compose.onNodeWithText("受影响", substring = true).assertDoesNotExist()
        compose.onNodeWithText("确认修改").assertExists()
    }

    @Test
    fun titleNamesTheQuestBeingEdited() {
        show(pending(affected = emptyList()))
        compose.onNodeWithText("修改第 1 任务的记录").assertExists()
    }
}
