package com.roundtrace.app.feature

import com.roundtrace.app.core.knowledge.AvalonEvidenceAnalyzer
import com.roundtrace.app.core.knowledge.AvalonKnowledgeProvider
import com.roundtrace.app.core.knowledge.KnowledgeProvider
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.GamePosition
import com.roundtrace.app.core.model.KnowledgeSnapshot
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.ReconstructedMatch
import com.roundtrace.app.core.model.SeatId
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.replay.VisibleEventPolicy
import com.roundtrace.app.knowledge.AvalonScenario
import com.roundtrace.app.util.MatchBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * NX-03-PERF 的可测量证据（PRD §4「禁止捏造百分比」）。
 *
 * 这个测试**不做断言式的性能门槛**（在共享 CI 机器上必然 flaky），
 * 只做两件事：
 * 1. 用固定夹具在**同一进程、同一构建模式**下跑固定重复次数，把 median / p95 打印出来；
 * 2. 断言与性能无关但同样重要的结构事实：`analyze()` 期间**不创建快照**
 *    （快照由调用方建一次并复用），因此「快照调用次数」这一项是可数的、可回归的。
 *
 * 数据由人写进 `docs/audit/performance-next-round.md`；**只报告同环境对照，不承诺提升幅度**。
 *
 * 夹具两档（覆盖「小局」与「较长历史」）：
 * - `small`：8 人局 2 个任务（约 10 条事件）；
 * - `long`：8 人局完整 5 任务 + 40 条公开发言（约 70 条事件）。
 */
class ReplayAnalyzePerformanceTest {

    private val registry = DefaultGameRulesRegistry()
    private val provider = AvalonKnowledgeProvider(registry.avalonReducer)
    private val analyzer = AvalonEvidenceAnalyzer()

    /** 统计用的小工具：只对已排序样本做索引，不做任何「估计」。 */
    private data class Stats(
        val samples: Int,
        val medianNs: Long,
        val p95Ns: Long,
        val minNs: Long,
        val maxNs: Long
    )

    private fun stats(durations: List<Long>): Stats {
        val sorted = durations.sorted()
        fun at(p: Double): Long {
            if (sorted.isEmpty()) return 0L
            val idx = (ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
            return sorted[idx]
        }
        return Stats(
            samples = sorted.size,
            medianNs = at(0.50),
            p95Ns = at(0.95),
            minNs = sorted.firstOrNull() ?: 0L,
            maxNs = sorted.lastOrNull() ?: 0L
        )
    }

    private fun measure(repeat: Int, block: () -> Unit): Stats {
        // 预热：把 JIT / 类加载的成本挤出测量窗口（次数少、只为稳定，不影响结论）
        repeat(WARMUP) { block() }
        val durations = ArrayList<Long>(repeat)
        repeat(repeat) {
            val started = System.nanoTime()
            block()
            durations += System.nanoTime() - started
        }
        return stats(durations)
    }

    private fun report(
        fixture: String,
        label: String,
        eventCount: Int,
        stats: Stats,
        snapshotCallsPerRun: String
    ) {
        // 单行、稳定前缀，便于从测试报告里抓取后写进 docs/audit
        println(
            "[PERF] fixture=$fixture stage=$label events=$eventCount samples=${stats.samples} " +
                "median_ns=${stats.medianNs} p95_ns=${stats.p95Ns} " +
                "min_ns=${stats.minNs} max_ns=${stats.maxNs} snapshot_calls_per_run=$snapshotCallsPerRun"
        )
    }

    private fun smallFixture(): ReconstructedMatch =
        MatchBuilder.eightPlayerMerlinMordred()
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
                failCount = 1,
                observedOutcome = MissionOutcome.FAILED
            )
            .build()

    private fun longFixture(): ReconstructedMatch {
        val builder = AvalonScenario.fullMatch()
        // 追加 40 条公开发言，把历史拉到「一局完整 + 现场发言」的真实量级
        repeat(40) { index ->
            builder.playerClaimed(
                speakerSeat = (index % 8) + 1,
                claimType = when (index % 3) {
                    0 -> ClaimType.SUSPECT
                    1 -> ClaimType.PROTECT
                    else -> ClaimType.ROLE_CLAIM
                },
                questNo = (index % 5) + 1,
                targetSeats = listOf(((index + 1) % 8) + 1),
                text = "现场发言 #$index"
            )
        }
        return builder.build()
    }

    /** 与 `ReplayViewModel.buildUiState` 相同的可见性 + 时间裁剪口径。 */
    private fun visibleHistory(
        match: ReconstructedMatch,
        at: GamePosition,
        mode: Perspective
    ): List<MatchEvent> = VisibleEventPolicy.forPerspective(
        events = match.events.filter { !it.retracted },
        mode = mode,
        observerSeat = match.config.mySeat
    ).filter { it.occurredOrder <= at.occurredOrder }

    private fun atEnd(match: ReconstructedMatch): GamePosition =
        GamePosition(
            occurredOrder = match.events.maxOfOrNull { it.occurredOrder } ?: 0,
            questNo = null,
            proposalNo = null
        )

    /**
     * 计数装饰器：`analyze()` 期间**不得**再建快照。
     *
     * 这条断言是「快照复用」的结构证据——与耗时无关，因此在任何机器上都稳定。
     */
    private class CountingProvider(private val delegate: KnowledgeProvider) : KnowledgeProvider by delegate {
        var calls: Int = 0
            private set

