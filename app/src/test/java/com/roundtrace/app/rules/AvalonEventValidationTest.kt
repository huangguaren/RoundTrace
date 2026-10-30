package com.roundtrace.app.rules

import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.IssueCode
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.RecordPhase
import com.roundtrace.app.core.model.SeatId
import com.roundtrace.app.core.model.TeamProposalResolved
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteChoice
import com.roundtrace.app.core.model.TeamVoteRecorded
import com.roundtrace.app.core.rules.avalon.AvalonRules
import com.roundtrace.app.util.MatchBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R13 的「事件部分」（TASKS.md T26②）。
 *
 * 覆盖：队伍少一人 / 重复座位 / 票数超人数 / 逐人票与汇总票矛盾 /
 * 失败牌超队伍 / 未通过提案就录任务结果 / 决议与票数冲突。
 *
 * 与 [AvalonSetupValidationTest]（配置部分）互补：那个测「开局配置」，这个测「确认事件」。
 */
class AvalonEventValidationTest {

    private val rules = AvalonRules()

    /** 仅开始第 1 个任务的空状态。 */
    private fun stateAfterQuest1Started() =
        MatchBuilder.eightPlayerMerlinMordred().questStarted(1, 1).replay()

    // ————————————————————————————————————————————————————————
    // 队伍检查
    // ————————————————————————————————————————————————————————

    @Test
    fun `R13 队伍少一人时草稿可存但确认被阻断`() {
        val state = stateAfterQuest1Started()
        // 8 人第 1 任务应 3 人，只填 2 人
        val incompleteTeam: List<SeatId> = listOf(1, 2)
        val event = TeamProposed(
            eventId = EventId("incomplete-team"),
            occurredOrder = 2,
            recordedAt = 0L,
            visibility = EventVisibility.PUBLIC,
            questNo = 1,
            proposalNo = 1,
            leaderSeat = 1,
            teamSeats = incompleteTeam
        )

        val confirmIssues = rules.validateEvent(state, event)
        assertTrue(
            "确认态必须被 BLOCKING 拦住，实际=${confirmIssues.map { it.code }}",
            confirmIssues.any { it.code == IssueCode.TEAM_SIZE_MISMATCH && it.isBlocking }
        )

        val draftIssues = rules.validateTeamDraft(incompleteTeam, playerCount = 8, questNo = 1)
        assertTrue(
            "草稿态不得有 BLOCKING（允许只选部分成员后暂存），实际=${draftIssues.map { it.code to it.severity }}",
            draftIssues.none { it.isBlocking }
        )
        assertTrue(
            "草稿态仍应给出提示（让用户知道还没选满）",
            draftIssues.any { it.code == IssueCode.TEAM_SIZE_MISMATCH }
        )
    }

    @Test
    fun `R13 队伍超员时确认被阻断`() {
        val state = stateAfterQuest1Started()
        val tooMany = TeamProposed(
            eventId = EventId("too-many"),
            occurredOrder = 2,
            recordedAt = 0L,
            visibility = EventVisibility.PUBLIC,
            questNo = 1,
            proposalNo = 1,
            leaderSeat = 1,
            teamSeats = listOf(1, 2, 3, 4)
        )
        assertTrue(
            "8 人第 1 任务 4 人应报 TEAM_SIZE_MISMATCH",
            rules.validateEvent(state, tooMany)
                .any { it.code == IssueCode.TEAM_SIZE_MISMATCH && it.isBlocking }
        )
    }

    @Test
    fun `R13 重复座位确认被阻断`() {
        val state = stateAfterQuest1Started()
        val duplicateTeam = TeamProposed(
            eventId = EventId("dup-team"),
            occurredOrder = 2,
            recordedAt = 0L,
            visibility = EventVisibility.PUBLIC,
            questNo = 1,
            proposalNo = 1,
            leaderSeat = 1,
            teamSeats = listOf(1, 1, 2)
        )
        assertTrue(
            "重复座位应报 DUPLICATE_SEAT",
            rules.validateEvent(state, duplicateTeam)
                .any { it.code == IssueCode.DUPLICATE_SEAT && it.isBlocking }
        )
    }

