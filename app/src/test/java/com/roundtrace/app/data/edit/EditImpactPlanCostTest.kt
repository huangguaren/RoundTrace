package com.roundtrace.app.data.edit

import com.roundtrace.app.core.data.MatchBundle
import com.roundtrace.app.core.data.edit.ActiveEventToken
import com.roundtrace.app.core.data.edit.EditImpactPlanner
import com.roundtrace.app.core.data.edit.EditPlanRejection
import com.roundtrace.app.core.data.edit.EditPlanResult
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventType
import com.roundtrace.app.core.model.GameState
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.MatchSummary
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.ReconstructedMatch
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.GameReducer
import com.roundtrace.app.core.rules.ReductionResult
import com.roundtrace.app.util.MatchBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2：无变化编辑的**代价证据**（不是耗时排名，是结构性事实）。
 *
 * ## 为什么用「规则重放次数」而不是毫秒
 *
 * 上一轮的教训：同一台机器两次跑同一个 stage，median 能差 2 倍（见
 * `docs/audit/performance-next-round.md`）。所以凡是能用**计数**表达的结论就不该用计时表达：
 *
 * - 无变化路径在 `EditImpactPlanner.plan()` 里**早于**任何 `reducer.replay` 返回，
 *   因此规则重放次数必然是 **0**，与机器快慢无关；
 * - 有变化的路径必须重放才能判断「改动是否让对局进入终局」，次数必然 **> 0**。
 *
 * 这两条断言比「快了 X%」有用得多：它们说明新闸门没有把成本搬到别处，
 * 也说明为什么「打开编辑直接保存」不会再有可感知延迟。
 *
 * 计时部分只作为**同机同构建的对照观察**打印出来，不设任何阈值、不当门槛。
 */
class EditImpactPlanCostTest {

    /**
     * 包一层 reducer，只数调用次数，不改任何语义。
     *
     * 注意计数的口径：`ReplayEngine.replay()` 内部走的是 `initialState` + 逐条 `step`，
     * **不调用** `GameReducer.replay()`。所以「规则推进了多少次」要看 `stepCalls`，
     * 只数 `replayCalls` 会得到恒为 0 的假证据。
     */
    private class CountingReducer(private val delegate: GameReducer) : GameReducer {
        var initialStateCalls = 0
        var replayCalls = 0
        var stepCalls = 0

        override fun initialState(match: ReconstructedMatch): GameState {
            initialStateCalls += 1
            return delegate.initialState(match)
        }

        override fun step(state: GameState, event: MatchEvent): ReductionResult {
            stepCalls += 1
            return delegate.step(state, event)
        }

        override fun replay(match: ReconstructedMatch, upToOccurredOrder: Int?): ReductionResult {
            replayCalls += 1
            return delegate.replay(match, upToOccurredOrder)
        }
    }

    private fun bundleOf(builder: MatchBuilder): MatchBundle {
        val match = builder.build()
        return MatchBundle(
            summary = MatchSummary(
                matchId = match.matchId,
                title = "无变化代价测试",
                playerCount = match.config.playerCount,
                status = MatchStatus.IN_PROGRESS,
                rulesetId = match.config.rulesetId,
                updatedAtEpochMs = 0L,
                questsStarted = builder.events().count { it.type == EventType.QUEST_STARTED },
                mySeat = match.config.mySeat
            ),
            config = match.config,
            participants = match.participants,
            roleAssignments = match.roleAssignments,
            events = builder.events(),
            revealedAt = null
        )
    }

    private fun tokensOf(bundle: MatchBundle): List<ActiveEventToken> = bundle.events.map {
        ActiveEventToken(eventId = it.eventId, logicalPosition = it.occurredOrder, revisionSeq = 0)
    }

    private fun twoQuestBundle(): MatchBundle =
        bundleOf(
            MatchBuilder.eightPlayerMerlinMordred()
                .questCycle(1, 1, listOf(1, 2, 3), failCount = 1)
                .questCycle(2, 2, listOf(2, 3, 4, 5), failCount = 1)
        )

    private inline fun <reified T : MatchEvent> MatchBundle.event(
        predicate: (T) -> Boolean
    ): T = events.filterIsInstance<T>().first(predicate)

    /** 与 `EditImpactPlannerTest` 同口径：替换事件必须换一个 id，否则会先撞 `DUPLICATE_EVENT_ID`。 */
    private fun TeamProposed.replaced(teamSeats: List<Int> = this.teamSeats): TeamProposed =
        copy(eventId = EventId("cost-replacement-${eventId.raw}"), occurredOrder = 0, teamSeats = teamSeats)

    private fun MissionResolved.replaced(failCount: Int?): MissionResolved =
        copy(
            eventId = EventId("cost-replacement-${eventId.raw}"),
            occurredOrder = 0,
            failCount = failCount
        )

    // ————————————————————————————————————————————————————————
    // 结构性证据（与机器无关）
    // ————————————————————————————————————————————————————————

