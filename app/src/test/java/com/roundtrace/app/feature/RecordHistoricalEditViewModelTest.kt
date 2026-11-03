package com.roundtrace.app.feature

import com.roundtrace.app.core.data.EditApplyResult
import com.roundtrace.app.core.data.RepositoryError
import com.roundtrace.app.core.model.EventFactory
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.rules.avalon.AvalonReducer
import com.roundtrace.app.core.rules.avalon.AvalonRules
import com.roundtrace.app.core.ui.component.SaveStatus
import com.roundtrace.app.core.util.SequentialIdGenerator
import com.roundtrace.app.core.util.fixedClock
import com.roundtrace.app.feature.record.EditFailureReason
import com.roundtrace.app.feature.record.EditSession
import com.roundtrace.app.feature.record.PendingEdit
import com.roundtrace.app.feature.record.RecordAction
import com.roundtrace.app.feature.record.RecordCommitResult
import com.roundtrace.app.feature.record.RecordViewModel
import com.roundtrace.app.util.FakeMatchRepository
import com.roundtrace.app.util.MainDispatcherRule
import com.roundtrace.app.util.MatchBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * NX-06 历史安全编辑的 **ViewModel 状态机**测试（A01、A02、A09、A10、A13、A14、A18）。
 *
 * 数据库级原子性由 `AtomicHistoricalEditTest` 用真实 Room 覆盖；这里只钉住
 * 「UI 侧会不会偷跑」「失败会不会被当成成功」这类状态机缺陷 —— 也就是本轮 P0-2 的病根。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordHistoricalEditViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val rules = AvalonRules()

    private fun eightPlayerConfig() = MatchCreationDraft(
        title = "历史编辑 ViewModel 测试",
        playerCount = 8,
        roleSlots = AvalonPreset.standardPreset(8),
        mySeat = 1,
        initialLeaderSeat = 1,
        presetId = AvalonPreset.presetId(8),
        rulesetId = AvalonPreset.RULESET_ID,
        rulesetVersion = AvalonPreset.RULESET_VERSION
    ).toGameConfig(createdAtEpochMs = 0L)

    private fun newViewModel(repository: FakeMatchRepository) = RecordViewModel(
        matchId = MatchId("fake-match"),
        repository = repository,
        gameRulesRegistry = DefaultGameRulesRegistry(),
        reducer = AvalonReducer(rules),
        rules = rules,
        eventFactory = EventFactory(SequentialIdGenerator("evt"), fixedClock(1_700_000_000_000L))
    )

    /** 前两轮已结算、第三轮待录：`currentQuest` 会是 3。 */
    private fun twoQuestsSettled(): List<com.roundtrace.app.core.model.MatchEvent> =
        MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(1, 1, listOf(1, 2, 3), 0, MissionOutcome.SUCCESS)
            .questCycle(2, 2, listOf(1, 2, 3, 4), 0, MissionOutcome.SUCCESS)
            .events()

    private fun repositoryWithTwoQuests(): FakeMatchRepository = FakeMatchRepository(
        config = eightPlayerConfig(),
        initialEvents = twoQuestsSettled()
    )

    private fun firstQuestProposalId(repository: FakeMatchRepository): EventId =
        repository.bundle()!!.events
            .filterIsInstance<TeamProposed>()
            .first { it.questNo == 1 }
            .eventId

    /** 打开第 1 轮提案编辑态并改一名队员，然后点「保存」→ 只应产出预览。 */
    private fun RecordViewModel.openFirstProposalEditAndSave(repository: FakeMatchRepository) {
        onAction(RecordAction.OpenEdit(firstQuestProposalId(repository)))
        onAction(RecordAction.ToggleTeamSeat(3))
        onAction(RecordAction.ToggleTeamSeat(5))
        onAction(RecordAction.ConfirmProposal)
    }

    // ————————————————————————————————————————————————————————
    // A02：历史模式下禁止新增
    // ————————————————————————————————————————————————————————

    @Test
    fun historyModeRejectsNewEvents() = runTest {
        val repository = repositoryWithTwoQuests()
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        val before = repository.bundle()!!.events
        viewModel.onAction(RecordAction.SetEditingQuest(1))
        advanceUntilIdle()

        // 把表单填成「本来可以新增」的状态，确保被拦下的原因是历史模式而不是表单不完整
        viewModel.onAction(RecordAction.SelectLeader(2))
        viewModel.onAction(RecordAction.ToggleTeamSeat(1))
        viewModel.onAction(RecordAction.ToggleTeamSeat(2))
        viewModel.onAction(RecordAction.ToggleTeamSeat(3))
        viewModel.onAction(RecordAction.ConfirmProposal)
        advanceUntilIdle()

        assertEquals("A02：历史浏览期间不得新增任何事件", before, repository.bundle()!!.events)
        assertTrue("A02：不得产生任何写入调用", repository.assignedOrders.isEmpty())
        assertNull("A02：不得进入预览", viewModel.uiState.value.pendingEdit)
    }

    @Test
    fun exitingHistoryModeRestoresLatestPositionWithoutWriting() = runTest {
        val repository = repositoryWithTwoQuests()
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        val before = repository.bundle()!!.events
        viewModel.onAction(RecordAction.SetEditingQuest(1))
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.quest?.questNo)

        viewModel.onAction(RecordAction.SetEditingQuest(null))
        advanceUntilIdle()

        assertEquals("US-01：退出历史浏览后回到真实进度（第 3 轮）", 3, viewModel.uiState.value.quest?.questNo)
        assertEquals("US-01：浏览不改变历史", before, repository.bundle()!!.events)
    }

    // ————————————————————————————————————————————————————————
    // A01 / A09 / A10：预览与取消都是零写入，草稿保留
    // ————————————————————————————————————————————————————————

    @Test
    fun savingHistoricalEditOnlyPreviewsAndWritesNothing() = runTest {
        val repository = repositoryWithTwoQuests()
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        val before = repository.bundle()!!.events
        viewModel.openFirstProposalEditAndSave(repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("A01：预览阶段绝不能写库", before, repository.bundle()!!.events)
        assertTrue("A01：必须调用过影响计划计算", repository.planCalls > 0)
        assertEquals("A01：确认前不得调用确认接口", 0, repository.applyCalls)
        assertNotNull("A01：应产出待确认预览", state.pendingEdit)
        assertNull("A01：预览不是成功，不得发 Succeeded", state.commitResult)
        assertFalse("A01：预览阶段不得显示保存失败", state.saveStatus == SaveStatus.FAILED)
        assertNotNull("A01：编辑会话必须保留", state.editSession)
        assertEquals("A01：草稿必须保留", setOf(1, 2, 5), state.draft.teamSeats)
    }

    @Test
    fun cancelKeepsDraftAndEditSessionWithoutWriting() = runTest {
        val repository = repositoryWithTwoQuests()
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        val before = repository.bundle()!!.events
        viewModel.openFirstProposalEditAndSave(repository)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.pendingEdit)

        viewModel.onAction(RecordAction.CancelPendingEdit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull("A09：取消后预览关闭", state.pendingEdit)
        assertEquals("A09：取消 = 零历史写入", before, repository.bundle()!!.events)
        assertEquals("A09：不得调用确认接口", 0, repository.applyCalls)
        assertEquals("A10：取消后草稿完整保留", setOf(1, 2, 5), state.draft.teamSeats)
        assertNotNull("A10：取消后编辑会话仍在，用户可继续改", state.editSession)
        assertEquals("A10：草稿里仍记着编辑目标", firstQuestProposalId(repository), state.draft.editTargetEventId)
    }

    @Test
    fun reopeningSameTargetAfterCancelKeepsUserInput() = runTest {
        val repository = repositoryWithTwoQuests()
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        viewModel.openFirstProposalEditAndSave(repository)
        advanceUntilIdle()
        viewModel.onAction(RecordAction.CancelPendingEdit)
        advanceUntilIdle()

        // 用户再次点同一条记录：不得用原值把刚改的内容冲掉
        viewModel.onAction(RecordAction.OpenEdit(firstQuestProposalId(repository)))
        advanceUntilIdle()

        assertEquals(
            "取消后重新打开同一条记录，必须保留用户这次的输入",
            setOf(1, 2, 5),
            viewModel.uiState.value.draft.teamSeats
        )
    }

    // ————————————————————————————————————————————————————————
    // A11：确认走单次复合写
    // ————————————————————————————————————————————————————————

    @Test
    fun confirmAppliesExactlyOneAtomicWriteAndClearsDraft() = runTest {
        val repository = repositoryWithTwoQuests()
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        viewModel.openFirstProposalEditAndSave(repository)
        advanceUntilIdle()
        val pending = viewModel.uiState.value.pendingEdit!!
        assertTrue("前提：这次修改会重置依赖记录", pending.retractCount > 0)

        viewModel.onAction(RecordAction.ConfirmPendingEdit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("A11：只发一次复合写", 1, repository.applyCalls)
        assertTrue("A11：成功后才发 Succeeded", state.commitResult is RecordCommitResult.Succeeded)
        assertEquals(SaveStatus.SAVED, state.saveStatus)
        assertNull("A11：成功后关闭预览", state.pendingEdit)
        assertNull("A11：成功后退出编辑态", state.editSession)
        assertTrue("A11：成功后清空草稿", state.draft.teamSeats.isEmpty())

        val after = repository.bundle()!!.events
        assertTrue(
            "A11：第 1 轮提案已被替换成 1/2/5",
            after.filterIsInstance<TeamProposed>()
                .any { it.questNo == 1 && it.teamSeats.sorted() == listOf(1, 2, 5) }
        )
        assertEquals(
            "A11：被确认的依赖记录全部下线",
            pending.retractCount,
            pending.plan.retractIds.count { id -> after.none { it.eventId == id } }
        )
    }

    // ————————————————————————————————————————————————————————
    // A13 / A18：过期与失败都不得假装成功
    // ————————————————————————————————————————————————————————

    @Test
    fun stalePreviewRefreshesPlanAndKeepsInput() = runTest {
        val repository = repositoryWithTwoQuests()
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        viewModel.openFirstProposalEditAndSave(repository)
        advanceUntilIdle()
        val pending = viewModel.uiState.value.pendingEdit!!
        val freshPlan = pending.plan.copy(manualReviewReasons = listOf("记录已变化"))
        repository.nextApplyResult = EditApplyResult.StalePreview(freshPlan)

        viewModel.onAction(RecordAction.ConfirmPendingEdit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(
            "A13：必须提示重新确认",
            RecordCommitResult.Failed(EditFailureReason.STALE_PREVIEW),
            state.commitResult
        )
        assertEquals(SaveStatus.FAILED, state.saveStatus)
        assertNotNull("A13：预览必须用新计划刷新后继续展示", state.pendingEdit)
        assertEquals("A13：刷新后的计划就是仓储给的那份", freshPlan, state.pendingEdit!!.plan)
        assertEquals("A13：输入必须保留", setOf(1, 2, 5), state.draft.teamSeats)
        assertNotNull("A13：编辑会话必须保留", state.editSession)
    }

    @Test
    fun repositoryFailureNeverReportsSaved() = runTest {
        val repository = repositoryWithTwoQuests()
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        val before = repository.bundle()!!.events
        viewModel.openFirstProposalEditAndSave(repository)
        advanceUntilIdle()
        repository.nextApplyResult = EditApplyResult.Failed(RepositoryError.WRITE_FAILED)

        viewModel.onAction(RecordAction.ConfirmPendingEdit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("A18：业务失败必须如实标 FAILED", SaveStatus.FAILED, state.saveStatus)
        assertEquals(
            "A18：必须把错误原因交给 UI",
            RecordCommitResult.Failed(RepositoryError.WRITE_FAILED.name),
            state.commitResult
        )
        assertFalse(state.commitResult is RecordCommitResult.Succeeded)
        assertEquals("A18：失败时历史不得变化", before, repository.bundle()!!.events)
        assertEquals("A18：输入必须保留", setOf(1, 2, 5), state.draft.teamSeats)
        assertNotNull("A18：编辑会话必须保留", state.editSession)
        assertNotNull("A18：预览仍然在（用户可重试）", state.pendingEdit)
    }

    @Test
    fun conflictKeepsInputAndDoesNotReportSuccess() = runTest {
        val repository = repositoryWithTwoQuests()
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        viewModel.openFirstProposalEditAndSave(repository)
        advanceUntilIdle()
        repository.nextApplyResult = EditApplyResult.Conflict(listOf("任务结果依赖关系不明"))

        viewModel.onAction(RecordAction.ConfirmPendingEdit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(SaveStatus.FAILED, state.saveStatus)
        assertEquals(
            RecordCommitResult.Failed(EditFailureReason.MANUAL_REVIEW_REQUIRED),
            state.commitResult
        )
        assertEquals("冲突时输入必须保留", setOf(1, 2, 5), state.draft.teamSeats)
        assertNotNull("冲突时编辑会话必须保留", state.editSession)
    }

    // ————————————————————————————————————————————————————————
    // A14：连点两次确认只执行一次
    // ————————————————————————————————————————————————————————

    @Test
    fun doubleConfirmIssuesOnlyOneWrite() = runTest {
        val repository = repositoryWithTwoQuests()
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        viewModel.openFirstProposalEditAndSave(repository)
        advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        repository.applyGate = gate
        viewModel.onAction(RecordAction.ConfirmPendingEdit)
        viewModel.onAction(RecordAction.ConfirmPendingEdit)
        advanceUntilIdle()

        assertEquals("A14：连点两次只允许发出一次确认", 1, repository.applyCalls)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("A14：最终仍是只写一次", 1, repository.applyCalls)
        assertTrue(viewModel.uiState.value.commitResult is RecordCommitResult.Succeeded)
    }

    @Test
    fun pendingEditCarriesStructuredImpactForTheDialog() = runTest {
        val repository = repositoryWithTwoQuests()
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        viewModel.openFirstProposalEditAndSave(repository)
        advanceUntilIdle()

        val pending: PendingEdit = viewModel.uiState.value.pendingEdit!!
        assertEquals(1, pending.questNo)
        assertEquals(1, pending.proposalNo)
        assertTrue(
            "受影响清单必须按任务/提案分组，供 UI 直接渲染",
            pending.groups.isNotEmpty() && pending.groups.all { it.items.isNotEmpty() }
        )
        assertTrue("必须给出修改前后摘要", pending.beforeSummary.isNotBlank() && pending.afterSummary.isNotBlank())
        assertTrue("修改后摘要必须反映新队伍", pending.afterSummary.contains("5 号"))
        assertTrue("破坏性影响必须可确认", pending.canConfirmDestructive)
        assertEquals(
            "同提案的投票+决议+任务结果 = 3 条",
            3,
            pending.retractCount
        )
    }

    @Test
    fun cancelEditActionStillDiscardsDraftWithoutWriting() = runTest {
        val repository = repositoryWithTwoQuests()
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        val before = repository.bundle()!!.events
        viewModel.openFirstProposalEditAndSave(repository)
        advanceUntilIdle()
        viewModel.onAction(RecordAction.CancelEdit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull("退出编辑态", state.editSession)
        assertNull("同时关闭预览", state.pendingEdit)
        assertEquals("退出编辑态依旧不得写库", before, repository.bundle()!!.events)
        assertNull("草稿不再指向编辑目标", state.draft.editTargetEventId)
    }
}
