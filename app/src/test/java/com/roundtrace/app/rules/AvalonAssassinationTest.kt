package com.roundtrace.app.rules

import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.IssueCode
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.WinReason
import com.roundtrace.app.core.rules.avalon.AvalonReducer
import com.roundtrace.app.core.rules.avalon.AvalonRules
import com.roundtrace.app.util.MatchBuilder
import com.roundtrace.app.util.RoleFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R10：刺杀命中梅林 / 刺错 / 角色未知 三个分支（TASKS.md T25②）。
 *
 * 红线：目标角色未揭晓时**绝不猜测**命中，维持 `AWAITING_ASSASSINATION`。
 */
class AvalonAssassinationTest {

    private val rules = AvalonRules()
    private val reducer = AvalonReducer(rules)

    /** 构造一场「三次任务成功、进入待刺杀」的 8 人局。 */
    private fun threeSuccessesBuilder(): MatchBuilder =
        MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(1, leaderSeat = 1, teamSeats = listOf(1, 2, 3), failCount = 0)
            .questCycle(2, leaderSeat = 2, teamSeats = listOf(1, 2, 3, 4), failCount = 0)
            .questCycle(3, leaderSeat = 3, teamSeats = listOf(4, 6, 7, 8), failCount = 0)

    @Test
    fun `R10 刺杀命中梅林坏人胜利`() {
        val state = threeSuccessesBuilder()
            .assassinationRecorded(
                assassinSeat = RoleFixtures.ASSASSIN_SEAT,
                targetSeat = RoleFixtures.MERLIN_SEAT
            )
            .replay()

        assertEquals("R10 命中梅林应终局为坏人胜", MatchStatus.FINISHED_EVIL, state.status)
        assertEquals(
            "R10 终局原因应为刺杀命中梅林",
            WinReason.ASSASSIN_HIT_MERLIN,
            state.outcome?.reason
        )
        assertEquals("R10 终局胜方应为 EVIL", Alignment.EVIL, state.outcome?.winner)
        assertEquals("R10 刺杀目标应为 1 号", 1, state.assassination?.targetSeat)
        assertEquals("R10 命中标记应为 true", true, state.assassination?.hitMerlin)
    }

    @Test
    fun `R10 刺杀未命中好人胜利`() {
        val state = threeSuccessesBuilder()
            .assassinationRecorded(
                assassinSeat = RoleFixtures.ASSASSIN_SEAT,
                targetSeat = RoleFixtures.LOYAL_SERVANT_2_SEAT
            )
            .replay()

        assertEquals("R10 刺错应终局为好人胜", MatchStatus.FINISHED_GOOD, state.status)
        assertEquals(
            "R10 终局原因应为刺杀未命中",
            WinReason.ASSASSIN_MISSED,
            state.outcome?.reason
        )
        assertEquals("R10 终局胜方应为 GOOD", Alignment.GOOD, state.outcome?.winner)
        assertEquals("R10 命中标记应为 false", false, state.assassination?.hitMerlin)
    }

    @Test
    fun `R10 目标角色未知时维持待刺杀不猜测命中`() {
        val builder = threeSuccessesBuilder()
        builder.withUnknownRole(RoleFixtures.LOYAL_SERVANT_8_SEAT)
        builder.assassinationRecorded(
            assassinSeat = RoleFixtures.ASSASSIN_SEAT,
            targetSeat = RoleFixtures.LOYAL_SERVANT_8_SEAT
        )

        val result = builder.replayResult()

        assertEquals(
            "R10 角色未知时必须维持待刺杀",
            MatchStatus.AWAITING_ASSASSINATION,
            result.state.status
        )
        assertNull("R10 角色未知时不得给出终局结论", result.state.outcome)
        assertNull(
            "R10 命中标记必须为 null，绝不猜测",
            result.state.assassination?.hitMerlin
        )
        assertNull(
            "R10 determineOutcome 也必须返回 null",
            rules.determineOutcome(result.state)
        )
        assertTrue(
            "R10 应报 UNKNOWN_ROLE_FOR_OUTCOME，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == IssueCode.UNKNOWN_ROLE_FOR_OUTCOME }
        )
    }

