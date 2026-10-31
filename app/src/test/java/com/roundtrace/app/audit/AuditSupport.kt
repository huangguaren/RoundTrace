package com.roundtrace.app.audit

import com.roundtrace.app.core.data.MatchBundle
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.MatchSummary
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.Participant
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.QuestStarted
import com.roundtrace.app.core.model.ReconstructedMatch
import com.roundtrace.app.core.model.TeamProposalResolved
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteRecorded
import com.roundtrace.app.core.replay.ReplayEngine
import com.roundtrace.app.core.rules.GameReducer
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * QA 审计期新增的夹具（只服务 `com.roundtrace.app.audit` 下的测试）。
 *
 * 与 `com.roundtrace.app.util.*` 的区别：这里刻意提供
 * ① **可推进的时钟**（`fixedClock` 永远返回同一时刻，无法证明「某字段没被覆盖」）；
 * ② **可直接落库的事件构造器**（审计关心的是「经过仓储/DAO 的真实写入路径」，
 *    而不是 `MatchBuilder` 的纯内存链）。
 */
class MutableClock(startMs: Long) : Clock() {

    var nowMs: Long = startMs
        private set

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = Instant.ofEpochMilli(nowMs)

    /** 推进时钟（毫秒）。审计用：让 `now` 与已写入的时间戳必然不同。 */
    fun advanceBy(deltaMs: Long) {
        nowMs += deltaMs
    }
}

/** 审计专用的事件构造器：`occurredOrder` 一律填 0，由仓储盖章。 */
object AuditEvents {

    fun questStarted(id: String, questNo: Int, leaderSeat: Int): QuestStarted = QuestStarted(
        eventId = EventId(id),
        occurredOrder = 0,
        recordedAt = 0L,
        questNo = questNo,
        leaderSeat = leaderSeat
    )

    fun teamProposed(
        id: String,
        questNo: Int,
        proposalNo: Int,
        leaderSeat: Int,
        teamSeats: List<Int>
    ): TeamProposed = TeamProposed(
        eventId = EventId(id),
        occurredOrder = 0,
        recordedAt = 0L,
        questNo = questNo,
        proposalNo = proposalNo,
        leaderSeat = leaderSeat,
        teamSeats = teamSeats
    )

    fun teamVote(
        id: String,
        questNo: Int,
        proposalNo: Int,
        approve: Int,
        reject: Int,
        playerCount: Int
    ): TeamVoteRecorded = TeamVoteRecorded(
        eventId = EventId(id),
        occurredOrder = 0,
        recordedAt = 0L,
        questNo = questNo,
        proposalNo = proposalNo,
        votesBySeat = null,
        approveCount = approve,
        rejectCount = reject,
        playerCountAtVote = playerCount
    )

    fun proposalResolved(
        id: String,
        questNo: Int,
        proposalNo: Int,
        decision: ProposalDecision,
        approve: Int,
        reject: Int,
        playerCount: Int
    ): TeamProposalResolved = TeamProposalResolved(
        eventId = EventId(id),
        occurredOrder = 0,
        recordedAt = 0L,
        questNo = questNo,
        proposalNo = proposalNo,
        decision = decision,
        approveCount = approve,
        rejectCount = reject,
        playerCountAtVote = playerCount
    )

    fun missionResolved(
        id: String,
        questNo: Int,
        teamSeats: List<Int>,
        teamSizeExpected: Int,
        failCount: Int,
        observedOutcome: MissionOutcome
    ): MissionResolved = MissionResolved(
        eventId = EventId(id),
        occurredOrder = 0,
        recordedAt = 0L,
        questNo = questNo,
        teamSeats = teamSeats,
        teamSizeExpected = teamSizeExpected,
        failCount = failCount,
        observedOutcome = observedOutcome
    )
}

/** 该人数下第 [questNo] 任务的官方队伍人数。 */
fun officialTeamSize(playerCount: Int, questNo: Int): Int =
    AvalonPreset.counts(playerCount).teamSizes[questNo - 1]

/** 由事件派生状态（与 `recomputeProjectionLocked` 用的是同一套重放）。 */
fun derivedStatus(match: ReconstructedMatch, reducer: GameReducer): MatchStatus =
    ReplayEngine(reducer).replay(match).state.status

/**
 * 把领域快照包装成导出用的 [MatchBundle]。
 *
 * 之所以需要：审计要验证「导入后 `matches.status` 由重放派生」，
 * 而导入入口 `importFromJson` 只吃 JSON，所以要先造一份合法的导出文件。
 */
fun bundleOf(
    match: ReconstructedMatch,
    status: MatchStatus,
    updatedAtEpochMs: Long
): MatchBundle = MatchBundle(
    summary = MatchSummary(
        matchId = match.matchId,
        title = match.config.title,
        playerCount = match.config.playerCount,
        status = status,
        rulesetId = match.config.rulesetId,
        updatedAtEpochMs = updatedAtEpochMs,
        questsStarted = match.events.count { it is QuestStarted },
        mySeat = match.config.mySeat
    ),
    config = match.config,
    participants = match.participants.ifEmpty { (1..match.config.playerCount).map { Participant(seat = it) } },
    roleAssignments = match.roleAssignments,
    events = match.events,
    revealedAt = match.revealedAt
)

/** 席位号数组 → 事件里的 `List<SeatId>`（`SeatId` 就是 `Int` 别名，显式写出便于阅读）。 */
fun seats(vararg values: Int): List<Int> = values.toList()

/** 便于在多处复用的空配置检查（避免审计测试用到非法人数）。 */
fun isLegalPlayerCount(playerCount: Int): Boolean =
    playerCount in AvalonPreset.MIN_PLAYERS..AvalonPreset.MAX_PLAYERS

/** 便于在断言消息里打印事件类型（排查用）。 */
fun MatchEvent.shortName(): String = this::class.simpleName ?: type.name

/** 便于在断言消息里打印对局 ID（仅审计日志用，不参与逻辑）。 */
fun auditMatchId(label: String): MatchId = MatchId("audit-$label")
