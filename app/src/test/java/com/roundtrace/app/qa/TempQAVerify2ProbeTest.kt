package com.roundtrace.app.qa

import com.roundtrace.app.core.model.BeliefType
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.model.PrivateNoteAdded
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.QuestStarted
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.TeamVoteChoice
import com.roundtrace.app.core.model.TeamVoteRecorded
import com.roundtrace.app.core.replay.PlayerTraceBuilder
import com.roundtrace.app.core.replay.PlayerTraceCategory
import com.roundtrace.app.core.replay.PlayerTraceFactLevel
import com.roundtrace.app.core.replay.PlayerTraceRelationship
import com.roundtrace.app.util.MatchBuilder
import com.roundtrace.app.util.RoleFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA **第二轮独立探针**（针对 16:27 之后被改动的 F05-01 实现）。
 * 旧探针文件已被他人改写并误提交，不能作为我的独立证据，故全部重写、另起文件名。
 */
class TempQAVerify2ProbeTest {

    private val n = 8
    private val end = 999

    private fun qaBuilder(): MatchBuilder =
        MatchBuilder.of(playerCount = n, rolesBySeat = RoleFixtures.EIGHT_PLAYER_MERLIN_MORDRED, mySeat = 1)

    private fun trace(
        events: List<MatchEvent>,
        seat: Int,
        at: Int = end,
        mode: Perspective = Perspective.PUBLIC,
        observer: Int? = null
    ) = PlayerTraceBuilder.build(events, seat, at, mode, observer, n)

    private fun filler(orders: IntRange, leader: Int = 2): List<QuestStarted> = orders.map { o ->
        QuestStarted(
            eventId = EventId("v2-f-$o"), occurredOrder = o, recordedAt = o * 1_000L,
            questNo = 1, leaderSeat = leader
        )
    }

    private fun multiProposal(): List<MatchEvent> = qaBuilder().apply {
        questStarted(1, 2)
        teamProposed(1, 1, 2, listOf(2, 3, 4))
        teamVoteRecorded(
            1, 1, approveCount = 4, rejectCount = 4,
            votesBySeat = mapOf(
                2 to TeamVoteChoice.APPROVE, 3 to TeamVoteChoice.REJECT,
                4 to TeamVoteChoice.REJECT, 5 to TeamVoteChoice.APPROVE
            )
        )
        teamProposalResolved(1, 1, ProposalDecision.REJECTED, approveCount = 4, rejectCount = 4)
        teamProposed(1, 2, 3, listOf(2, 3, 4))
        teamVoteRecorded(1, 2, approveCount = 6, rejectCount = 2)
        teamProposalResolved(1, 2, ProposalDecision.APPROVED, approveCount = 6, rejectCount = 2)
        missionResolved(1, listOf(2, 3, 4, 6), failCount = 1)
    }.events()

    // ————— 追加项 A：私有声称不得从「被提及」侧门进他人轨迹 —————
    @Test
    fun privateClaimHasNoSideDoorIntoOtherSeatsTrace() {
        // 私有声称：1 号私录、点名 5 号、声称自己是梅林、带私密正文
        val claim = qaBuilder().apply {
            playerClaimed(
                speakerSeat = 1,
                claimType = ClaimType.ROLE_CLAIM,
                questNo = 1,
                claimedRole = RoleId.MERLIN,
                targetSeats = listOf(5),
                text = "私密QA：5 号像坏人",
                visibility = EventVisibility.PRIVATE_OBSERVER
            )
        }.events().single()

        listOf(
            "PUBLIC" to Perspective.PUBLIC,
            "OBSERVER(本人)" to Perspective.OBSERVER_AT_TIME,
            "GOD" to Perspective.GOD_AFTER_REVEAL
        ).forEach { (name, mode) ->
            val observer = if (mode == Perspective.OBSERVER_AT_TIME) 1 else null
            assertEquals(
                "$name 下 5 号不得有来自私有声称的 MENTIONED 条目",
                0,
                trace(listOf(claim), 5, end, mode, observer)
                    .count { it.category == PlayerTraceCategory.MENTIONED }
            )
        }
        // 非归属观察者看 5 号：同样 0
        assertEquals(0, trace(listOf(claim), 5, end, Perspective.OBSERVER_AT_TIME, 2)
            .count { it.category == PlayerTraceCategory.MENTIONED })
        // 但本人轨迹里应出现，且必须带私有来源标记
        val own = trace(listOf(claim), 1, end, Perspective.OBSERVER_AT_TIME, 1)
        assertEquals("1 号本人应看到自己的私有声称", 1, own.size)
        assertEquals(
            "私有来源条目必须标记 sourceVisibility=PRIVATE_OBSERVER",
            EventVisibility.PRIVATE_OBSERVER, own.single().sourceVisibility
        )
        assertTrue("本人条目应可读出私密正文：${own.single().detail}", own.single().detail.contains("私密QA"))
        // 公开声称仍应产出被提及
        val pub = qaBuilder().apply {
            playerClaimed(
                speakerSeat = 1, claimType = ClaimType.SUSPECT, questNo = 1, targetSeats = listOf(5)
            )
        }.events()
        assertEquals(
            "公开声称仍要产出被提及（防「一刀切关掉」）",
            1,
            trace(pub, 5, end, Perspective.PUBLIC, null).count { it.category == PlayerTraceCategory.MENTIONED }
        )
        assertEquals(EventVisibility.PUBLIC,
            trace(pub, 5, end, Perspective.PUBLIC, null).single().sourceVisibility)
        println("[V2] 私有声称: 5号MENTIONED(PUBLIC/OBS/GOD) = 0/0/0；1号本人条目 sourceVisibility=${own.single().sourceVisibility}")
    }

