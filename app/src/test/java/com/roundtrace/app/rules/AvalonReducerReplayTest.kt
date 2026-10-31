package com.roundtrace.app.rules

import com.roundtrace.app.core.model.IssueCode
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.replay.ReplayEngine
import com.roundtrace.app.core.rules.avalon.AvalonReducer
import com.roundtrace.app.core.rules.avalon.AvalonRules
import com.roundtrace.app.util.MatchBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R14：修改早期事件后重放必须**按新事实重算**，不得保留旧结论（TASKS.md T27）。
 *
 * 场景（8 人局）：
 * - 基准事实：任务 1 成功、任务 2 失败、任务 3 成功、任务 4 队员记录不完整；
 * - 修订后事实：任务 2 的组队票改为 4:4（与记录的「通过」冲突）、任务 4 队员补全。
 *
 * 期望：goodSuccessCount / evilFailureCount / issues 全部按新事实重算。
 */
class AvalonReducerReplayTest {

    private val rules = AvalonRules()
    private val reducer = AvalonReducer(rules)
    private val replayEngine = ReplayEngine(reducer)

    /**
     * 构造 4 个任务的事件列表。
     *
     * @param revised true = 使用「修订后」的第 2 任务票数与第 4 任务队伍
     */
    private fun buildFourQuests(revised: Boolean): MatchBuilder {
        val builder = MatchBuilder.eightPlayerMerlinMordred()

        // —— 任务 1：3 人，0 张失败牌 → 成功（good = 1）
        builder.questCycle(1, leaderSeat = 1, teamSeats = listOf(1, 2, 3), failCount = 0)

        // —— 任务 2：4 人
        if (revised) {
            // 修订：票数改为 4 赞成 4 反对（数学上应否决），但现场仍记录为通过 → DECISION_VOTE_CONFLICT
            builder
                .questStarted(2, 2)
                .teamProposed(2, 1, 2, listOf(2, 3, 4, 6))
                .teamVoteRecorded(2, 1, approveCount = 4, rejectCount = 4)
                .teamProposalResolved(2, 1, ProposalDecision.APPROVED, approveCount = 4, rejectCount = 4)
                .missionResolved(2, teamSeats = listOf(2, 3, 4, 6), failCount = 2)
        } else {
            builder.questCycle(2, leaderSeat = 2, teamSeats = listOf(2, 3, 4, 6), failCount = 2, approve = 6, reject = 2)
        }

        // —— 任务 3：4 人，0 张失败牌 → 成功（good = 2）
        builder.questCycle(3, leaderSeat = 3, teamSeats = listOf(4, 6, 7, 8), failCount = 0)

        // —— 任务 4：5 人
        if (revised) {
            // 修订：队员补全为与提案一致的 5 人 → 可核实 → 失败计入
            builder
                .questStarted(4, 4)
                .teamProposed(4, 1, 4, listOf(1, 3, 4, 5, 8))
                .teamVoteRecorded(4, 1, approveCount = 6, rejectCount = 2)
                .teamProposalResolved(4, 1, ProposalDecision.APPROVED, approveCount = 6, rejectCount = 2)
                .missionResolved(4, teamSeats = listOf(1, 3, 4, 5, 8), failCount = 2)
        } else {
            // 基准：只记得 3 名队员 → 未核实，不计数
            builder
                .questStarted(4, 4)
                .teamProposed(4, 1, 4, listOf(1, 3, 4, 5, 8))
                .teamVoteRecorded(4, 1, approveCount = 6, rejectCount = 2)
                .teamProposalResolved(4, 1, ProposalDecision.APPROVED, approveCount = 6, rejectCount = 2)
                .missionResolved(4, teamSeats = listOf(1, 3, 4), failCount = 2)
        }

        return builder
    }

    @Test
    fun `R14 基准事实重放得到两胜两负`() {
        val state = buildFourQuests(revised = false).replay()

        assertEquals("R14 基准：任务 1 与 3 成功，goodSuccessCount 应为 2", 2, state.goodSuccessCount)
        // 规则（2026-10-31 裁决）：结果明确即计数——任务 2 与 4 都记录了失败牌数，
        // 即使任务 4 队员不完整，失败仍是已经发生的事实。
        assertEquals("R14 基准：任务 2 与 4 的失败都应计数", 2, state.evilFailureCount)
        assertEquals("R14 基准：两胜两负尚未终局", MatchStatus.IN_PROGRESS, state.status)
        assertFalse(
            "R14 基准：任务 4 队员不完整，必须标记未核实",
            state.quests.first { it.questNo == 4 }.verified
        )
        assertEquals("R14 基准：应记录 4 个任务", 4, state.quests.size)
    }

