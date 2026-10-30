package com.roundtrace.app.util

import com.roundtrace.app.core.model.AssassinationRecorded
import com.roundtrace.app.core.model.BeliefType
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.GameConfig
import com.roundtrace.app.core.model.GameState
import com.roundtrace.app.core.model.GameType
import com.roundtrace.app.core.model.MatchAbandoned
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MatchFinalized
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.Participant
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.model.PrivateNoteAdded
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.QuestStarted
import com.roundtrace.app.core.model.ReconstructedMatch
import com.roundtrace.app.core.model.RoleAssignment
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.RoleSlotSpec
import com.roundtrace.app.core.model.RoleSource
import com.roundtrace.app.core.model.RevealState
import com.roundtrace.app.core.model.TeamProposalResolved
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteChoice
import com.roundtrace.app.core.model.TeamVoteRecorded
import com.roundtrace.app.core.model.WinReason
import com.roundtrace.app.core.rules.ReductionResult
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.rules.avalon.AvalonReducer
import com.roundtrace.app.core.rules.avalon.AvalonRules

/**
 * 测试夹具：链式构建一场对局（TASKS.md T23）。
 *
 * 设计要点：
 * - 事件顺序 = 调用顺序，自动分配 `occurredOrder`（1,2,3…）与递增 `recordedAt`；
 * - `eventId` 为可读的 `e-N`，便于断言；
 * - **允许显式制造 `UNKNOWN`**：`mission(teamSeats = [...不完整])`、`failCount = null` 等，
 *   这是真实现场录入的常态，测试数据不得用「看起来合理」的值填满（TDD §11 的要求）。
 *
 * 用法：
 * ```
 * val builder = MatchBuilder.eightPlayerMerlinMordred()
 * builder.questStarted(1, 1).teamProposed(1, 1, 1, listOf(1, 2, 3))...
 * val state = builder.replay()
 * ```
 */