    // ————— req1：视角隔离（私有笔记） —————
    @Test
    fun privateNoteVisibilityMatrix() {
        val note = PrivateNoteAdded(
            eventId = EventId("v2-note"), occurredOrder = 10, recordedAt = 10_000L,
            visibility = EventVisibility.PRIVATE_OBSERVER, questNo = 1, observerSeat = 1,
            beliefType = BeliefType.SUSPECT, targetSeats = listOf(5), text = "私密笔记QA",
            availableFromOrder = 3
        )
        val events: List<MatchEvent> = filler(1..12) + note
        (1..n).forEach { seat ->
            assertEquals("PUBLIC 下 $seat 号不得见私有笔记", 0,
                trace(events, seat, end, Perspective.PUBLIC, seat)
                    .count { it.category == PlayerTraceCategory.PRIVATE_NOTE })
        }
        assertEquals(1, trace(events, 1, end, Perspective.OBSERVER_AT_TIME, 1)
            .count { it.category == PlayerTraceCategory.PRIVATE_NOTE })
        assertEquals(0, trace(events, 1, end, Perspective.OBSERVER_AT_TIME, 2)
            .count { it.category == PlayerTraceCategory.PRIVATE_NOTE })
        assertEquals(1, trace(events, 1, end, Perspective.GOD_AFTER_REVEAL, null)
            .count { it.category == PlayerTraceCategory.PRIVATE_NOTE })
        assertEquals("他人私人判断不进他人轨迹", 0,
            trace(events, 5, end, Perspective.GOD_AFTER_REVEAL, null)
                .count { it.category == PlayerTraceCategory.PRIVATE_NOTE })
    }

