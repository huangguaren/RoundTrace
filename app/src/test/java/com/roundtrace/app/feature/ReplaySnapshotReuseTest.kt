package com.roundtrace.app.feature

import com.roundtrace.app.core.knowledge.AvalonEvidenceAnalyzer
import com.roundtrace.app.core.knowledge.AvalonKnowledgeProvider
import com.roundtrace.app.core.knowledge.KnowledgeProvider
import com.roundtrace.app.core.knowledge.ReplayAnalyzer
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EvidenceFinding
import com.roundtrace.app.core.model.GamePosition
import com.roundtrace.app.core.model.KnowledgeSnapshot
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.ReconstructedMatch
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.SeatId
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.feature.replay.ReplayAction
import com.roundtrace.app.feature.replay.ReplayViewModel
import com.roundtrace.app.util.FakeMatchRepository
import com.roundtrace.app.util.MatchBuilder
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
 * NX-03-PERF（阶段 C2/C3）：复盘快照复用与「不做全局缓存」的证据。
 *
 * 三条要钉住的约束：
 * 1. **快照不能被多算**：`analyzeAt()` 内部会自己建一次快照，如果 ViewModel 已经建过一次，
 *    同一份输入就会被建两次。这里用计数装饰器直接数 `KnowledgeProvider.snapshot` 的调用。
 * 2. **不做跨事件/跨时刻缓存**（B29）：时刻、视角、修订、撤销后必须重新计算，
 *    而且内容要跟着刷新——不是复用上一份快照。
 * 3. **隐私闸门不因复用而松动**：未揭晓不得打开上帝视角；历史时刻不得出现后来的事实。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplaySnapshotReuseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val registry = DefaultGameRulesRegistry()
    private val realProvider = AvalonKnowledgeProvider(registry.avalonReducer)
    private val realAnalyzer = AvalonEvidenceAnalyzer()

    // ————————————————————————————————————————————————————————
    // 计数装饰器
    // ————————————————————————————————————————————————————————

    private class CountingProvider(
        private val delegate: KnowledgeProvider
    ) : KnowledgeProvider by delegate {

        /** 每次快照的视角（用于统计与断言「有没有偷偷建上帝视角」）。 */
        val modes: MutableList<Perspective> = mutableListOf()

        val calls: Int
            get() = modes.size

        fun callsFor(mode: Perspective): Int = modes.count { it == mode }

        override fun snapshot(
            match: ReconstructedMatch,
            observer: SeatId?,
            at: GamePosition,
            mode: Perspective
        ): KnowledgeSnapshot {
            modes += mode
            return delegate.snapshot(match, observer, at, mode)
        }
    }

    private class CountingAnalyzer(
        private val delegate: ReplayAnalyzer
    ) : ReplayAnalyzer by delegate {

        var analyzeCalls: Int = 0
            private set
        var analyzeAtCalls: Int = 0
            private set

        override fun analyze(
            snapshot: KnowledgeSnapshot,
            history: List<MatchEvent>
        ): List<EvidenceFinding> {
            analyzeCalls += 1
            return delegate.analyze(snapshot, history)
        }

        override fun analyzeAt(
            match: ReconstructedMatch,
            observer: SeatId?,
            at: GamePosition,
            mode: Perspective,
            knowledgeProvider: KnowledgeProvider
        ): List<EvidenceFinding> {
            analyzeAtCalls += 1
            return delegate.analyzeAt(match, observer, at, mode, knowledgeProvider)
        }
    }

    // ————————————————————————————————————————————————————————
    // 夹具
    // ————————————————————————————————————————————————————————

    private val matchId = MatchId("fake-match")

    private fun repository(revealed: Boolean = false): FakeMatchRepository {
        val builder = com.roundtrace.app.knowledge.AvalonScenario.fullMatch()
        val scenario = com.roundtrace.app.knowledge.AvalonScenario.match(revealed)
        return FakeMatchRepository(
            config = com.roundtrace.app.knowledge.AvalonScenario.eightPlayerConfig(),
            initialEvents = builder.events(),
            revealedAt = if (revealed) 1_700_000_100_000L else null,
            initialRoles = scenario.roleAssignments
        )
    }

    /**
     * 已揭晓 + 处于刺杀阶段的对局。
     *
     * `canOpenGodView` 同时要求 `revealedAt != null` 与**重放出来的** `status ∈
     * {AWAITING_ASSASSINATION, FINISHED_GOOD, FINISHED_EVIL}`（看的是 replay 状态，不是列上的投影），
     * 所以夹具必须真的打完 3 个成功任务；只设置 `setStatus` 是不够的。
     */
    private fun revealedRepository(): FakeMatchRepository {
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(
                questNo = 1,
                leaderSeat = 1,
                teamSeats = listOf(1, 2, 3),
                failCount = 0,
                observedOutcome = MissionOutcome.SUCCESS
            )
            .questCycle(
                questNo = 2,
                leaderSeat = 2,
                teamSeats = listOf(1, 2, 3, 4),
                failCount = 0,
                observedOutcome = MissionOutcome.SUCCESS
            )
            .questCycle(
                questNo = 3,
                leaderSeat = 3,
                teamSeats = listOf(1, 2, 3, 4),
                failCount = 0,
                observedOutcome = MissionOutcome.SUCCESS
            )
        val revealedAt = 1_700_000_100_000L
        val reconstructed = builder.build(revealedAtEpochMs = revealedAt)
        return FakeMatchRepository(
            config = reconstructed.config,
            initialEvents = builder.events(),
            revealedAt = revealedAt,
            initialRoles = reconstructed.roleAssignments
        )
    }

    private fun newViewModel(
        repository: FakeMatchRepository,
        provider: CountingProvider,
        analyzer: CountingAnalyzer
    ): ReplayViewModel = ReplayViewModel(
        matchId = MatchId("fake-match"),
        repository = repository,
        knowledgeProvider = provider,
        analyzer = analyzer
    )

    // ————————————————————————————————————————————————————————
    // 1) PUBLIC：每次有效输入只建 1 次快照
    // ————————————————————————————————————————————————————————

    @Test
    fun `PUBLIC 视角每次输入只创建一次快照`() = runTest {
        val provider = CountingProvider(realProvider)
        val analyzer = CountingAnalyzer(realAnalyzer)
        val viewModel = newViewModel(repository(), provider, analyzer)
        advanceUntilIdle()

        assertEquals(
            "PUBLIC 视角应复用公开探测得到的快照：1 次 snapshot，实际=${provider.modes}",
            1,
            provider.calls
        )
        assertEquals(1, provider.callsFor(Perspective.PUBLIC))
        assertEquals(
            "PUBLIC 下的公开探测就是最终快照，不需要第二次计算",
            0,
            provider.callsFor(Perspective.OBSERVER_AT_TIME)
        )
        assertEquals("分析入口应只调用一次 analyze", 1, analyzer.analyzeCalls)
        assertEquals(
            "不得走 analyzeAt（它会自己再建一次快照）",
            0,
            analyzer.analyzeAtCalls
        )
        assertNotNull(viewModel.uiState.value.snapshot)
        assertTrue(
            "复盘必须有结论（否则复用测试没有意义）",
            viewModel.uiState.value.findings.isNotEmpty()
        )
    }

    @Test
    fun `快速重复选择同一视角不产生无意义重算`() = runTest {
        val provider = CountingProvider(realProvider)
        val analyzer = CountingAnalyzer(realAnalyzer)
        val viewModel = newViewModel(repository(), provider, analyzer)
        advanceUntilIdle()
        val baseline = provider.calls

        // MutableStateFlow 对相同值不发射 → 同样的视角点击不应触发重算
        repeat(3) {
            viewModel.onAction(ReplayAction.SelectPerspective(Perspective.PUBLIC))
            advanceUntilIdle()
        }

        assertEquals(
            "重复选择同一视角不得重建快照（实际=${provider.modes}）",
            baseline,
            provider.calls
        )
    }

    // ————————————————————————————————————————————————————————
    // 2) 个人 / 上帝视角：最多「公开探测 + 目标视角」2 次
    // ————————————————————————————————————————————————————————

    @Test
    fun `个人视角最多创建两次快照（公开探测 + 目标视角）`() = runTest {
        val provider = CountingProvider(realProvider)
        val analyzer = CountingAnalyzer(realAnalyzer)
        val viewModel = newViewModel(repository(), provider, analyzer)
        advanceUntilIdle()
        val baseline = provider.calls

        viewModel.onAction(ReplayAction.SelectPerspective(Perspective.OBSERVER_AT_TIME))
        advanceUntilIdle()

        val delta = provider.calls - baseline
        assertEquals(
            "切到个人视角应恰好新增 2 次快照（公开探测 + 目标视角），实际新增=$delta，全部=${provider.modes}",
            2,
            delta
        )
        assertEquals(
            "个人视角只应建 1 次（探测复用 PUBLIC，不重复建 OBSERVER）",
            1,
            provider.callsFor(Perspective.OBSERVER_AT_TIME)
        )
        assertEquals(
            "初始 PUBLIC 1 次 + 切换时的公开探测 1 次",
            2,
            provider.callsFor(Perspective.PUBLIC)
        )
        assertEquals(
            "观察者视角内容必须正确（1 号梅林）",
            RoleId.MERLIN,
            viewModel.uiState.value.snapshot?.observerRole
        )
    }

    @Test
    fun `已揭晓时上帝视角最多创建两次快照`() = runTest {
        val provider = CountingProvider(realProvider)
        val analyzer = CountingAnalyzer(realAnalyzer)
        val viewModel = newViewModel(revealedRepository(), provider, analyzer)
        advanceUntilIdle()
        assertTrue(
            "已揭晓时 must allow 上帝视角",
            viewModel.uiState.value.canOpenGodView
        )
        val baseline = provider.calls

        viewModel.onAction(ReplayAction.SelectPerspective(Perspective.GOD_AFTER_REVEAL))
        advanceUntilIdle()

        val delta = provider.calls - baseline
        assertTrue(
            "上帝视角最多 2 次快照，实际新增=$delta，全部=${provider.modes}",
            delta <= 2
        )
        assertEquals(Perspective.GOD_AFTER_REVEAL, viewModel.uiState.value.mode)
        assertTrue(
            "上帝视角应能看到真实阵营",
            viewModel.uiState.value.snapshot!!.seatKnowledge.values
                .any { it.knownAlignment != null }
        )
    }

    // ————————————————————————————————————————————————————————
    // 3) 时刻切换 → 重新计算（B29：不做跨时刻缓存）
    // ————————————————————————————————————————————————————————

    @Test
    fun `切换时刻必须重新计算且内容刷新`() = runTest {
        val provider = CountingProvider(realProvider)
        val analyzer = CountingAnalyzer(realAnalyzer)
        val viewModel = newViewModel(repository(), provider, analyzer)
        advanceUntilIdle()

        val latest = viewModel.uiState.value
        val latestFacts = latest.snapshot!!.publicFacts.size
        val baseline = provider.calls

        viewModel.onAction(ReplayAction.SelectPosition(1))
        advanceUntilIdle()

        val early = viewModel.uiState.value
        assertTrue(
            "切换时刻必须重建快照（B29：不做跨时刻缓存），实际新增=${provider.calls - baseline}",
            provider.calls > baseline
        )
        assertEquals(1, early.at.occurredOrder)
        assertTrue(
            "早期位置的事实必须少于最新位置：早期=$latestFacts",
            early.snapshot!!.publicFacts.size < latestFacts
        )
        assertTrue(
            "时间线必须随位置收敛",
            early.timeline.all { it.occurredOrder <= 1 }
        )
    }

    // ————————————————————————————————————————————————————————
    // 4) 修订 / 撤销 → 重新计算且内容刷新
    // ————————————————————————————————————————————————————————

    @Test
    fun `修订事件后重新计算并刷新内容`() = runTest {
        val repository = repository()
        val provider = CountingProvider(realProvider)
        val analyzer = CountingAnalyzer(realAnalyzer)
        val viewModel = newViewModel(repository, provider, analyzer)
        advanceUntilIdle()

        val target = repository.bundle()!!.events
            .filterIsInstance<MissionResolved>()
            .first { it.questNo == 1 }
        val before = viewModel.uiState.value
        val beforeFactSummary = before.snapshot!!.publicFacts
            .first { it.id == target.eventId.raw }
            .summary
        val baseline = provider.calls

        // 修订：把第 1 任务的失败牌数改成 2（触发报告刷新）
        repository.reviseEvent(
            matchId = matchId,
            targetEventId = target.eventId,
            replacement = target.copy(eventId = EventId("rev-m1"), failCount = 2)
        )
        advanceUntilIdle()

        val after = viewModel.uiState.value
        assertTrue(
            "修订后必须重新计算（B29：不缓存跨 revision 的分析结果），实际新增=${provider.calls - baseline}",
            provider.calls > baseline
        )
        val afterFactSummary = after.snapshot!!.publicFacts
            .firstOrNull { it.questNo == 1 && it.summary.contains("失败牌") }
            ?.summary
        assertNotNull("第 1 任务结果事实必须仍在", afterFactSummary)
        assertTrue(
            "事实摘要必须刷新为新值，before=$beforeFactSummary，after=$afterFactSummary",
            afterFactSummary != beforeFactSummary
        )
        assertFalse("修订不应把页面拖进错误态", after.errorMessage != null)
    }

    @Test
    fun `撤销事件后重新计算并刷新内容`() = runTest {
        val repository = repository()
        val provider = CountingProvider(realProvider)
        val analyzer = CountingAnalyzer(realAnalyzer)
        val viewModel = newViewModel(repository, provider, analyzer)
        advanceUntilIdle()

        val before = viewModel.uiState.value
        val beforeQuest1Mission = before.snapshot!!.publicFacts
            .count { it.questNo == 1 && it.summary.contains("失败牌") }
        assertEquals("撤销前第 1 任务有 1 条结果事实", 1, beforeQuest1Mission)
        val baseline = provider.calls

        val target = repository.bundle()!!.events
            .filterIsInstance<MissionResolved>()
            .first { it.questNo == 1 }
        repository.retractEvent(matchId, target.eventId)
        advanceUntilIdle()

        val after = viewModel.uiState.value
        assertTrue(
            "撤销后必须重新计算，实际新增=${provider.calls - baseline}",
            provider.calls > baseline
        )
        assertEquals(
            "撤销后的快照不得再出现被撤销事件的事实",
            0,
            after.snapshot!!.publicFacts.count { it.id == target.eventId.raw }
        )
        assertTrue(
            "时间线同样不得再出现被撤销事件",
            after.timeline.none { it.sourceEventIds.any { id -> id == target.eventId } }
        )
    }

    // ————————————————————————————————————————————————————————
    // 5) 隐私闸门不因复用而松动
    // ————————————————————————————————————————————————————————

    @Test
    fun `未揭晓时拒绝上帝视角且不构建上帝快照`() = runTest {
        val provider = CountingProvider(realProvider)
        val analyzer = CountingAnalyzer(realAnalyzer)
        val viewModel = newViewModel(repository(revealed = false), provider, analyzer)
        advanceUntilIdle()

        assertFalse("未揭晓时不得允许上帝视角", viewModel.uiState.value.canOpenGodView)
        viewModel.onAction(ReplayAction.SelectPerspective(Perspective.GOD_AFTER_REVEAL))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("应标记被拒，供 UI 提示", state.godViewBlocked)
        assertEquals("被拒后模式不得改变", Perspective.PUBLIC, state.mode)
        assertEquals(
            "被拒时绝不允许构建上帝视角快照，实际=${provider.modes}",
            0,
            provider.callsFor(Perspective.GOD_AFTER_REVEAL)
        )
        assertTrue(
            "公开视角不得有私有事实",
            state.snapshot!!.privateRoleFacts.isEmpty()
        )
    }

    @Test
    fun `历史时刻的个人视角不泄漏后来事实`() = runTest {
        val provider = CountingProvider(realProvider)
        val analyzer = CountingAnalyzer(realAnalyzer)
        val viewModel = newViewModel(repository(), provider, analyzer)
        advanceUntilIdle()

        // 退到第 1 个任务中段，再看个人视角
        viewModel.onAction(ReplayAction.SelectPosition(3))
        advanceUntilIdle()
        viewModel.onAction(ReplayAction.SelectPerspective(Perspective.OBSERVER_AT_TIME))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(3, state.at.occurredOrder)
        assertTrue(
            "该时刻之后的任务事实不得出现，实际=${state.snapshot!!.publicFacts.map { it.questNo }}",
            state.snapshot!!.publicFacts.all { (it.questNo ?: 0) <= 1 }
        )
        assertTrue(
            "时间线不得越过时间边界",
            state.timeline.all { it.occurredOrder <= 3 }
        )
        assertTrue(
            "个人视角仍受时间边界约束（结论不得引用后来的任务）",
            state.findings.all { (it.relatedQuestNo ?: 0) <= 1 }
        )
    }
}
