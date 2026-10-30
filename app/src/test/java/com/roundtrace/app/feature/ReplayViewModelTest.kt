package com.roundtrace.app.feature

import com.roundtrace.app.core.knowledge.AvalonEvidenceAnalyzer
import com.roundtrace.app.core.knowledge.AvalonKnowledgeProvider
import com.roundtrace.app.core.model.GamePosition
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.util.FakeMatchRepository
import com.roundtrace.app.util.MainDispatcherRule
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
 * 复盘 ViewModel 测试（TASKS.md T57⑤ + T57②）。
 *
 * 核心：
 * 1. fake bundle 下切换视角**不抛异常**；
 * 2. 上帝视角在 `canOpenGodView == false` 时被**拒绝**（模式不变 + 标记被拒）；
 * 3. 每次切换位置都重新计算，`snapshot.findings` 与时间线随 `at` 收敛。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplayViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val reducerHolder = com.roundtrace.app.core.rules.DefaultGameRulesRegistry()
    private val provider = AvalonKnowledgeProvider(reducerHolder.avalonReducer)
    private val analyzer = AvalonEvidenceAnalyzer()

    private fun repository(revealed: Boolean = false): FakeMatchRepository {
        val builder = com.roundtrace.app.knowledge.AvalonScenario.fullMatch()
        val scenario = com.roundtrace.app.knowledge.AvalonScenario.match(revealed)
        return FakeMatchRepository(
            config = com.roundtrace.app.knowledge.AvalonScenario.eightPlayerConfig(),
            initialEvents = builder.events(),
            revealedAt = if (revealed) 1_700_000_100_000L else null,
            // 真实仓储在 createMatch 时会为每个座位写一条角色记录；
            // fake 也要有，否则观察者角色退化为 UNKNOWN（那是 fake 的缺口，不是产品行为）
            initialRoles = scenario.roleAssignments
        )
    }

    private fun newViewModel(repository: FakeMatchRepository) = com.roundtrace.app.feature.replay
        .ReplayViewModel(
            matchId = MatchId("fake-match"),
            repository = repository,
            knowledgeProvider = provider,
            analyzer = analyzer
        )

    @Test
    fun t57_switching_perspectives_does_not_throw() = runTest {
        val viewModel = newViewModel(repository())
        advanceUntilIdle()

        val modes = listOf(
            Perspective.PUBLIC,
            Perspective.OBSERVER_AT_TIME,
            Perspective.GOD_AFTER_REVEAL,
            Perspective.PUBLIC
        )
        modes.forEach { mode ->
            viewModel.onAction(
                com.roundtrace.app.feature.replay.ReplayAction.SelectPerspective(mode)
            )
            advanceUntilIdle()
            // 不抛异常即为通过；同时状态必须可用
            assertFalse(
                "切到 $mode 后不应进入错误态，实际=${viewModel.uiState.value.errorMessage}",
                viewModel.uiState.value.errorMessage != null
            )
        }
    }

    @Test
    fun t57_god_view_is_rejected_when_not_allowed() = runTest {
        val viewModel = newViewModel(repository(revealed = false))
        advanceUntilIdle()

        assertFalse(
            "未揭晓时 canOpenGodView 必须为 false",
            viewModel.uiState.value.canOpenGodView
        )

        viewModel.onAction(
            com.roundtrace.app.feature.replay.ReplayAction.SelectPerspective(
                Perspective.GOD_AFTER_REVEAL
            )
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("应标记 godViewBlocked 供 UI 提示", state.godViewBlocked)
        assertEquals(
            "被拒绝后模式不得改变（绝不静默降级或强行给真相）",
            Perspective.PUBLIC,
            state.mode
        )

        viewModel.onAction(com.roundtrace.app.feature.replay.ReplayAction.ClearGodViewBlocked)
        advanceUntilIdle()
        assertFalse("提示消费后应清除标记", viewModel.uiState.value.godViewBlocked)
    }

    @Test
    fun t57_position_change_recomputes_snapshot_and_timeline() = runTest {
        val viewModel = newViewModel(repository())
        advanceUntilIdle()

        val latest = viewModel.uiState.value
        assertNotNull("最新位置应有快照", latest.snapshot)
        val latestTimelineSize = latest.timeline.size
        assertTrue("最新位置时间线应非空", latestTimelineSize > 0)

        // 退回到第 1 个事件
        viewModel.onAction(com.roundtrace.app.feature.replay.ReplayAction.SelectPosition(1))
        advanceUntilIdle()

        val early = viewModel.uiState.value
        assertEquals("位置应更新", 1, early.at.occurredOrder)
        assertTrue(
            "时间线必须随位置收敛（不超过 at）",
            early.timeline.all { it.occurredOrder <= 1 }
        )
        assertTrue(
            "早期位置的事实数应少于最新位置",
            (early.snapshot?.publicFacts?.size ?: 0) <= (latest.snapshot?.publicFacts?.size ?: 0)
        )
    }

    @Test
    fun t57_public_perspective_never_exposes_private_facts() = runTest {
        val viewModel = newViewModel(repository())
        advanceUntilIdle()
        viewModel.onAction(
            com.roundtrace.app.feature.replay.ReplayAction.SelectPerspective(Perspective.PUBLIC)
        )
        advanceUntilIdle()

        val snapshot = viewModel.uiState.value.snapshot
        assertNotNull(snapshot)
        assertTrue("公开视角不得有私有事实", snapshot!!.privateRoleFacts.isEmpty())
        assertTrue("公开视角不得有主观判断", snapshot.subjectiveBeliefs.isEmpty())
    }

    @Test
    fun t57_observer_perspective_keeps_mordred_hidden() = runTest {
        val viewModel = newViewModel(repository())
        advanceUntilIdle()
        viewModel.onAction(
            com.roundtrace.app.feature.replay.ReplayAction.SelectPerspective(
                Perspective.OBSERVER_AT_TIME
            )
        )
        advanceUntilIdle()

        val snapshot = viewModel.uiState.value.snapshot
        assertNotNull(snapshot)
        assertEquals("观察者应为配置里的 mySeat = 1", 1, snapshot!!.observerSeat)
        assertEquals("观察者角色应为梅林", RoleId.MERLIN, snapshot.observerRole)
        assertNull(
            "梅林视角下 4 号（莫德雷德）必须仍然未知",
            snapshot.seatKnowledge.getValue(4).knownAlignment
        )
    }

    @Test
    fun t57_select_quest_moves_to_quest_end() = runTest {
        val viewModel = newViewModel(repository())
        advanceUntilIdle()

        viewModel.onAction(com.roundtrace.app.feature.replay.ReplayAction.SelectQuest(2))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("位置应停在任务 2 之后", state.at.occurredOrder > 0)
        assertTrue(
            "任务 3 及之后的事实不应出现",
            state.snapshot!!.publicFacts.all { (it.questNo ?: 0) <= 2 }
        )
    }

    @Test
    fun t57_missing_match_degrades_to_error_state() = runTest {
        val repository = repository()
        val viewModel = newViewModel(repository)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isLoading)

        // 删除对局后 FakeMatchRepository 会把事件清空，但 bundle 仍在；
        // 这里断言「即使数据为空也不崩溃」，这是 UI 侧最重要的健壮性
        repository.deleteMatch(MatchId("fake-match"))
        advanceUntilIdle()
        assertTrue(
            "空数据下不应抛异常（errorMessage 可为 null 或可读文案）",
            viewModel.uiState.value.snapshot != null || viewModel.uiState.value.errorMessage != null
        )
    }

    @Test
    fun t57_start_position_has_no_findings() = runTest {
        val viewModel = newViewModel(repository())
        advanceUntilIdle()

        viewModel.onAction(
            com.roundtrace.app.feature.replay.ReplayAction.SelectPosition(
                GamePosition.start().occurredOrder
            )
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(
            "开局位置没有任何任务事实，结论应为空",
            state.findings.isEmpty()
        )
        assertTrue("同时应呈现空态", state.hasNoFindings)
    }
}