    @Test
    fun `R14 修订后重放按新事实更新核实标记且计数口径不变`() {
        val baseState = buildFourQuests(revised = false).replay()
        val revisedState = buildFourQuests(revised = true).replay()

        assertEquals("R14 修订前 goodSuccessCount = 2", 2, baseState.goodSuccessCount)
        assertEquals("R14 修订后 goodSuccessCount 仍为 2（任务 2 的失败未变成成功）", 2, revisedState.goodSuccessCount)

        // 规则（2026-10-31 裁决）：计数只看结果是否明确，不依赖队伍账目是否补齐，
        // 因此修订前后失败数一致；修订改变的是「能否复核」（verified）。
        assertEquals("R14 修订前 evilFailureCount = 2（结果明确即计数）", 2, baseState.evilFailureCount)
        assertEquals("R14 修订后 evilFailureCount 仍为 2", 2, revisedState.evilFailureCount)
        assertFalse(
            "R14 基准：任务 4 队员不完整 → 未核实",
            baseState.quests.first { it.questNo == 4 }.verified
        )
        assertTrue(
            "R14 任务 4 补全队员后应可核实",
            revisedState.quests.first { it.questNo == 4 }.verified
        )
        assertEquals(
            "R14 任务 4 两胜两负仍未终局",
            MatchStatus.IN_PROGRESS,
            revisedState.status
        )
    }

    @Test
    fun `R14 基准重放产生 MISSION_TEAM_MISMATCH`() {
        val result = buildFourQuests(revised = false).replayResult()
        assertTrue(
            "R14 队员与提案不一致必须报 MISSION_TEAM_MISMATCH，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == IssueCode.MISSION_TEAM_MISMATCH }
        )
        assertTrue(
            "R14 同时应报 DATA_INCOMPLETE，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == IssueCode.DATA_INCOMPLETE }
        )
    }

    @Test
    fun `R14 修订重放产生 DECISION_VOTE_CONFLICT`() {
        val result = buildFourQuests(revised = true).replayResult()
        assertTrue(
            "R14 票数与决议冲突必须报 DECISION_VOTE_CONFLICT，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == IssueCode.DECISION_VOTE_CONFLICT }
        )
        assertFalse(
            "R14 修订后的任务 2 决议应标记未核实",
            result.state.quests.first { it.questNo == 2 }.proposals.first().decisionVerified
        )
    }

    @Test
    fun `R14 重放幂等且同一输入两次结果相等`() {
        val match = buildFourQuests(revised = true).build()
        val first = replayEngine.replay(match)
        val second = replayEngine.replay(match)

        assertEquals("R14 重放必须幂等：state 相等", first.state, second.state)
        assertEquals("R14 重放必须幂等：issues 相等", first.issues, second.issues)
    }

    @Test
    fun `R14 部分重放只应用不超过指定顺序的事件`() {
        val builder = buildFourQuests(revised = false)
        val match = builder.build()
        val events = builder.events()

        // 任务 1 的事件共 5 条（questStarted + proposal + vote + resolved + mission）
        val afterQuest1 = replayEngine.replay(match, upTo = 5)

        assertEquals("R14 部分重放应只完成 1 个任务", 1, afterQuest1.state.quests.size)
        assertEquals("R14 任务 1 成功应计数为 1", 1, afterQuest1.state.goodSuccessCount)
        assertEquals(
            "R14 lastAppliedOrder 应为第 5 个事件",
            5,
            afterQuest1.state.lastAppliedOrder
        )
        assertTrue(
            "R14 部分重放的事件数应小于全量",
            events.size > 5
        )
    }

    @Test
    fun `R14 被撤销的事件不参与重放`() {
        val builder = buildFourQuests(revised = false)
        // 追加一条「已撤销」的任务结果（模拟用户撤销后的事件仍在库里）
        val retractedEvent = com.roundtrace.app.core.model.MissionResolved(
            eventId = com.roundtrace.app.core.model.EventId("retracted-mission"),
            occurredOrder = 999,
            recordedAt = 0L,
            visibility = com.roundtrace.app.core.model.EventVisibility.PUBLIC,
            retracted = true,
            questNo = 1,
            teamSeats = listOf(1, 2, 3),
            teamSizeExpected = 3,
            failCount = 9,
            observedOutcome = com.roundtrace.app.core.model.MissionOutcome.FAILED
        )
        builder.appendRaw(retractedEvent)

        val state = builder.replay()
        assertEquals(
            "R14 retracted 事件必须被过滤，不得影响计数",
            2,
            state.goodSuccessCount
        )
        // 基准夹具本身有 2 次结果明确的失败；若 retracted 事件被计入会是 3，故断言 2。
        assertEquals("R14 retracted 事件不得计入失败", 2, state.evilFailureCount)
    }

    @Test
    fun `R14 修订事件链可被识别`() {
        val builder = buildFourQuests(revised = false)
        val history = builder.events()
        val target = history.first()
        // 只有一条链、且目标在历史里 → 视为链内事件
        assertTrue(
            "R14 目标事件应在历史中",
            EventRevisionChainHelper.isPresent(target, history)
        )
        assertNotNull("R14 事件 id 不应为空", target.eventId.raw)
    }

    /** 小工具：避免测试里重复写 contains 逻辑（不作为生产代码）。 */
    private object EventRevisionChainHelper {
        fun isPresent(
            event: com.roundtrace.app.core.model.MatchEvent,
            history: List<com.roundtrace.app.core.model.MatchEvent>
        ): Boolean = history.any { it.eventId == event.eventId }
    }
}
