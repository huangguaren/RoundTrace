package com.roundtrace.app.data

import com.roundtrace.app.core.database.EventCodec
import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.AssassinationRecorded
import com.roundtrace.app.core.model.BeliefType
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.MatchAbandoned
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MatchFinalized
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.model.PrivateNoteAdded
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.QuestStarted
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.TeamProposalResolved
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteChoice
import com.roundtrace.app.core.model.TeamVoteRecorded
import com.roundtrace.app.core.model.WinReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `EventCodec` 编解码测试（TASKS.md T33③）。
 *
 * 三条必须钉住的契约：
 * 1. `decode(encode(e)) == e` 对 **10 种事件类型**成立；
 * 2. `verified` 字段**照常序列化**（它是持久化契约字段，由 reducer 在重放时重算，不采信旧值）；
 * 3. `occurredOrder` 只在**列**里，payload 不含它 —— 保证「顺序由仓储盖章」不被 payload 双写污染。
 */
class EventCodecTest {

    private val codec = EventCodec()
    private val matchId = MatchId("match-under-test")

    /** 统一给事件盖上「仓储分配的 order」，便于与解码结果比较。 */
    private fun MatchEvent.withOrder(order: Int): MatchEvent = when (this) {
        is QuestStarted -> copy(occurredOrder = order)
        is TeamProposed -> copy(occurredOrder = order)
        is TeamVoteRecorded -> copy(occurredOrder = order)
        is TeamProposalResolved -> copy(occurredOrder = order)
        is MissionResolved -> copy(occurredOrder = order)
        is PlayerClaimed -> copy(occurredOrder = order)
        is PrivateNoteAdded -> copy(occurredOrder = order)
        is AssassinationRecorded -> copy(occurredOrder = order)
        is MatchAbandoned -> copy(occurredOrder = order)
        is MatchFinalized -> copy(occurredOrder = order)
    }

    private fun roundTrip(event: MatchEvent, order: Int = 7): MatchEvent {
        val entity = codec.encode(event, matchId, order)
        return codec.decode(entity)
    }

    private fun allTenEvents(): List<MatchEvent> = listOf(
        QuestStarted(
            eventId = EventId("e1"),
            occurredOrder = 0,
            recordedAt = 1_000L,
            questNo = 2,
            leaderSeat = 3
        ),
        TeamProposed(
            eventId = EventId("e2"),
            occurredOrder = 0,
            recordedAt = 1_100L,
            questNo = 2,
            proposalNo = 1,
            leaderSeat = 3,
            teamSeats = listOf(1, 3, 5, 7)
        ),
        TeamVoteRecorded(
            eventId = EventId("e3"),
            occurredOrder = 0,
            recordedAt = 1_200L,
            questNo = 2,
            proposalNo = 1,
            votesBySeat = mapOf(
                1 to TeamVoteChoice.APPROVE,
                2 to TeamVoteChoice.REJECT,
                3 to TeamVoteChoice.UNKNOWN
            ),
            approveCount = 5,
            rejectCount = 2,
            unknownVoteCount = 1,
            playerCountAtVote = 8
        ),
        TeamProposalResolved(
            eventId = EventId("e4"),
            occurredOrder = 0,
            recordedAt = 1_300L,
            questNo = 2,
            proposalNo = 1,
            decision = ProposalDecision.APPROVED,
            approveCount = 5,
            rejectCount = 2,
            playerCountAtVote = 8,
            // verified 是持久化契约字段，必须原样保留（reducer 会重算，不采信）
            verified = true
        ),
        MissionResolved(
            eventId = EventId("e5"),
            occurredOrder = 0,
            recordedAt = 1_400L,
            questNo = 2,
            teamSeats = listOf(1, 3, 5, 7),
            teamSizeExpected = 4,
            failCount = 1,
            observedOutcome = MissionOutcome.FAILED,
            verified = true
        ),
        PlayerClaimed(
            eventId = EventId("e6"),
            occurredOrder = 0,
            recordedAt = 1_500L,
            questNo = 3,
            speakerSeat = 7,
            claimType = ClaimType.ROLE_CLAIM,
            claimedRole = RoleId.PERCIVAL,
            targetSeats = listOf(1, 3),
            text = "我是派西维尔",
            proposalNo = 1
        ),
        PrivateNoteAdded(
            eventId = EventId("e7"),
            occurredOrder = 0,
            recordedAt = 1_600L,
            questNo = 3,
            observerSeat = 1,
            beliefType = BeliefType.SUSPECT,
            targetSeats = listOf(4),
            text = "4 号可疑",
            availableFromOrder = 2
        ),
        AssassinationRecorded(
            eventId = EventId("e8"),
            occurredOrder = 0,
            recordedAt = 1_700L,
            assassinSeat = 5,
            targetSeat = 1,
            hitMerlin = null
        ),
        MatchAbandoned(
            eventId = EventId("e9"),
            occurredOrder = 0,
            recordedAt = 1_800L,
            reason = "有人离场"
        ),
        MatchFinalized(
            eventId = EventId("e10"),
            occurredOrder = 0,
            recordedAt = 1_900L,
            winner = Alignment.EVIL,
            winReason = WinReason.ASSASSIN_HIT_MERLIN,
            note = "复盘确认"
        )
    )