    @Test
    fun noChangePlanDoesNotTriggerAnyRulesReplay() {
        val counter = CountingReducer(DefaultGameRulesRegistry().avalonReducer)
        val planner = EditImpactPlanner(counter)
        val bundle = twoQuestBundle()
        val target = bundle.event<TeamProposed> { it.questNo == 1 }

        val result = planner.plan(
            bundle = bundle,
            expectedActive = tokensOf(bundle),
            targetEventId = target.eventId,
            replacement = target.replaced(target.teamSeats)
        )

        assertEquals(
            "内容未改时必须直接拒绝",
            EditPlanRejection.NO_BUSINESS_CHANGE,
            (result as? EditPlanResult.Rejected)?.reason
        )
        assertEquals("无变化路径不得触发任何规则重放", 0, counter.replayCalls)
        assertEquals("无变化路径不得逐条推进规则", 0, counter.stepCalls)
        assertEquals("无变化路径不得构造初始状态", 0, counter.initialStateCalls)
    }

    @Test
    fun noChangePlanOnMissionAlsoSkipsReplay() {
        val counter = CountingReducer(DefaultGameRulesRegistry().avalonReducer)
        val planner = EditImpactPlanner(counter)
        val bundle = twoQuestBundle()
        val mission = bundle.event<MissionResolved> { it.questNo == 1 }

        val result = planner.plan(
            bundle = bundle,
            expectedActive = tokensOf(bundle),
            targetEventId = mission.eventId,
            replacement = mission.replaced(failCount = mission.failCount)
        )

        assertEquals(
            EditPlanRejection.NO_BUSINESS_CHANGE,
            (result as? EditPlanResult.Rejected)?.reason
        )
        assertEquals("任务结果无变化同样不得重放", 0, counter.replayCalls)
        assertEquals("任务结果无变化同样不得逐条推进", 0, counter.stepCalls)
    }

    @Test
    fun changedMissionPlanReplaysToDetectFinalization() {
        val counter = CountingReducer(DefaultGameRulesRegistry().avalonReducer)
        val planner = EditImpactPlanner(counter)
        val bundle = twoQuestBundle()
        val mission = bundle.event<MissionResolved> { it.questNo == 1 }

        // 1 → 0：把已失败的任务改成成功，必须重放才知道是否改变终局
        val result = planner.plan(
            bundle = bundle,
            expectedActive = tokensOf(bundle),
            targetEventId = mission.eventId,
            replacement = mission.replaced(failCount = 0)
        )

        assertTrue("真实修改应当算出计划，实际 $result", result is EditPlanResult.Ready)
        assertTrue(
            "有变化的路径必须重放规则，实际 stepCalls=${counter.stepCalls}",
            counter.stepCalls > 0
        )
        assertTrue(
            "重放必须从初始状态开始，实际 initialStateCalls=${counter.initialStateCalls}",
            counter.initialStateCalls > 0
        )
    }

    // ————————————————————————————————————————————————————————
    // 同机对照观察（不设阈值、不作门槛）
    // ————————————————————————————————————————————————————————

    @Test
    fun printPlanCostComparison() {
        val planner = EditImpactPlanner(DefaultGameRulesRegistry().avalonReducer)
        val bundle = twoQuestBundle()
        val tokens = tokensOf(bundle)
        val proposal = bundle.event<TeamProposed> { it.questNo == 1 }
        val mission = bundle.event<MissionResolved> { it.questNo == 1 }

        val noChange = { planner.plan(bundle, tokens, proposal.eventId, proposal.replaced(proposal.teamSeats)) }
        val changed = { planner.plan(bundle, tokens, mission.eventId, mission.replaced(failCount = 0)) }

        val noChangeStats = measure(noChange)
        val changedStats = measure(changed)

        println(
            "P2-PERF plan_no_change  median=${noChangeStats.medianNs}ns p95=${noChangeStats.p95Ns}ns " +
                "min=${noChangeStats.minNs}ns max=${noChangeStats.maxNs}ns samples=${noChangeStats.samples}"
        )
        println(
            "P2-PERF plan_changed    median=${changedStats.medianNs}ns p95=${changedStats.p95Ns}ns " +
                "min=${changedStats.minNs}ns max=${changedStats.maxNs}ns samples=${changedStats.samples}"
        )
        println("P2-PERF fixture events=${bundle.events.size} quests=2 playerCount=8")

        // 只做「两条路径都能跑完」的完整性检查，不对耗时下任何结论
        assertTrue(noChangeStats.medianNs > 0 && changedStats.medianNs > 0)
    }

    private data class Stats(val medianNs: Long, val p95Ns: Long, val minNs: Long, val maxNs: Long, val samples: Int)

    private fun measure(block: () -> Any?, warmup: Int = 5, repeat: Int = 40): Stats {
        repeat(warmup) { block() }
        val samples = LongArray(repeat) {
            val start = System.nanoTime()
            block()
            System.nanoTime() - start
        }.sorted()
        return Stats(
            medianNs = samples[repeat / 2],
            p95Ns = samples[(repeat * 95 / 100).coerceAtMost(repeat - 1)],
            minNs = samples.first(),
            maxNs = samples.last(),
            samples = repeat
        )
    }
}
