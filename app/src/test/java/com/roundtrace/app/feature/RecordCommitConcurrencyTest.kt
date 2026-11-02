package com.roundtrace.app.feature

import com.roundtrace.app.core.model.EventFactory
import com.roundtrace.app.core.model.EventType
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.rules.avalon.AvalonReducer
import com.roundtrace.app.core.rules.avalon.AvalonRules
import com.roundtrace.app.core.ui.component.SaveStatus
import com.roundtrace.app.core.util.SequentialIdGenerator
import com.roundtrace.app.core.util.fixedClock
import com.roundtrace.app.feature.record.RecordAction
import com.roundtrace.app.feature.record.RecordCommitResult
import com.roundtrace.app.feature.record.RecordViewModel
import com.roundtrace.app.util.FakeMatchRepository
import com.roundtrace.app.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * F1：草稿自动保存期间提交**不得**被静默吞掉。
 *
 * ## 缺陷是什么
 *
 * 原来的 `commit()` 以 `saveStatus == SAVING` 作为并发闸门，而草稿自动保存（300ms 防抖后的
 * `persistDraftNow`）写的是**同一个** `saveStatus`。现场「选完队员马上点确认」时，
 * 草稿保存刚好占着 SAVING 窗口 → 提交直接 `return`，事件一条都没写，
 * 而 UI 随后把状态刷成 SAVED —— 用户以为保存成功。
 *
 * ## 这里怎么把窗口固定住
 *
 * [FakeMatchRepository.saveDraftGate] 让 `saveDraft` 挂起不返回，于是 SAVING 窗口
 * 被**无限拉长**：不需要靠时序碰运气，也不需要 sleep。
 * 在此状态下点提交，断言事件真的 +1、`commitResult` 真的有值。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordCommitConcurrencyTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val rules = AvalonRules()

    private fun eightPlayerConfig() = MatchCreationDraft(
        title = "提交并发测试",
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

    /** 8 人局第 1 轮：队长 1 号 + 3 人队。 */
    private fun RecordViewModel.fillFirstProposal() {
        onAction(RecordAction.SelectLeader(1))
        onAction(RecordAction.ToggleTeamSeat(2))
        onAction(RecordAction.ToggleTeamSeat(3))
    }

    /**
     * 只数**提案**条数。
     *
     * 首次提交提案时仓储会自动补建一条 `QUEST_STARTED`（ARCHITECTURE §9.3-18），
     * 因此「事件总数 +1」是个错误的断言口径；这里只盯被提交的那类事件。
     */
    private fun FakeMatchRepository.proposalCount(): Int =
        bundle()!!.events.count { it.type == EventType.TEAM_PROPOSED }

    // ————————————————————————————————————————————————————————
    // 核心：草稿保存占着 SAVING 窗口时，提交必须照常执行
    // ————————————————————————————————————————————————————————

    @Test
    fun commitStillRunsWhileDraftAutosaveHoldsTheSavingWindow() = runTest {
        val repository = FakeMatchRepository(config = eightPlayerConfig())
        // 把草稿保存卡死在「已进入 SAVING、还没返回」的瞬间
        repository.saveDraftGate = CompletableDeferred()
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        viewModel.fillFirstProposal()
        // 防抖到期 → persistDraftNow 挂起在闸门上，SAVING 窗口被固定住
        advanceUntilIdle()

        val blocked = viewModel.uiState.value
        assertEquals(
            "前提：草稿保存必须真的把状态停在 SAVING（否则这条用例测不到 F1 的窗口）",
            SaveStatus.SAVING,
            blocked.saveStatus
        )
        assertNull("前提：此时还没有任何提交结果", blocked.commitResult)
        assertFalse(
            "草稿保存不得禁用提交按钮：isSaving 只看提交在飞，不看 saveStatus",
            blocked.isSaving
        )

        val eventsBefore = repository.proposalCount()

        viewModel.onAction(RecordAction.ConfirmProposal)
        advanceUntilIdle()

        val after = viewModel.uiState.value
        assertEquals(
            "**提交必须真的写进库**：草稿保存占着 SAVING 也不得吞掉这次提交",
            eventsBefore + 1,
            repository.proposalCount()
        )
        assertNotNull("必须给出提交结果，而不是静默丢弃", after.commitResult)
        assertEquals(RecordCommitResult.Succeeded, after.commitResult)
        assertEquals("提交成功后展示状态应为已保存", SaveStatus.SAVED, after.saveStatus)
    }

    // ————————————————————————————————————————————————————————
    // 防重复提交仍然有效（去掉 saveStatus 闸门后的回归）
    // ————————————————————————————————————————————————————————

    @Test
    fun rapidDoubleSubmitWritesExactlyOneEvent() = runTest {
        val repository = FakeMatchRepository(config = eightPlayerConfig())
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        viewModel.fillFirstProposal()
        advanceUntilIdle()
        val proposalsBefore = repository.proposalCount()

        // 同一帧内连点两次：第一次同步把闸门关上，第二次必须被忽略
        viewModel.onAction(RecordAction.ConfirmProposal)
        viewModel.onAction(RecordAction.ConfirmProposal)
        advanceUntilIdle()

        assertEquals(
            "连点两次只能写一条提案（防重复提交不能因为换了闸门就失效）",
            proposalsBefore + 1,
            repository.proposalCount()
        )
        assertEquals(RecordCommitResult.Succeeded, viewModel.uiState.value.commitResult)
    }

    @Test
    fun commitGateReopensAfterFailure() = runTest {
        val repository = FakeMatchRepository(config = eightPlayerConfig())
        repository.failNextWrite = true
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        viewModel.fillFirstProposal()
        advanceUntilIdle()
        val proposalsBefore = repository.proposalCount()

        viewModel.onAction(RecordAction.ConfirmProposal)
        advanceUntilIdle()
        assertEquals("第一次写失败不得落库", proposalsBefore, repository.proposalCount())
        assertEquals(
            SaveStatus.FAILED,
            viewModel.uiState.value.saveStatus
        )

        // 闸门必须已经放开，否则用户重试会毫无反应
        viewModel.onAction(RecordAction.ConfirmProposal)
        advanceUntilIdle()
        assertEquals(
            "失败后重试必须真的再写一次（闸门不得卡死）",
            proposalsBefore + 1,
            repository.proposalCount()
        )
        assertEquals(RecordCommitResult.Succeeded, viewModel.uiState.value.commitResult)
    }
}