    // ————— 追加项 B：B31 数值边界 —————
    @Test
    fun backfilledNoteAnchorNeverBeyondAt() {
        val events: List<MatchEvent> = filler(1..12) + PrivateNoteAdded(
            eventId = EventId("v2-note"), occurredOrder = 900, recordedAt = 900_000L,
            visibility = EventVisibility.PRIVATE_OBSERVER, questNo = 1, observerSeat = 1,
            beliefType = BeliefType.SUSPECT, targetSeats = listOf(5), text = "私密QA",
            availableFromOrder = 3
        )
        listOf(0, 1, 2).forEach { at ->
            assertEquals("at=$at 未到 availableFromOrder=3，不得出现", 0,
                trace(events, 1, at, Perspective.OBSERVER_AT_TIME, 1)
                    .count { it.category == PlayerTraceCategory.PRIVATE_NOTE })
        }
        listOf(3, 5, 100, 500).forEach { at ->
            val notes = trace(events, 1, at, Perspective.OBSERVER_AT_TIME, 1)
                .filter { it.category == PlayerTraceCategory.PRIVATE_NOTE }
            assertEquals("at=$at 应可见", 1, notes.size)
            assertEquals("at=$at 定位点恒为 3", 3, notes.single().occurredOrder)
            assertTrue("定位点不得晚于 at", notes.single().occurredOrder <= at)
        }
        (1..n).forEach { seat ->
            assertTrue("at=5 时 $seat 号出现越界定位点",
                trace(events, seat, 5, Perspective.OBSERVER_AT_TIME, 1).all { it.occurredOrder <= 5 })
        }
        val late: List<MatchEvent> = filler(1..12) + PrivateNoteAdded(
            eventId = EventId("v2-late"), occurredOrder = 2, recordedAt = 2_000L,
            visibility = EventVisibility.PRIVATE_OBSERVER, questNo = 1, observerSeat = 1,
            beliefType = BeliefType.TRUST, targetSeats = listOf(5), text = "私密QA2",
            availableFromOrder = 900
        )
        assertEquals("availableFromOrder=900 时 at=5 不得提前泄漏", 0,
            trace(late, 1, 5, Perspective.OBSERVER_AT_TIME, 1)
                .count { it.category == PlayerTraceCategory.PRIVATE_NOTE })
        println("[V2] B31 笔记(occurredOrder=900/availableFromOrder=3): at=2→0条, at=3/5/100/500→1条@3")
    }

    // ————— req3：汇总票 —————
    @Test
    fun summaryVotesNeverSplitToSeats() {
        val events = multiProposal()
        (1..n).forEach { seat ->
            assertFalse(
                "$seat 号出现了来自第 2 次提案(只有汇总票)的个人票",
                trace(events, seat).any {
                    it.category == PlayerTraceCategory.PERSONAL_VOTE && it.occurredOrder == 6
                }
            )
        }
        val onlySummary = qaBuilder().apply {
            questStarted(1, 1)
            teamVoteRecorded(1, 1, approveCount = 5, rejectCount = 3)
        }.events()
        (1..n).forEach { seat ->
            assertEquals(0, trace(onlySummary, seat).count { it.category == PlayerTraceCategory.PERSONAL_VOTE })
        }
        (2..5).forEach { seat ->
            assertEquals("$seat 号逐人票应产出 1 条", 1,
                trace(events, seat).count { it.category == PlayerTraceCategory.PERSONAL_VOTE })
        }
    }

    // ————— req5：提名 ≠ 任务；队长自提名不再重复 —————
    @Test
    fun nominationMissionAndLeaderSelfNomination() {
        val events = multiProposal()
        val seat4 = trace(events, 4)
        assertEquals("4 号被提名 2 次", 2, seat4.count { it.category == PlayerTraceCategory.NOMINATED })
        assertEquals("4 号任务队员 1 次", 1, seat4.count { it.category == PlayerTraceCategory.MISSION_MEMBER })
        assertNotEquals(
            seat4.first { it.category == PlayerTraceCategory.NOMINATED }.relationship,
            seat4.first { it.category == PlayerTraceCategory.MISSION_MEMBER }.relationship
        )
        assertTrue(seat4.first { it.category == PlayerTraceCategory.MISSION_MEMBER }.detail.contains("2 / 3 / 4 / 6"))
        seat4.filter { it.category == PlayerTraceCategory.NOMINATED }.forEach {
            assertTrue(it.detail.contains("2 / 3 / 4"))
            assertFalse("提名不得混入实际队伍的 6 号", it.detail.contains("6 号"))
        }
        // 队长 2 号在第 1 次提案里把自己写进队伍：只能出 1 条（LEADER），并注明本人也在队里
        val seat2 = trace(events, 2)
        val atOrder2 = seat2.filter { it.occurredOrder == 2 }
        println("[V2] 2 号 order=2 的条目 = " + atOrder2.map { "${it.category.name}/${it.relationship.name}" })
        assertEquals("队长自提名时同一事件只出 1 条", 1, atOrder2.size)
        assertEquals(PlayerTraceCategory.LEADER, atOrder2.single().category)
        assertTrue(
            "队长条目需注明本人也在提案队伍中：${atOrder2.single().detail}",
            atOrder2.single().detail.contains("本人也在提案队伍中")
        )
        // 第 2 次提案（队长 3 号、2 号只是队员）→ 仍有 NOMINATED
        assertEquals(1, seat2.count { it.category == PlayerTraceCategory.NOMINATED })
    }