class MatchBuilder private constructor(
    val playerCount: Int,
    private val rolesBySeat: Map<Int, RoleId>,
    private val roleSlots: List<RoleSlotSpec>,
    private val presetId: String,
    private val mySeat: Int?,
    private val initialLeaderSeat: Int,
    private val revealedAtMillis: Long?
) {

    private val rules: AvalonRules = AvalonRules()
    private val reducer: AvalonReducer = AvalonReducer(rules)

    private val events: MutableList<MatchEvent> = mutableListOf()
    private var orderCounter: Int = 0
    private var idCounter: Int = 0
    private var clockCounter: Long = BASE_EPOCH_MS

    // ————————————————————————————————————————————————————————
    // 事件构建
    // ————————————————————————————————————————————————————————

    /** 进入第 [questNo] 个任务。 */
    fun questStarted(questNo: Int, leaderSeat: Int): MatchBuilder = apply {
        events += QuestStarted(
            eventId = nextEventId(),
            occurredOrder = nextOrder(),
            recordedAt = nextRecordedAt(),
            visibility = EventVisibility.PUBLIC,
            questNo = questNo,
            leaderSeat = leaderSeat
        )
    }

    /** 组队提案。 */
    fun teamProposed(
        questNo: Int,
        proposalNo: Int,
        leaderSeat: Int,
        teamSeats: List<Int>
    ): MatchBuilder = apply {
        events += TeamProposed(
            eventId = nextEventId(),
            occurredOrder = nextOrder(),
            recordedAt = nextRecordedAt(),
            visibility = EventVisibility.PUBLIC,
            questNo = questNo,
            proposalNo = proposalNo,
            leaderSeat = leaderSeat,
            teamSeats = teamSeats
        )
    }

    /** 组队投票（汇总票 + 可选逐人票）。 */
    fun teamVoteRecorded(
        questNo: Int,
        proposalNo: Int,
        approveCount: Int? = null,
        rejectCount: Int? = null,
        unknownVoteCount: Int? = null,
        votesBySeat: Map<Int, TeamVoteChoice>? = null,
        playerCountAtVote: Int = playerCount
    ): MatchBuilder = apply {
        events += TeamVoteRecorded(
            eventId = nextEventId(),
            occurredOrder = nextOrder(),
            recordedAt = nextRecordedAt(),
            visibility = EventVisibility.PUBLIC,
            questNo = questNo,
            proposalNo = proposalNo,
            votesBySeat = votesBySeat,
            approveCount = approveCount,
            rejectCount = rejectCount,
            unknownVoteCount = unknownVoteCount,
            playerCountAtVote = playerCountAtVote
        )
    }

    /** 组队决议。 */
    fun teamProposalResolved(
        questNo: Int,
        proposalNo: Int,
        decision: ProposalDecision,
        approveCount: Int? = null,
        rejectCount: Int? = null,
        playerCountAtVote: Int = playerCount
    ): MatchBuilder = apply {
        events += TeamProposalResolved(
            eventId = nextEventId(),
            occurredOrder = nextOrder(),
            recordedAt = nextRecordedAt(),
            visibility = EventVisibility.PUBLIC,
            questNo = questNo,
            proposalNo = proposalNo,
            decision = decision,
            approveCount = approveCount,
            rejectCount = rejectCount,
            playerCountAtVote = playerCountAtVote
        )
    }

    /** 任务牌结果。`teamSeats` 允许不完整或 null，`failCount` 允许 null（= 未知）。 */
    fun missionResolved(
        questNo: Int,
        teamSeats: List<Int>?,
        failCount: Int?,
        observedOutcome: MissionOutcome? = null,
        teamSizeExpected: Int = rules.expectedTeamSizeFor(playerCount, questNo)
    ): MatchBuilder = apply {
        events += MissionResolved(
            eventId = nextEventId(),
            occurredOrder = nextOrder(),
            recordedAt = nextRecordedAt(),
            visibility = EventVisibility.PUBLIC,
            questNo = questNo,
            teamSeats = teamSeats,
            teamSizeExpected = teamSizeExpected,
            failCount = failCount,
            observedOutcome = observedOutcome
        )
    }

    /** 公开发言。 */
    fun playerClaimed(
        speakerSeat: Int,
        claimType: ClaimType,
        questNo: Int? = null,
        claimedRole: RoleId? = null,
        targetSeats: List<Int> = emptyList(),
        text: String? = null
    ): MatchBuilder = apply {
        events += PlayerClaimed(
            eventId = nextEventId(),
            occurredOrder = nextOrder(),
            recordedAt = nextRecordedAt(),
            visibility = EventVisibility.PUBLIC,
            questNo = questNo,
            speakerSeat = speakerSeat,
            claimType = claimType,
            claimedRole = claimedRole,
            targetSeats = targetSeats,
            text = text
        )
    }

    /** 私人笔记。 */
    fun privateNoteAdded(
        observerSeat: Int,
        beliefType: BeliefType,
        targetSeats: List<Int> = emptyList(),
        questNo: Int? = null,
        availableFromOrder: Int = orderCounter + 1,
        text: String? = null
    ): MatchBuilder = apply {
        events += PrivateNoteAdded(
            eventId = nextEventId(),
            occurredOrder = nextOrder(),
            recordedAt = nextRecordedAt(),
            visibility = EventVisibility.PRIVATE_OBSERVER,
            questNo = questNo,
            observerSeat = observerSeat,
            beliefType = beliefType,
            targetSeats = targetSeats,
            text = text,
            availableFromOrder = availableFromOrder
        )
    }

    /** 刺杀结算。 */
    fun assassinationRecorded(
        assassinSeat: Int,
        targetSeat: Int,
        hitMerlin: Boolean? = null
    ): MatchBuilder = apply {
        events += AssassinationRecorded(
            eventId = nextEventId(),
            occurredOrder = nextOrder(),
            recordedAt = nextRecordedAt(),
            visibility = EventVisibility.PUBLIC,
            assassinSeat = assassinSeat,
            targetSeat = targetSeat,
            hitMerlin = hitMerlin
        )
    }

    /** 弃局。 */
    fun matchAbandoned(reason: String? = "测试弃局"): MatchBuilder = apply {
        events += MatchAbandoned(
            eventId = nextEventId(),
            occurredOrder = nextOrder(),
            recordedAt = nextRecordedAt(),
            visibility = EventVisibility.PUBLIC,
            reason = reason
        )
    }

    /** 终局核对。 */
    fun matchFinalized(
        winReason: WinReason,
        winner: com.roundtrace.app.core.model.Alignment? = null
    ): MatchBuilder = apply {
        events += MatchFinalized(
            eventId = nextEventId(),
            occurredOrder = nextOrder(),
            recordedAt = nextRecordedAt(),
            visibility = EventVisibility.PUBLIC,
            winner = winner,
            winReason = winReason
        )
    }

    /**
     * 一次完整的「提案 → 投票 → 决议 → 任务结果」闭环。
     *
     * @param approve 赞成票数（默认全员赞成）
     * @param reject 反对票数
     * @param decision 决议（默认 APPROVED）
     */
    fun questCycle(
        questNo: Int,
        leaderSeat: Int,
        teamSeats: List<Int>,
        failCount: Int?,
        observedOutcome: MissionOutcome? = null,
        proposalNo: Int = 1,
        approve: Int = playerCount,
        reject: Int = 0,
        decision: ProposalDecision = ProposalDecision.APPROVED,
        missionTeamSeats: List<Int>? = teamSeats
    ): MatchBuilder = apply {
        questStarted(questNo, leaderSeat)
        teamProposed(questNo, proposalNo, leaderSeat, teamSeats)
        teamVoteRecorded(
            questNo = questNo,
            proposalNo = proposalNo,
            approveCount = approve,
            rejectCount = reject
        )
        teamProposalResolved(
            questNo = questNo,
            proposalNo = proposalNo,
            decision = decision,
            approveCount = approve,
            rejectCount = reject
        )
        missionResolved(
            questNo = questNo,
            teamSeats = missionTeamSeats,
            failCount = failCount,
            observedOutcome = observedOutcome
        )
    }

    /** 追加一个「已存在的」事件（用于构造修订前后的历史）。 */
    fun appendRaw(event: MatchEvent): MatchBuilder = apply { events += event }

    // ————————————————————————————————————————————————————————
    // 输出
    // ————————————————————————————————————————————————————————

    /** 当前事件列表（不可变快照）。 */
    fun events(): List<MatchEvent> = events.toList()

    /** 事件条数。 */
    val eventCount: Int
        get() = events.size

    /** 角色真值表（座位 → 角色）。 */
    fun roleTruth(): Map<Int, RoleId> = rolesBySeat.toMap()

    /** 构造重放输入。 */
    fun build(revealedAtEpochMs: Long? = revealedAtMillis): ReconstructedMatch {
        val config = GameConfig(
            gameType = GameType.AVALON,
            rulesetId = AvalonPreset.RULESET_ID,
            rulesetVersion = AvalonPreset.RULESET_VERSION,
            presetId = presetId,
            playerCount = playerCount,
            roleSlots = roleSlots,
            mySeat = mySeat,
            initialLeaderSeat = initialLeaderSeat,
            title = "测试对局 ${playerCount} 人",
            createdAtEpochMs = BASE_EPOCH_MS
        )
        val assignments = rolesBySeat.map { (seat, role) ->
            val effectiveRole = if (unknownRoleSeats.contains(seat)) RoleId.UNKNOWN else role
            seat to RoleAssignment(
                seat = seat,
                roleId = effectiveRole,
                source = RoleSource.PRESET_MANUAL,
                revealState = if (revealedAtEpochMs != null) RevealState.REVEALED else RevealState.HIDDEN
            )
        }.toMap()

        return ReconstructedMatch(
            matchId = MatchId("test-match-$playerCount"),
            config = config,
            participants = (1..playerCount).map { Participant(seat = it) },
            roleAssignments = assignments,
            events = events.sortedBy { it.occurredOrder },
            revealedAt = revealedAtEpochMs
        )
    }

    /** 全量重放。 */
    fun replay(upTo: Int? = null): GameState = replayResult(upTo).state

    /** 全量重放（带 issue）。 */
    fun replayResult(upTo: Int? = null): ReductionResult =
        reducer.replay(build(), upTo)

    /** 角色为 UNKNOWN 的变体（模拟「角色尚未填写」）。 */
    fun withUnknownRole(seat: Int): MatchBuilder = apply {
        unknownRoleSeats += seat
    }

    private val unknownRoleSeats: MutableSet<Int> = mutableSetOf()

    companion object {

        /** 固定基准时刻，保证测试完全确定性。 */
        const val BASE_EPOCH_MS: Long = 1_700_000_000_000L

        /**
         * TDD §11 的 8 人真实案例：
         * 1 梅林 / 2 忠臣 / 3 莫甘娜 / 4 莫德雷德 / 5 刺客 / 6 忠臣 / 7 派西维尔 / 8 忠臣。
         */
        fun eightPlayerMerlinMordred(): MatchBuilder = of(
            playerCount = 8,
            rolesBySeat = RoleFixtures.EIGHT_PLAYER_MERLIN_MORDRED,
            roleSlots = AvalonPreset.standardPreset(8),
            presetId = AvalonPreset.presetId(8),
            mySeat = RoleFixtures.MERLIN_SEAT,
            initialLeaderSeat = 1
        )

        /** 自定义人数与角色表（按标准预设填 roleSlots）。 */
        fun of(
            playerCount: Int,
            rolesBySeat: Map<Int, RoleId>,
            roleSlots: List<RoleSlotSpec> = AvalonPreset.standardPreset(playerCount),
            presetId: String = AvalonPreset.presetId(playerCount),
            mySeat: Int? = 1,
            initialLeaderSeat: Int = 1,
            revealedAtEpochMs: Long? = null
        ): MatchBuilder {
            require(rolesBySeat.keys.all { it in 1..playerCount }) {
                "角色表的座位必须在 1..$playerCount：${rolesBySeat.keys}"
            }
            require(rolesBySeat.size == playerCount) {
                "角色表必须覆盖全部 $playerCount 个座位，实际 ${rolesBySeat.size} 个"
            }
            return MatchBuilder(
                playerCount = playerCount,
                rolesBySeat = rolesBySeat,
                roleSlots = roleSlots,
                presetId = presetId,
                mySeat = mySeat,
                initialLeaderSeat = initialLeaderSeat,
                revealedAtMillis = revealedAtEpochMs
            )
        }
    }

    private fun nextOrder(): Int {
        orderCounter += 1
        return orderCounter
    }

    private fun nextEventId(): EventId {
        idCounter += 1
        return EventId("e-$idCounter")
    }

    private fun nextRecordedAt(): Long {
        clockCounter += 1_000L
        return clockCounter
    }
}
