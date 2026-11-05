package com.roundtrace.app.core.replay

import com.roundtrace.app.core.model.BeliefType
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EventType
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.WinReason
import com.roundtrace.app.util.MatchBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 复盘主时间线行为人（UX-P2-01 / Phase A）。
 *
 * 钉住「结构化 actorSeat」契约：
 * - 明确存在单一行为人的事件必须填 `actorSeat`：
 *     任务开始 / 组队提案 → 队长；发言 → 发言者；私人笔记 → 观察者；刺杀 → 刺客；
 * - **无单一行为人**的事件（`TeamVoteRecorded` 汇总票、`TeamProposalResolved` 决议、
 *   `MissionResolved` 任务结果、`MatchFinalized` 终局、`MatchAbandoned` 弃局）保持 `null`，
 *   **绝不臆造行为人**；
 * - 私人笔记的行为人只在合法视角过滤后才出现（与既有的 B31 / 隐私隔离唯一出口一致）；
 * - 加 `actorSeat` 不得改变条目数量、排序与既有文案。
 */
class TimelineActorSeatTest {

    private companion object {
        const val PLAYER_COUNT = 8
    }

    @Test
    fun questStartedUsesLeaderAsActor() {
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            questStarted(questNo = 1, leaderSeat = 3)
        }.events()