    // ————— req4：失败牌不归因 —————
    @Test
    fun failCountNeverAttributed() {
        val events = multiProposal()
        (1..n).forEach { seat ->
            trace(events, seat).forEach {
                assertFalse("不得归因失败牌：${it.title}", it.title.contains("失败牌"))
                assertFalse(it.detail.contains("号出"))
            }
        }
        val m = trace(events, 4).single { it.category == PlayerTraceCategory.MISSION_MEMBER }
        assertTrue(m.detail.contains("失败牌数：1"))
        assertTrue(m.detail.contains("不推断"))
        val unknown = qaBuilder().apply { missionResolved(2, listOf(5, 6), failCount = null) }.events()
        val d = trace(unknown, 5).single { it.category == PlayerTraceCategory.MISSION_MEMBER }.detail
        assertTrue(d.contains("未记录"))
        assertFalse(d.contains("0（确定零失败牌）"))
        val noTeam = qaBuilder().apply { missionResolved(3, null, null) }.events()
        (1..n).forEach { seat ->
            assertEquals("teamSeats=null 不得猜人", 0,
                trace(noTeam, seat).count { it.category == PlayerTraceCategory.MISSION_MEMBER })
        }
    }

    // ————— req6/7/8：修订、上界、非法输入、事实等级 —————
    @Test
    fun revisionBoundAndIllegalInput() {
        val old = TeamVoteRecorded(
            eventId = EventId("v2-old"), occurredOrder = 5, recordedAt = 1L, questNo = 1, proposalNo = 1,
            votesBySeat = mapOf(3 to TeamVoteChoice.APPROVE), approveCount = 1, rejectCount = 0,
            playerCountAtVote = n, retracted = true
        )
        val new = old.copy(
            eventId = EventId("v2-new"), recordedAt = 2L, retracted = false, supersedesId = old.eventId,
            votesBySeat = mapOf(3 to TeamVoteChoice.REJECT)
        )
        val items = PlayerTraceBuilder.build(listOf(old, new), 3, end, Perspective.PUBLIC, null, n)
            .filter { it.category == PlayerTraceCategory.PERSONAL_VOTE }
        assertEquals("修订后同一逻辑位置只有一条", 1, items.size)
        assertEquals(EventId("v2-new"), items.single().sourceEventId)
        assertTrue(items.single().title.contains("反对"))

        val events = multiProposal()
        (1..n).forEach { seat ->
            assertTrue("at=3 越界", trace(events, seat, at = 3).all { it.occurredOrder <= 3 })
            val list = trace(events, seat)
            assertEquals("$seat 升序", list.map { it.occurredOrder }.sorted(), list.map { it.occurredOrder })
            val keys = list.map { it.sourceEventId.raw to it.category }
            assertEquals("$seat 无重复", keys.distinct().size, keys.size)
        }
        listOf(0, -1, 9, 100).forEach { seat -> assertTrue(trace(events, seat).isEmpty()) }
        assertTrue(PlayerTraceBuilder.build(events, 1, end, Perspective.PUBLIC, null, 0).isEmpty())
        (1..n).forEach { seat ->
            listOf(Perspective.PUBLIC, Perspective.OBSERVER_AT_TIME, Perspective.GOD_AFTER_REVEAL).forEach { mode ->
                assertTrue("不得产出推测",
                    PlayerTraceBuilder.build(events, seat, end, mode, 1, n)
                        .none { it.factLevel == PlayerTraceFactLevel.INFERRED })
            }
        }
    }

    // ————— req5b：发言者/被提及者/声称 —————
    @Test
    fun speakerMentionedAndClaim() {
        val events = qaBuilder().apply {
            playerClaimed(
                speakerSeat = 3, claimType = ClaimType.ROLE_CLAIM, questNo = 1,
                claimedRole = RoleId.PERCIVAL, targetSeats = listOf(1)
            )
        }.events()
        assertEquals(PlayerTraceRelationship.SPOKE, trace(events, 3).single().relationship)
        assertEquals(PlayerTraceRelationship.NAMED_BY_OTHER, trace(events, 1).single().relationship)
        assertTrue(trace(events, 3).single().title.contains("声称"))
        assertTrue(trace(events, 3).single().detail.contains("不等于身份事实"))
        assertTrue(trace(events, 1).single().title.contains("3 号"))
    }
}
