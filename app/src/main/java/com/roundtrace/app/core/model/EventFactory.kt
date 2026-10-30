package com.roundtrace.app.core.model

import com.roundtrace.app.core.util.IdGenerator
import java.time.Clock

/**
 * 事件工厂（ARCHITECTURE §8.4）。
 *
 * 职责：为每个事件类型提供构造方法，统一填充
 *  - `eventId` = [IdGenerator] 生成的新 id
 *  - `recordedAt` = [Clock] 当前时刻（epoch millis）
 *  - `occurredOrder` = **0**（由 Repository 在事务内盖章分配，领域层不得自增）
 *
 * 不做任何规则校验：校验是 `GameRules.validateEvent` 的职责。
 */
class EventFactory(
    private val idGenerator: IdGenerator,
    private val clock: Clock
) {

    private fun newEventId(): EventId = EventId(idGenerator.newId())

    private fun now(): Long = clock.millis()

    fun questStarted(
        questNo: Int,
        leaderSeat: SeatId,
        visibility: EventVisibility = EventVisibility.PUBLIC
    ): QuestStarted = QuestStarted(
        eventId = newEventId(),
        occurredOrder = 0,
        recordedAt = now(),
        visibility = visibility,
        questNo = questNo,
        leaderSeat = leaderSeat
    )

    fun teamProposed(
        questNo: Int,
        proposalNo: Int,
        leaderSeat: SeatId,
        teamSeats: List<SeatId>,
        visibility: EventVisibility = EventVisibility.PUBLIC
    ): TeamProposed = TeamProposed(
        eventId = newEventId(),
        occurredOrder = 0,
        recordedAt = now(),
        visibility = visibility,
        questNo = questNo,
        proposalNo = proposalNo,
        leaderSeat = leaderSeat,
        teamSeats = teamSeats
    )

    fun teamVoteRecorded(
        questNo: Int,
        proposalNo: Int,
        playerCountAtVote: Int,
        votesBySeat: Map<SeatId, TeamVoteChoice>? = null,
        approveCount: Int? = null,
        rejectCount: Int? = null,
        unknownVoteCount: Int? = null,
        visibility: EventVisibility = EventVisibility.PUBLIC
    ): TeamVoteRecorded = TeamVoteRecorded(
        eventId = newEventId(),
        occurredOrder = 0,
        recordedAt = now(),
        visibility = visibility,
        questNo = questNo,
        proposalNo = proposalNo,
        votesBySeat = votesBySeat,
        approveCount = approveCount,
        rejectCount = rejectCount,
        unknownVoteCount = unknownVoteCount,
        playerCountAtVote = playerCountAtVote
    )

    fun teamProposalResolved(
        questNo: Int,
        proposalNo: Int,
        decision: ProposalDecision,
        playerCountAtVote: Int,
        approveCount: Int? = null,
        rejectCount: Int? = null,
        visibility: EventVisibility = EventVisibility.PUBLIC
    ): TeamProposalResolved = TeamProposalResolved(
        eventId = newEventId(),
        occurredOrder = 0,
        recordedAt = now(),
        visibility = visibility,
        questNo = questNo,
        proposalNo = proposalNo,
        decision = decision,
        approveCount = approveCount,
        rejectCount = rejectCount,
        playerCountAtVote = playerCountAtVote,
        verified = false
    )

    fun missionResolved(
        questNo: Int,
        teamSizeExpected: Int,
        teamSeats: List<SeatId>? = null,
        failCount: Int? = null,
        observedOutcome: MissionOutcome? = null,
        visibility: EventVisibility = EventVisibility.PUBLIC
    ): MissionResolved = MissionResolved(
        eventId = newEventId(),
        occurredOrder = 0,
        recordedAt = now(),
        visibility = visibility,
        questNo = questNo,
        teamSeats = teamSeats,
        teamSizeExpected = teamSizeExpected,
        failCount = failCount,
        observedOutcome = observedOutcome,
        verified = false
    )

    fun playerClaimed(
        speakerSeat: SeatId,
        claimType: ClaimType,
        questNo: Int? = null,
        claimedRole: RoleId? = null,
        targetSeats: List<SeatId> = emptyList(),
        text: String? = null,
        proposalNo: Int? = null,
        visibility: EventVisibility = EventVisibility.PUBLIC
    ): PlayerClaimed = PlayerClaimed(
        eventId = newEventId(),
        occurredOrder = 0,
        recordedAt = now(),
        visibility = visibility,
        questNo = questNo,
        speakerSeat = speakerSeat,
        claimType = claimType,
        claimedRole = claimedRole,
        targetSeats = targetSeats,
        text = text,
        proposalNo = proposalNo
    )

    fun privateNoteAdded(
        observerSeat: SeatId,
        beliefType: BeliefType,
        availableFromOrder: Int,
        questNo: Int? = null,
        targetSeats: List<SeatId> = emptyList(),
        text: String? = null
    ): PrivateNoteAdded = PrivateNoteAdded(
        eventId = newEventId(),
        occurredOrder = 0,
        recordedAt = now(),
        visibility = EventVisibility.PRIVATE_OBSERVER,
        questNo = questNo,
        observerSeat = observerSeat,
        beliefType = beliefType,
        targetSeats = targetSeats,
        text = text,
        availableFromOrder = availableFromOrder
    )

    fun assassinationRecorded(
        assassinSeat: SeatId,
        targetSeat: SeatId,
        hitMerlin: Boolean? = null,
        questNo: Int? = null,
        visibility: EventVisibility = EventVisibility.PUBLIC
    ): AssassinationRecorded = AssassinationRecorded(
        eventId = newEventId(),
        occurredOrder = 0,
        recordedAt = now(),
        visibility = visibility,
        questNo = questNo,
        assassinSeat = assassinSeat,
        targetSeat = targetSeat,
        hitMerlin = hitMerlin
    )

    fun matchAbandoned(
        reason: String? = null,
        visibility: EventVisibility = EventVisibility.PUBLIC
    ): MatchAbandoned = MatchAbandoned(
        eventId = newEventId(),
        occurredOrder = 0,
        recordedAt = now(),
        visibility = visibility,
        reason = reason
    )

    fun matchFinalized(
        winReason: WinReason,
        winner: Alignment? = null,
        note: String? = null,
        visibility: EventVisibility = EventVisibility.PUBLIC
    ): MatchFinalized = MatchFinalized(
        eventId = newEventId(),
        occurredOrder = 0,
        recordedAt = now(),
        visibility = visibility,
        winner = winner,
        winReason = winReason,
        note = note
    )
}