    @Test
    fun `R10 非刺杀阶段的刺杀事件不生效`() {
        val result = MatchBuilder.eightPlayerMerlinMordred()
            .questStarted(1, 1)
            .assassinationRecorded(assassinSeat = 5, targetSeat = 1)
            .replayResult()

        assertEquals(
            "R10 非刺杀阶段记录刺杀不应改变状态",
            MatchStatus.IN_PROGRESS,
            result.state.status
        )
        assertNull("R10 不应产生 outcome", result.state.outcome)
        assertTrue(
            "R10 应报 ASSASSINATION_NOT_ALLOWED，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == IssueCode.ASSASSINATION_NOT_ALLOWED && it.isBlocking }
        )
    }

    @Test
    fun `R10 刺杀座位越界被拒绝`() {
        val result = threeSuccessesBuilder()
            .assassinationRecorded(assassinSeat = 5, targetSeat = 11)
            .replayResult()

        assertEquals(
            "R10 越界目标不应推进状态",
            MatchStatus.AWAITING_ASSASSINATION,
            result.state.status
        )
        assertTrue(
            "R10 应报 SEAT_OUT_OF_RANGE，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == IssueCode.SEAT_OUT_OF_RANGE && it.isBlocking }
        )
    }

    @Test
    fun `R10 终局结果不得被用户填写的胜方覆盖`() {
        val result = threeSuccessesBuilder()
            .assassinationRecorded(
                assassinSeat = RoleFixtures.ASSASSIN_SEAT,
                targetSeat = RoleFixtures.MERLIN_SEAT
            )
            // 用户填错胜方
            .matchFinalized(WinReason.ASSASSIN_HIT_MERLIN, Alignment.GOOD)
            .replayResult()

        assertEquals(
            "R10 终局结果不得被 MATCH_FINALIZED 覆盖",
            MatchStatus.FINISHED_EVIL,
            result.state.status
        )
        assertEquals(
            "R10 保留规则结果 EVIL",
            Alignment.EVIL,
            result.state.outcome?.winner
        )
        assertTrue(
            "R10 冲突应报 FINALIZED_CONFLICT，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == IssueCode.FINALIZED_CONFLICT }
        )
    }

    @Test
    fun `R10 无刺客变体三次成功后好人直接胜`() {
        // 自定义变体：去掉刺客（presetId 非标准，validateSetup 允许）
        val builder = MatchBuilder.of(
            playerCount = 8,
            rolesBySeat = linkedMapOf(
                1 to com.roundtrace.app.core.model.RoleId.MERLIN,
                2 to com.roundtrace.app.core.model.RoleId.LOYAL_SERVANT,
                3 to com.roundtrace.app.core.model.RoleId.LOYAL_SERVANT,
                4 to com.roundtrace.app.core.model.RoleId.LOYAL_SERVANT,
                5 to com.roundtrace.app.core.model.RoleId.LOYAL_SERVANT,
                6 to com.roundtrace.app.core.model.RoleId.LOYAL_SERVANT,
                7 to com.roundtrace.app.core.model.RoleId.MORGANA,
                8 to com.roundtrace.app.core.model.RoleId.MINION
            ),
            roleSlots = listOf(
                com.roundtrace.app.core.model.RoleSlotSpec(com.roundtrace.app.core.model.RoleId.MERLIN, 1),
                com.roundtrace.app.core.model.RoleSlotSpec(com.roundtrace.app.core.model.RoleId.LOYAL_SERVANT, 5),
                com.roundtrace.app.core.model.RoleSlotSpec(com.roundtrace.app.core.model.RoleId.MORGANA, 1),
                com.roundtrace.app.core.model.RoleSlotSpec(com.roundtrace.app.core.model.RoleId.MINION, 1)
            ),
            presetId = "custom"
        )
            .questCycle(1, leaderSeat = 1, teamSeats = listOf(1, 2, 3), failCount = 0)
            .questCycle(2, leaderSeat = 2, teamSeats = listOf(1, 2, 3, 4), failCount = 0)
            .questCycle(3, leaderSeat = 3, teamSeats = listOf(4, 6, 7, 8), failCount = 0)

        val state = builder.replay()

        assertEquals(
            "R10 无刺客变体三次成功后好人直接胜，不得卡在待刺杀",
            MatchStatus.FINISHED_GOOD,
            state.status
        )
        assertEquals(
            "R10 终局原因应为无刺客",
            WinReason.NO_ASSASSIN_PRESENT,
            state.outcome?.reason
        )
        assertFalse(
            "R10 不应进入刺杀阶段",
            state.phase == com.roundtrace.app.core.model.RecordPhase.ASSASSINATION
        )
    }
}