    @Test
    fun `R13 座位越界确认被阻断`() {
        val state = stateAfterQuest1Started()
        val outOfRange = TeamProposed(
            eventId = EventId("out-of-range"),
            occurredOrder = 2,
            recordedAt = 0L,
            visibility = EventVisibility.PUBLIC,
            questNo = 1,
            proposalNo = 1,
            leaderSeat = 1,
            teamSeats = listOf(1, 2, 11)
        )
        assertTrue(
            "座位 11 应报 SEAT_OUT_OF_RANGE",
            rules.validateEvent(state, outOfRange)
                .any { it.code == IssueCode.SEAT_OUT_OF_RANGE && it.isBlocking }
        )
    }

    // ————————————————————————————————————————————————————————
    // 投票检查（票数 ≠ 失败牌）
    // ————————————————————————————————————————————————————————

    @Test
    fun `R13 赞成加反对超过人数时报码`() {
        val state = stateAfterQuest1Started()
        val overVoted = TeamVoteRecorded(
            eventId = EventId("over-vote"),
            occurredOrder = 3,
            recordedAt = 0L,
            visibility = EventVisibility.PUBLIC,
            questNo = 1,
            proposalNo = 1,
            votesBySeat = null,
            approveCount = 5,
            rejectCount = 4,
            unknownVoteCount = null,
            playerCountAtVote = 8
        )
        assertTrue(
            "5+4=9 > 8 应报 VOTE_COUNT_EXCEEDS_PLAYERS",
            rules.validateEvent(state, overVoted)
                .any { it.code == IssueCode.VOTE_COUNT_EXCEEDS_PLAYERS && it.isBlocking }
        )
    }

    @Test
    fun `R13 逐人票与汇总票矛盾时报码`() {
        val builder = MatchBuilder.eightPlayerMerlinMordred()
            .questStarted(1, 1)
            .teamProposed(1, 1, 1, listOf(1, 2, 3))
            // 逐人票：2 赞成 1 反对；汇总票却写 3 赞成
            .teamVoteRecorded(
                questNo = 1,
                proposalNo = 1,
                approveCount = 3,
                rejectCount = 0,
                votesBySeat = mapOf(
                    1 to TeamVoteChoice.APPROVE,
                    2 to TeamVoteChoice.APPROVE,
                    3 to TeamVoteChoice.REJECT
                )
            )

        val result = builder.replayResult()
        assertTrue(
            "R13 逐人票与汇总票矛盾应报 VOTE_SUM_MISMATCH，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == IssueCode.VOTE_SUM_MISMATCH }
        )
        assertEquals(
            "R13 矛盾时不得标记为已核实（此处仍在投票阶段）",
            RecordPhase.VOTING,
            result.state.phase
        )
    }

    @Test
    fun `R13 决议与票数冲突时保留用户决议但标记未核实`() {
        val result = MatchBuilder.eightPlayerMerlinMordred()
            .questStarted(1, 1)
            .teamProposed(1, 1, 1, listOf(1, 2, 3))
            .teamVoteRecorded(1, 1, approveCount = 4, rejectCount = 4)
            .teamProposalResolved(1, 1, ProposalDecision.APPROVED, approveCount = 4, rejectCount = 4)
            .replayResult()

        val proposal = result.state.quests.first().proposals.first()
        assertEquals(
            "R13 必须保留用户现场记录的决议（规则不覆盖事实）",
            ProposalDecision.APPROVED,
            proposal.decision
        )
        assertFalse("R13 但与票数冲突，必须标记未核实", proposal.decisionVerified)
        assertTrue(
            "R13 应报 DECISION_VOTE_CONFLICT，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == IssueCode.DECISION_VOTE_CONFLICT }
        )
        assertEquals(
            "R13 仍按用户决议推进到任务阶段",
            RecordPhase.MISSION_PENDING,
            result.state.phase
        )
    }

    // ————————————————————————————————————————————————————————
    // 任务结果检查
    // ————————————————————————————————————————————————————————

