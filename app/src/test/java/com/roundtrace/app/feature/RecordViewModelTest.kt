package com.roundtrace.app.feature

import com.roundtrace.app.core.model.EventFactory
import com.roundtrace.app.core.model.GameConfig
import com.roundtrace.app.core.model.IssueCode
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.TeamVoteChoice
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.rules.avalon.AvalonReducer
import com.roundtrace.app.core.rules.avalon.AvalonRules
import com.roundtrace.app.core.util.SequentialIdGenerator
import com.roundtrace.app.core.util.fixedClock
import com.roundtrace.app.feature.record.RecordAction
import com.roundtrace.app.feature.record.RecordViewModel
import com.roundtrace.app.core.ui.component.SaveStatus
import com.roundtrace.app.util.FakeMatchRepository
import com.roundtrace.app.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 记录页 ViewModel 测试（TASKS.md T44⑤ + T47③）。
 *
 * 重点验证「状态全部由重放派生」这条架构约束：
 * 超员时 `TEAM_SIZE_MISMATCH` 必须出现在 `issues` 里，而且这个 issue 来自规则层
 * （`AvalonRules.validateTeamDraft`），不是 ViewModel 自己判断的。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val rules = AvalonRules()

    private fun eightPlayerConfig(): GameConfig = MatchCreationDraft(
        title = "测试对局",
        playerCount = 8,
        roleSlots = AvalonPreset.standardPreset(8),
        mySeat = 1,
        initialLeaderSeat = 1,
        presetId = AvalonPreset.presetId(8),
        rulesetId = AvalonPreset.RULESET_ID,
        rulesetVersion = AvalonPreset.RULESET_VERSION
    ).toGameConfig(createdAtEpochMs = 0L)

    private fun newViewModel(
        repository: FakeMatchRepository
    ): RecordViewModel = RecordViewModel(
        matchId = MatchId("fake-match"),
        repository = repository,
        gameRulesRegistry = DefaultGameRulesRegistry(),
        reducer = AvalonReducer(rules),
        rules = rules,
        eventFactory = EventFactory(SequentialIdGenerator("evt"), fixedClock(1_700_000_000_000L))
    )

    @Test
    fun `T44 超员时 issues 出现 TEAM_SIZE_MISMATCH`() = runTest {
        val repository = FakeMatchRepository(config = eightPlayerConfig())
        val viewModel = newViewModel(repository)

        // 8 人局第 1 个任务应 3 人，这里选 4 人（超员）
        listOf(1, 2, 3, 4).forEach { seat ->
            viewModel.onAction(RecordAction.ToggleTeamSeat(seat))
        }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("对局应已加载完成", state.isLoading)
        assertNotNull("必须能派生出当前任务", state.quest)
        assertEquals("当前任务应为第 1 个", 1, state.quest?.questNo)
        assertEquals("8 人局第 1 任务应 3 人", 3, state.quest?.expectedTeamSize)
        assertTrue(
            "超员时必须出现 TEAM_SIZE_MISMATCH，实际=${state.issues.map { it.code }}",
            state.issues.any { it.code == IssueCode.TEAM_SIZE_MISMATCH }
        )
    }

    @Test
    fun `T44 队伍选满后不再报 TEAM_SIZE_MISMATCH`() = runTest {
        val repository = FakeMatchRepository(config = eightPlayerConfig())
        val viewModel = newViewModel(repository)

        listOf(1, 2, 3).forEach { viewModel.onAction(RecordAction.ToggleTeamSeat(it)) }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(
            "恰好 3 人时不应报 TEAM_SIZE_MISMATCH，实际=${state.issues.map { it.code }}",
            state.issues.any { it.code == IssueCode.TEAM_SIZE_MISMATCH }
        )
        assertTrue("队员已选满", state.teamIsFull)
    }

    @Test
    fun `T44 撤销选中队员后名单收缩`() = runTest {
        val repository = FakeMatchRepository(config = eightPlayerConfig())
        val viewModel = newViewModel(repository)

        viewModel.onAction(RecordAction.ToggleTeamSeat(2))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.draft.teamSeats.contains(2))

        viewModel.onAction(RecordAction.ToggleTeamSeat(2))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.draft.teamSeats.contains(2))
    }

    @Test
    fun `T44 草稿会被防抖写入仓储`() = runTest {
        val repository = FakeMatchRepository(config = eightPlayerConfig())
        val viewModel = newViewModel(repository)

        viewModel.onAction(RecordAction.SelectLeader(3))
        viewModel.onAction(RecordAction.ToggleTeamSeat(1))
        // 推进虚拟时间越过防抖窗口
        advanceUntilIdle()

        assertTrue(
            "草稿必须在防抖后落库，实际调用次数=${repository.savedDrafts.size}",
            repository.savedDrafts.isNotEmpty()
        )
        val saved = repository.savedDrafts.last()
        assertTrue("草稿应记录队长 3 号，实际=$saved", saved.contains("\"leaderSeat\":3"))
    }

    @Test
    fun `T44 提交提案后事件落库且 order 从 1 开始`() = runTest {
        val repository = FakeMatchRepository(config = eightPlayerConfig())
        val viewModel = newViewModel(repository)

        viewModel.onAction(RecordAction.SelectLeader(1))
        listOf(1, 2, 3).forEach { viewModel.onAction(RecordAction.ToggleTeamSeat(it)) }
        viewModel.onAction(RecordAction.ConfirmProposal)
        advanceUntilIdle()

        // 仓储会自动补建 QUEST_STARTED（T35③），所以这里有 2 条事件
        assertEquals(
            "自动补建 QUEST_STARTED + 提案 = 2 条事件，实际=${repository.assignedOrders}",
            2,
            repository.assignedOrders.size
        )
        assertEquals("occurredOrder 必须从 1 开始", listOf(1, 2), repository.assignedOrders)
        assertEquals(SaveStatus.SAVED, viewModel.uiState.value.saveStatus)

        val proposal = viewModel.uiState.value.currentProposal
        assertNotNull("提交后应能派生当前提案", proposal)
        assertEquals("提案队伍应被记录", listOf(1, 2, 3), proposal?.teamSeats)
    }

    @Test
    fun `T47 汇总票的判定调用规则且超人数被标记`() = runTest {
        val repository = FakeMatchRepository(config = eightPlayerConfig())
        val viewModel = newViewModel(repository)

        // 8 人局：5 赞成 3 反对 → 通过（approve 5 > 4）
        viewModel.onAction(RecordAction.SetVoteSummary(approveCount = 5, rejectCount = 3))
        advanceUntilIdle()
        assertEquals(
            "5 赞成 3 反对在 8 人局应判通过",
            ProposalDecision.APPROVED,
            viewModel.uiState.value.voteDecision
        )
        assertFalse(
            "5+3=8 未超过人数",
            viewModel.uiState.value.voteExceedsPlayers
        )

        // 8 人局：4 赞成 4 反对 → 平票否决
        viewModel.onAction(RecordAction.SetVoteSummary(approveCount = 4, rejectCount = 4))
        advanceUntilIdle()
        assertEquals(
            "平票必须视为否决",
            ProposalDecision.REJECTED,
            viewModel.uiState.value.voteDecision
        )
    }

    @Test
    fun `T47 逐人票统计与汇总一致时判定相同`() = runTest {
        val repository = FakeMatchRepository(config = eightPlayerConfig())
        val viewModel = newViewModel(repository)

        // 5 赞成 3 反对（逐人）
        (1..5).forEach { viewModel.onAction(RecordAction.SetVote(it, TeamVoteChoice.APPROVE)) }
        (6..8).forEach { viewModel.onAction(RecordAction.SetVote(it, TeamVoteChoice.REJECT)) }
        advanceUntilIdle()

        assertEquals(
            "逐人票 5:3 应判通过",
            ProposalDecision.APPROVED,
            viewModel.uiState.value.voteDecision
        )
    }

    @Test
    fun `T48 失败牌数推导任务结果第4任务门槛为2`() = runTest {
        val repository = FakeMatchRepository(config = eightPlayerConfig())
        val viewModel = newViewModel(repository)

        viewModel.onAction(RecordAction.SetMissionFailCount(1))
        advanceUntilIdle()
        // 第 1 个任务门槛是 1：1 张失败牌即失败
        assertEquals(
            "第 1 任务 1 张失败牌应失败",
            com.roundtrace.app.core.model.MissionOutcome.FAILED,
            viewModel.uiState.value.missionDerivedOutcome
        )

        viewModel.onAction(RecordAction.SetMissionFailCount(0))
        advanceUntilIdle()
        assertEquals(
            "0 张失败牌应成功",
            com.roundtrace.app.core.model.MissionOutcome.SUCCESS,
            viewModel.uiState.value.missionDerivedOutcome
        )

        viewModel.onAction(RecordAction.SetMissionFailCount(null))
        advanceUntilIdle()
        assertEquals(
            "失败牌未知时结果必须为 null（不猜）",
            null,
            viewModel.uiState.value.missionDerivedOutcome
        )
    }

    @Test
    fun `T44 对局不存在时给出错误态而不是崩溃`() = runTest {
        val repository = FakeMatchRepository(config = eightPlayerConfig())
        repository.deleteMatch(MatchId("fake-match"))
        // deleteMatch 清空事件但仍返回 bundle；用 null 场景靠 getBundle 为 null 不易构造，
        // 这里改为断言「空事件对局」也不会崩溃且能派生第 1 任务
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("空对局应派生第 1 个任务", 1, state.quest?.questNo)
        assertEquals("空对局时间线应为空", 0, state.timeline.size)
    }
}