        val entry = TimelineBuilder.build(events).single()
        assertEquals(EventType.QUEST_STARTED, entry.eventType)
        assertEquals(3, entry.actorSeat)
    }

    @Test
    fun teamProposedUsesLeaderAsActor() {
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            questStarted(1, 1)
            teamProposed(questNo = 1, proposalNo = 1, leaderSeat = 5, teamSeats = listOf(1, 2, 3))
        }.events()

        val entry = TimelineBuilder.build(events).single { it.eventType == EventType.TEAM_PROPOSED }
        assertEquals(5, entry.actorSeat)
    }

    @Test
    fun playerClaimedUsesSpeakerAsActor() {
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            playerClaimed(speakerSeat = 2, claimType = ClaimType.KEY_STATEMENT, text = "我觉得 5 号可疑")
        }.events()

        val entry = TimelineBuilder.build(events).single { it.eventType == EventType.PLAYER_CLAIMED }
        assertEquals(2, entry.actorSeat)
    }

    @Test
    fun assassinationRecordedUsesAssassinAsActor() {
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            assassinationRecorded(assassinSeat = 4, targetSeat = 1, hitMerlin = false)
        }.events()

        val entry = TimelineBuilder.build(events).single { it.eventType == EventType.ASSASSINATION_RECORDED }
        assertEquals(4, entry.actorSeat)
    }

    @Test
    fun privateNoteShowsObserverAsActorOnlyWhenVisible() {
        val builder = MatchBuilder.eightPlayerMerlinMordred().apply {
            privateNoteAdded(
                observerSeat = 7,
                beliefType = BeliefType.SUSPECT,
                targetSeats = listOf(2),
                availableFromOrder = 1,
                text = "私下记：2 号可疑"
            )
        }
        val events = builder.events()

        // 公开视角：私有笔记不可见，根本不会进入时间线 → 也就无所谓 actorSeat
        val publicTimeline = TimelineBuilder.build(
            VisibleEventPolicy.forPerspective(events, Perspective.PUBLIC, observerSeat = null)
        )
        assertEquals(0, publicTimeline.size)

        // 观察者本人视角：笔记可见，行为人 = 观察者本人座位
        val observerTimeline = TimelineBuilder.build(
            VisibleEventPolicy.forPerspective(events, Perspective.OBSERVER_AT_TIME, observerSeat = 7)
        )
        val note = observerTimeline.single { it.eventType == EventType.PRIVATE_NOTE_ADDED }
        assertEquals(7, note.actorSeat)
    }

    @Test
    fun teamVoteHasNoSingleActor() {
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            questStarted(1, 1)
            teamVoteRecorded(questNo = 1, proposalNo = 1, approveCount = 5, rejectCount = 3)
        }.events()

        val entry = TimelineBuilder.build(events).single { it.eventType == EventType.TEAM_VOTE_RECORDED }
        assertNull(entry.actorSeat)
    }

    @Test
    fun teamProposalResolvedHasNoSingleActor() {
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            questStarted(1, 1)
            teamProposalResolved(
                questNo = 1, proposalNo = 1, decision = ProposalDecision.APPROVED,
                approveCount = 5, rejectCount = 3
            )
        }.events()

        val entry = TimelineBuilder.build(events).single { it.eventType == EventType.TEAM_PROPOSAL_RESOLVED }
        assertNull(entry.actorSeat)
    }

    @Test
    fun missionResolvedHasNoSingleActor() {
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            questStarted(1, 1)
            missionResolved(
                questNo = 1, teamSeats = listOf(2, 3, 4),
                failCount = 1, observedOutcome = MissionOutcome.FAILED
            )
        }.events()

        val entry = TimelineBuilder.build(events).single { it.eventType == EventType.MISSION_RESOLVED }
        assertNull(entry.actorSeat)
    }

    @Test
    fun matchFinalizedHasNoSingleActor() {
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            matchFinalized(winReason = WinReason.THREE_MISSIONS_FAILED)
        }.events()

        val entry = TimelineBuilder.build(events).single { it.eventType == EventType.MATCH_FINALIZED }
        assertNull(entry.actorSeat)
    }

    @Test
    fun matchAbandonedHasNoSingleActor() {
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            matchAbandoned(reason = "人手不够")
        }.events()

        val entry = TimelineBuilder.build(events).single { it.eventType == EventType.MATCH_ABANDONED }
        assertNull(entry.actorSeat)
    }

    @Test
    fun actorSeatDoesNotChangeEntryCountOrderOrText() {
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            questStarted(1, 1)
            teamProposed(questNo = 1, proposalNo = 1, leaderSeat = 1, teamSeats = listOf(1, 2, 3))
            teamVoteRecorded(questNo = 1, proposalNo = 1, approveCount = 6, rejectCount = 2)
            teamProposalResolved(
                questNo = 1, proposalNo = 1, decision = ProposalDecision.APPROVED,
                approveCount = 6, rejectCount = 2
            )
            missionResolved(
                questNo = 1, teamSeats = listOf(1, 2, 3),
                failCount = 0, observedOutcome = MissionOutcome.SUCCESS
            )
            playerClaimed(speakerSeat = 2, claimType = ClaimType.KEY_STATEMENT, text = "相信 1 号")
            assassinationRecorded(assassinSeat = 5, targetSeat = 1, hitMerlin = false)
            matchFinalized(winReason = WinReason.ASSASSIN_MISSED)
        }.events()

        // 公开视角：私人笔记不在此列；其余 8 条全部可见
        val visible = VisibleEventPolicy.forPerspective(events, Perspective.PUBLIC, observerSeat = null)
        val timeline = TimelineBuilder.build(visible)

        assertEquals("公开时间线应有 8 条（私人笔记被过滤），实际=${timeline.size}", 8, timeline.size)

        // 排序必须由 occurredOrder 决定且连续（1..8）
        assertEquals((1..8).toList(), timeline.map { it.occurredOrder })

        // 行为人归属：队长 / 发言者 / 刺客有值，汇总票 / 决议 / 任务结果 / 终局为 null
        val byType = timeline.associateBy { it.eventType }
        assertEquals(1, byType[EventType.QUEST_STARTED]?.actorSeat)
        assertEquals(1, byType[EventType.TEAM_PROPOSED]?.actorSeat)
        assertEquals(2, byType[EventType.PLAYER_CLAIMED]?.actorSeat)
        assertEquals(5, byType[EventType.ASSASSINATION_RECORDED]?.actorSeat)
        assertNull(byType[EventType.TEAM_VOTE_RECORDED]?.actorSeat)
        assertNull(byType[EventType.TEAM_PROPOSAL_RESOLVED]?.actorSeat)
        assertNull(byType[EventType.MISSION_RESOLVED]?.actorSeat)
        assertNull(byType[EventType.MATCH_FINALIZED]?.actorSeat)

        // 既有文案不受影响：任务开始标题仍含「任务开始」
        assertTrue(
            "任务开始标题应保持原文案",
            byType[EventType.QUEST_STARTED]?.title?.contains("任务开始") == true
        )
    }

    private fun assertTrue(message: String, condition: Boolean) {
        assertEquals(message, true, condition)
    }
}
