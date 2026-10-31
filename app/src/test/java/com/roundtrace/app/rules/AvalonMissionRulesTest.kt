package com.roundtrace.app.rules

import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.rules.avalon.AvalonRules
import com.roundtrace.app.util.MatchBuilder
import com.roundtrace.app.util.RoleFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R04 / R05 / R06 / R07 / R12（TASKS.md T24）。
 *
 * 覆盖两条独立红线：
 * 1. **失败牌门槛**：`failThreshold = 2` 当且仅当 `playerCount >= 7 && questNo == 4`；
 * 2. **反对票 ≠ 失败牌**：组队反对票与任务失败牌是两个字段，永不互相推导（R12）。
 */
class AvalonMissionRulesTest {

    private val rules = AvalonRules()

    // ————————————————————————————————————————————————————————
    // 规则常量层
    // ————————————————————————————————————————————————————————

    @Test
    fun `R05R06 失败牌门槛仅第七人以上第四任务为二`() {
        for (playerCount in 5..10) {
            for (questNo in 1..5) {
                val expected = if (playerCount >= 7 && questNo == 4) 2 else 1
                assertEquals(
                    "失败牌门槛不符：playerCount=$playerCount, questNo=$questNo",
                    expected,
                    rules.failThreshold(playerCount, questNo)
                )
            }
        }
    }

    @Test
    fun `R05 八人第四任务一张失败牌不算失败`() {
        assertFalse(
            "8 人局第 4 任务 1 张失败牌不应判失败（门槛 2）",
            rules.isMissionFailed(failCount = 1, playerCount = 8, questNo = 4)
        )
        assertTrue(
            "8 人局第 4 任务 2 张失败牌应判失败",
            rules.isMissionFailed(failCount = 2, playerCount = 8, questNo = 4)
        )
    }

    @Test
    fun `R07 六人第四任务一张失败牌即失败`() {
        assertTrue(
            "6 人局第 4 任务 1 张失败牌即失败（门槛 1）",
            rules.isMissionFailed(failCount = 1, playerCount = 6, questNo = 4)
        )
        assertEquals("6 人局第 4 任务门槛应为 1", 1, rules.failThreshold(6, 4))
    }

    // ————————————————————————————————————————————————————————
    // R04：提案通过 + 任务失败，且两个票数字段不混淆
    // ————————————————————————————————————————————————————————

    @Test
    fun `R04 第二任务七赞成一反对且一张失败牌时提案通过任务失败`() {
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            // 第 1 任务先失败一次，避免三次成功提前进入刺杀阶段
            .questCycle(1, leaderSeat = 1, teamSeats = listOf(1, 2, 3), failCount = 1)
            // 第 2 任务：7 赞成 1 反对，1 张失败牌
            .questCycle(
                questNo = 2,
                leaderSeat = 2,
                teamSeats = listOf(1, 2, 3, 4),
                failCount = 1,
                approve = 7,
                reject = 1
            )

        val state = builder.replay()
        val quest2 = state.quests.first { it.questNo == 2 }
        val proposal = quest2.proposals.first()

        assertEquals(
            "R04 组队投票为 7 赞成，提案应通过（已核实）",
            true,
            proposal.decisionVerified
        )
        assertEquals("R04 赞成票数应记录为 7", 7, proposal.approveCount)
        assertEquals("R04 反对票数应记录为 1", 1, proposal.rejectCount)
        assertEquals("R04 任务失败牌数应记录为 1", 1, quest2.failCount)
        assertEquals(
            "R04 8 人第 2 任务门槛为 1，故任务应失败",
            MissionOutcome.FAILED,
            quest2.outcome
        )
        assertEquals("R04 两次任务失败应计入 evilFailureCount", 2, state.evilFailureCount)
        assertEquals("R04 不应误计好人成功数", 0, state.goodSuccessCount)
    }