    @Test
    fun `R13 失败牌数超过队伍人数返回 BLOCKING`() {
        val state = MatchBuilder.eightPlayerMerlinMordred()
            .questStarted(1, 1)
            .teamProposed(1, 1, 1, listOf(1, 2, 3))
            .teamVoteRecorded(1, 1, approveCount = 7, rejectCount = 1)
            .teamProposalResolved(1, 1, ProposalDecision.APPROVED, approveCount = 7, rejectCount = 1)
            .replay()

        val badMission = MissionResolved(
            eventId = EventId("bad-mission"),
            occurredOrder = 99,
            recordedAt = 0L,
            visibility = EventVisibility.PUBLIC,
            questNo = 1,
            teamSeats = listOf(1, 2, 3),
            teamSizeExpected = 3,
            failCount = 5,
            observedOutcome = MissionOutcome.FAILED
        )
        assertTrue(
            "失败牌 5 > 队伍 3 应报 FAIL_COUNT_EXCEEDS_TEAM，实际=${rules.validateEvent(state, badMission).map { it.code }}",
            rules.validateEvent(state, badMission)
                .any { it.code == IssueCode.FAIL_COUNT_EXCEEDS_TEAM && it.isBlocking }
        )
    }

    @Test
    fun `R13 未通过提案时记录任务结果返回 BLOCKING`() {
        val state = stateAfterQuest1Started()
        val mission = MissionResolved(
            eventId = EventId("mission-without-approval"),
            occurredOrder = 3,
            recordedAt = 0L,
            visibility = EventVisibility.PUBLIC,
            questNo = 1,
            teamSeats = listOf(1, 2, 3),
            teamSizeExpected = 3,
            failCount = 0,
            observedOutcome = MissionOutcome.SUCCESS
        )
        assertTrue(
            "无已通过提案时应报 MISSION_WITHOUT_APPROVED_PROPOSAL",
            rules.validateEvent(state, mission)
                .any { it.code == IssueCode.MISSION_WITHOUT_APPROVED_PROPOSAL && it.isBlocking }
        )
    }

    @Test
    fun `R13 任务队伍与已通过提案不一致时报码且不计数`() {
        val result = MatchBuilder.eightPlayerMerlinMordred()
            .questCycle(
                questNo = 1,
                leaderSeat = 1,
                teamSeats = listOf(1, 2, 3),
                failCount = 1,
                missionTeamSeats = listOf(1, 2, 4)
            )
            .replayResult()

        assertTrue(
            "R13 应报 MISSION_TEAM_MISMATCH，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == IssueCode.MISSION_TEAM_MISMATCH }
        )
        assertEquals("R13 队伍不一致的任务不得计数", 0, result.state.evilFailureCount)
        assertFalse("R13 应标记未核实", result.state.quests.first().verified)
    }

    @Test
    fun `R13 观察成败与失败牌数冲突时报码`() {
        val result = MatchBuilder.eightPlayerMerlinMordred()
            // 1 张失败牌按规则就是失败，但用户观察记录为成功
            .questCycle(
                questNo = 1,
                leaderSeat = 1,
                teamSeats = listOf(1, 2, 3),
                failCount = 1,
                observedOutcome = MissionOutcome.SUCCESS
            )
            .replayResult()

        assertTrue(
            "R13 应报 FAIL_COUNT_OUTCOME_CONFLICT，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == IssueCode.FAIL_COUNT_OUTCOME_CONFLICT }
        )
        assertFalse(
            "R13 冲突任务不得计入任何计数",
            result.state.quests.first().verified
        )
    }

    @Test
    fun `R13 非法事件只产生 issue 且 reducer 不抛异常`() {
        val builder = MatchBuilder.eightPlayerMerlinMordred()
        builder.appendRaw(
            TeamProposalResolved(
                eventId = EventId("resolve-without-proposal"),
                occurredOrder = 1,
                recordedAt = 0L,
                visibility = EventVisibility.PUBLIC,
                questNo = 1,
                proposalNo = 1,
                decision = ProposalDecision.APPROVED,
                approveCount = 8,
                rejectCount = 0,
                playerCountAtVote = 8
            )
        )
        val result = builder.replayResult()
        assertTrue("R13 无前置的决议必须被记录为 issue 而不是崩溃", result.issues.isNotEmpty())
        assertEquals(
            "R13 状态不应被非法事件推进",
            MatchStatus.DRAFT,
            result.state.status
        )
        assertEquals(
            "R13 事件仍然被计入 eventCount（事实不丢失）",
            1,
            result.state.eventCount
        )
    }
}