        override fun snapshot(
            match: ReconstructedMatch,
            observer: SeatId?,
            at: GamePosition,
            mode: Perspective
        ): KnowledgeSnapshot {
            calls += 1
            return delegate.snapshot(match, observer, at, mode)
        }
    }

    private fun runFixture(name: String, match: ReconstructedMatch) {
        val at = atEnd(match)
        val history = visibleHistory(match, at, Perspective.PUBLIC)

        // ① 快照构建（每次输入一次，不做跨时刻缓存 —— B29）
        val snapshotStats = measure(REPEAT) {
            provider.snapshot(match, match.config.mySeat, at, Perspective.PUBLIC)
        }

        // ② 分析（复用同一份快照）
        val snapshot = provider.snapshot(match, match.config.mySeat, at, Perspective.PUBLIC)
        val countingProvider = CountingProvider(provider)
        val analyzeStats = measure(REPEAT) {
            analyzer.analyze(snapshot, history)
        }
        // ③ 典型「一次复盘重算」= 公开探测 + 分析
        val recomputeStats = measure(REPEAT) {
            val probe = countingProvider.snapshot(match, match.config.mySeat, at, Perspective.PUBLIC)
            analyzer.analyze(probe, history)
        }

        val findings = analyzer.analyze(snapshot, history)
        assertTrue("$name：夹具必须能产出结论，否则测量没有意义", findings.isNotEmpty())
        assertEquals(
            "$name：probe+analyze 每跑一次只能建 1 次快照（预热 $WARMUP + 测量 $REPEAT 次）",
            WARMUP + REPEAT,
            countingProvider.calls
        )
        assertTrue(
            "$name：事件数必须与夹具描述一致（small<long 才有对照意义）",
            history.isNotEmpty()
        )

        report("$name-snapshot", "snapshot_public", history.size, snapshotStats, "0 (由调用方建)")
        report("$name-analyze", "analyze_public", history.size, analyzeStats, "0")
        report("$name-recompute", "probe+analyze", history.size, recomputeStats, "1")

        // ④ 同环境对照：`analyzeAt(...)` 会**自己再建一次快照**。
        // 这条不是「优化前后」的历史数据，而是**同一次提交内两条路径的结构差异**：
        // 若页面用「公开探测 + analyzeAt」，每次输入会建 2 次快照；用「探测 + analyze」只建 1 次。
        val alternativeProvider = CountingProvider(provider)
        val alternativeStats = measure(REPEAT) {
            analyzer.analyzeAt(
                match = match,
                observer = match.config.mySeat,
                at = at,
                mode = Perspective.PUBLIC,
                knowledgeProvider = alternativeProvider
            )
        }
        assertEquals(
            "$name：analyzeAt 路径每次运行自建 1 次快照（预热 $WARMUP + 测量 $REPEAT 次）",
            WARMUP + REPEAT,
            alternativeProvider.calls
        )
        report("$name-alternative", "analyzeAt(自建快照)", history.size, alternativeStats, "1 (analyzeAt 内部)")
    }

    @Test
    fun `测量小局与较长历史的快照与分析耗时`() {
        val smallEvents = smallFixture().events.size
        val longEvents = longFixture().events.size
        assertTrue(
            "较长历史夹具的事件数必须多于小局：small=$smallEvents long=$longEvents",
            longEvents > smallEvents
        )

        runFixture("small", smallFixture())
        runFixture("long", longFixture())
    }

    /**
     * 结构性断言（与耗时无关，任何机器都稳定）：
     * PUBLIC 视角下每次有效输入只建 1 次快照，`analyze()` 不建快照。
     *
     * 这条是 C2 的「快照调用次数」证据，配合 `docs/audit/performance-next-round.md` 阅读。
     */
    @Test
    fun `analyze 不创建快照且公开视角快照次数为一`() {
        val match = longFixture()
        val at = atEnd(match)
        val history = visibleHistory(match, at, Perspective.PUBLIC)
        val countingProvider = CountingProvider(provider)

        val snapshot = countingProvider.snapshot(match, match.config.mySeat, at, Perspective.PUBLIC)
        val findings = analyzer.analyze(snapshot, history)

        assertEquals("PUBLIC 视角 1 次输入 = 1 次快照", 1, countingProvider.calls)
        assertTrue(findings.isNotEmpty())
        assertTrue(
            "PUBLIC 视角不得含私有事实",
            snapshot.privateRoleFacts.isEmpty()
        )
        assertTrue(
            "PUBLIC 视角不得含主观判断",
            snapshot.subjectiveBeliefs.isEmpty()
        )
        assertTrue(
            "隐私闸门：公开视角的结论必须是 PUBLIC 可见事件",
            VisibleEventPolicy.forPerspective(
                events = history,
                mode = Perspective.PUBLIC,
                observerSeat = match.config.mySeat
            ).size == history.size
        )
    }

    private companion object {
        /** 每个 stage 的重复次数（≥30 是本轮要求）。 */
        const val REPEAT = 40

        /** 预热次数（只为把 JIT 成本挤出测量窗口）。 */
        const val WARMUP = 5
    }
}