    @Test
    fun `R04 反对票与失败牌取值不同时互不影响`() {
        // 关键验证：把「反对票 = 1」与「失败牌 = 2」同时记录，两个字段必须各自独立
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(1, leaderSeat = 1, teamSeats = listOf(1, 2, 3), failCount = 0)
            .questCycle(
                questNo = 2,
                leaderSeat = 2,
                teamSeats = listOf(1, 2, 3, 4),
                failCount = 2,
                approve = 7,
                reject = 1
            )

        val state = builder.replay()
        val quest2 = state.quests.first { it.questNo == 2 }
        val proposal = quest2.proposals.first()

        assertEquals("R04 反对票数应为 1", 1, proposal.rejectCount)
        assertEquals("R04 失败牌数应为 2", 2, quest2.failCount)
        assertNotEquals(
            "R04 两个字段取值不同，证明确实是独立字段而非同一字段复用",
            proposal.rejectCount,
            quest2.failCount
        )
        assertEquals(
            "R04 失败牌 2 张应判任务失败",
            MissionOutcome.FAILED,
            quest2.outcome
        )
    }

    // ————————————————————————————————————————————————————————
    // R05 / R06：8 人第 4 任务
    // ————————————————————————————————————————————————————————

    @Test
    fun `R05 八人第四任务五人一张失败牌判成功`() {
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(1, leaderSeat = 1, teamSeats = listOf(1, 2, 3), failCount = 1)
            .questCycle(2, leaderSeat = 2, teamSeats = listOf(1, 2, 3, 4), failCount = 0)
            .questCycle(3, leaderSeat = 3, teamSeats = listOf(4, 6, 7, 8), failCount = 0)
            // 第 4 任务：5 人，1 张失败牌（门槛 2）
            .questCycle(4, leaderSeat = 4, teamSeats = listOf(1, 3, 4, 5, 8), failCount = 1)

        val state = builder.replay()
        val quest4 = state.quests.first { it.questNo == 4 }

        assertEquals(
            "R05 8 人第 4 任务 5 人 1 张失败牌应判成功（门槛 2）",
            MissionOutcome.SUCCESS,
            quest4.outcome
        )
        assertTrue("R05 该任务数据完整，应可核实", quest4.verified)
        assertEquals("R05 三次任务成功应进入刺杀阶段", com.roundtrace.app.core.model.MatchStatus.AWAITING_ASSASSINATION, state.status)
        assertEquals("R05 已核实成功数应为 3", 3, state.goodSuccessCount)
        assertEquals("R05 已核实失败数应为 1", 1, state.evilFailureCount)
    }

    @Test
    fun `R06 八人第四任务五人两张失败牌判失败`() {
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(1, leaderSeat = 1, teamSeats = listOf(1, 2, 3), failCount = 1)
            .questCycle(2, leaderSeat = 2, teamSeats = listOf(1, 2, 3, 4), failCount = 0)
            .questCycle(3, leaderSeat = 3, teamSeats = listOf(4, 6, 7, 8), failCount = 0)
            .questCycle(4, leaderSeat = 4, teamSeats = listOf(1, 3, 4, 5, 8), failCount = 2)

        val state = builder.replay()
        val quest4 = state.quests.first { it.questNo == 4 }

        assertEquals(
            "R06 8 人第 4 任务 2 张失败牌应判失败（门槛 2）",
            MissionOutcome.FAILED,
            quest4.outcome
        )
        assertTrue("R06 该任务数据完整，应可核实", quest4.verified)
        assertEquals("R06 失败计数应为 2", 2, state.evilFailureCount)
        assertEquals("R06 成功计数应为 2", 2, state.goodSuccessCount)
        assertEquals(
            "R06 两胜两负尚未终局",
            com.roundtrace.app.core.model.MatchStatus.IN_PROGRESS,
            state.status
        )
    }

    // ————————————————————————————————————————————————————————
    // R07：6 人第 4 任务
    // ————————————————————————————————————————————————————————