    // ————————————————————————————————————————————————————————
    // 往返
    // ————————————————————————————————————————————————————————

    @Test
    fun `T33 十种事件全部可无损往返`() {
        val events = allTenEvents()
        assertEquals("必须覆盖 10 种事件类型", 10, events.size)
        events.forEach { event ->
            val decoded = roundTrip(event)
            assertEquals(
                "事件类型 ${event.type} 往返不一致",
                event.withOrder(7),
                decoded
            )
            assertEquals(
                "事件类型 ${event.type} 的 type 推导不一致",
                event.type,
                decoded.type
            )
        }
    }

    @Test
    fun `T33 verified 字段照常序列化且可还原`() {
        val mission = allTenEvents().filterIsInstance<MissionResolved>().first()
        val decodedMission = roundTrip(mission) as MissionResolved
        assertTrue("MissionResolved.verified 必须原样保留", decodedMission.verified)

        val resolved = allTenEvents().filterIsInstance<TeamProposalResolved>().first()
        val decodedResolved = roundTrip(resolved) as TeamProposalResolved
        assertTrue("TeamProposalResolved.verified 必须原样保留", decodedResolved.verified)

        // 反方向：verified=false 也必须原样保留（不能用默认值吞掉）
        val notVerified = mission.copy(verified = false)
        val decodedNotVerified = roundTrip(notVerified) as MissionResolved
        assertFalse("verified=false 不得被默认值覆盖", decodedNotVerified.verified)
    }

    @Test
    fun `T33 occurredOrder 只在列里payload 不含顺序字段`() {
        val event = allTenEvents().first()
        val entity = codec.encode(event, matchId, occurredOrder = 42)

        assertEquals("occurredOrder 必须写入列", 42, entity.occurredOrder)
        assertFalse(
            "payload 不得包含 occurredOrder（避免与列双写漂移）：${entity.payloadJson}",
            entity.payloadJson.contains("occurredOrder")
        )
        assertFalse(
            "payload 不得包含 eventId：${entity.payloadJson}",
            entity.payloadJson.contains("eventId")
        )
        assertEquals(
            "payloadVersion 必须为 1",
            EventCodec.PAYLOAD_VERSION,
            entity.payloadVersion
        )
    }

    @Test
    fun `T33 领域层的 order 0 会被仓储分配值覆盖`() {
        // 领域事件恒为 0；编码时传入的 order 才是权威
        val event = QuestStarted(
            eventId = EventId("stamp"),
            occurredOrder = 0,
            recordedAt = 0L,
            questNo = 1,
            leaderSeat = 1
        )
        val entity = codec.encode(event, matchId, occurredOrder = 1)
        assertEquals(1, entity.occurredOrder)
        assertEquals(1, codec.decode(entity).occurredOrder)
    }

    // ————————————————————————————————————————————————————————
    // 错误处理
    // ————————————————————————————————————————————————————————

    @Test
    fun `T33 未知 eventType 抛带类型信息的异常`() {
        val entity = codec.encode(allTenEvents().first(), matchId, 1).copy(eventType = "NOT_A_TYPE")

        val error = assertThrows(IllegalStateException::class.java) { codec.decode(entity) }
        val message = error.message.orEmpty()
        assertTrue("异常信息必须包含非法类型名，实际=$message", message.contains("NOT_A_TYPE"))
        assertTrue("异常信息必须包含 eventId，实际=$message", message.contains("eventId"))
    }

    @Test
    fun `T33 缺少必填字段抛异常而不是静默返回 null`() {
        val entity = codec.encode(allTenEvents().first(), matchId, 1).copy(payloadJson = "{}")
        val error = assertThrows(IllegalStateException::class.java) { codec.decode(entity) }
        assertNotNull("必须有可读异常", error.message)
    }

    @Test
    fun `T33 阶段键按任务号与事件类型推导`() {
        val events = allTenEvents()
        assertEquals("QUEST_2", codec.phaseKeyOf(events[0]))
        assertEquals("ASSASSINATION", codec.phaseKeyOf(events[7]))
        assertEquals("END", codec.phaseKeyOf(events[8]))
        assertEquals("END", codec.phaseKeyOf(events[9]))
    }

    @Test
    fun `T33 私有笔记的可见性与可用顺序可还原`() {
        val note = allTenEvents().filterIsInstance<PrivateNoteAdded>().first()
        val decoded = roundTrip(note) as PrivateNoteAdded
        assertEquals(
            com.roundtrace.app.core.model.EventVisibility.PRIVATE_OBSERVER,
            decoded.visibility
        )
        assertEquals("availableFromOrder 必须原样保留", 2, decoded.availableFromOrder)
    }
}