    @Test
    fun `R07 六人第四任务一张失败牌判失败`() {
        val builder = MatchBuilder.of(
            playerCount = 6,
            rolesBySeat = RoleFixtures.SIX_PLAYER,
            mySeat = 1,
            initialLeaderSeat = 1
        )
            .questCycle(1, leaderSeat = 1, teamSeats = listOf(1, 2), failCount = 0)
            .questCycle(2, leaderSeat = 2, teamSeats = listOf(1, 2, 3), failCount = 0)
            .questCycle(3, leaderSeat = 3, teamSeats = listOf(1, 2, 3, 4), failCount = 1)
            // 6 人第 4 任务队伍 3 人，门槛 1
            .questCycle(4, leaderSeat = 4, teamSeats = listOf(1, 2, 3), failCount = 1)

        val state = builder.replay()
        val quest4 = state.quests.first { it.questNo == 4 }

        assertEquals("R07 6 人第 4 任务期望队伍应为 3 人", 3, quest4.expectedTeamSize)
        assertEquals("R07 6 人第 4 任务失败牌门槛应为 1", 1, quest4.failThreshold)
        assertEquals(
            "R07 6 人第 4 任务 1 张失败牌即判失败",
            MissionOutcome.FAILED,
            quest4.outcome
        )
        assertEquals("R07 两次失败应计入 evilFailureCount", 2, state.evilFailureCount)
    }

    // ————————————————————————————————————————————————————————
    // R12：反对票不能推出任务失败
    // ————————————————————————————————————————————————————————

    @Test
    fun `R12 只记录一张反对票且失败牌未知时不得认定任务失败`() {
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questStarted(1, 1)
            .teamProposed(1, 1, 1, listOf(1, 2, 3))
            // 组队投票：7 赞成 1 反对（提案通过）
            .teamVoteRecorded(1, 1, approveCount = 7, rejectCount = 1)
            .teamProposalResolved(1, 1, com.roundtrace.app.core.model.ProposalDecision.APPROVED, approveCount = 7, rejectCount = 1)
            // 任务结果：失败牌数未知、成败也未记录
            .missionResolved(1, teamSeats = listOf(1, 2, 3), failCount = null, observedOutcome = null)

        val result = builder.replayResult()
        val quest1 = result.state.quests.first { it.questNo == 1 }

        assertNotEquals(
            "R12 反对票 1 张不能推出任务失败",
            MissionOutcome.FAILED,
            quest1.outcome
        )
        assertEquals(
            "R12 失败牌未知时结果应为 UNKNOWN",
            MissionOutcome.UNKNOWN,
            quest1.outcome
        )
        assertFalse("R12 未核实任务不得计入胜负", quest1.verified)
        assertEquals("R12 不得计入好人成功", 0, result.state.goodSuccessCount)
        assertEquals("R12 不得计入坏人失败", 0, result.state.evilFailureCount)
        assertTrue(
            "R12 应产生 DATA_INCOMPLETE 提示，实际 issues=${result.issues.map { it.code }}",
            result.issues.any { it.code == com.roundtrace.app.core.model.IssueCode.DATA_INCOMPLETE }
        )
    }

    @Test
    fun `R12 观察到成功但失败牌数未知时保留观察值并按观察结果计数`() {
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(
                questNo = 1,
                leaderSeat = 1,
                teamSeats = listOf(1, 2, 3),
                failCount = null,
                observedOutcome = MissionOutcome.SUCCESS
            )

        val state = builder.replay()
        val quest1 = state.quests.first { it.questNo == 1 }

        assertEquals(
            "R12 失败牌未知时应保留用户观察到的成败",
            MissionOutcome.SUCCESS,
            quest1.outcome
        )
        assertFalse("R12 但必须标记未核实", quest1.verified)
        // 规则（2026-10-31 裁决）：任务成败是公开信息，观察结果明确即计数；
        // 失败牌数未知只影响「可否复核」，不阻断胜负推进。
        assertEquals("R12 观察结果明确即计数", 1, state.goodSuccessCount)
    }
}
