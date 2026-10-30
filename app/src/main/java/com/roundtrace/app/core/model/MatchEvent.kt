package com.roundtrace.app.core.model

/**
 * 对局事件（ARCHITECTURE §2.3）。
 *
 * 设计约定（Engineer 必读）：
 * 1. [MatchEvent] 是 **sealed interface**，10 个事件类型全部显式携带 7 个公共字段
 *    （[eventId]/[occurredOrder]/[recordedAt]/[visibility]/[supersedesId]/[retracted]/[questNo]），
 *    便于 reducer 与 UI 直接访问，不做二次嵌套。
 * 2. **领域模型不带 kotlinx.serialization 注解**。序列化只在 `core/database/EventCodec.kt`
 *    与 `core/export` 目录下做 DTO 映射，保证领域层零框架耦合。
 * 3. **[occurredOrder] 只能由 Repository 分配**（`COALESCE(MAX(occurredOrder),0)+1`，同一事务内）。
 *    领域/UI 构造时填 `0`，由仓储盖章。
 * 4. `null` = 未知，`0` = 已知为零，两者不可互换。
 *
 * 纯 Kotlin：禁止 import android.* / androidx.*。
 */
sealed interface MatchEvent {

    /** 事件唯一 ID（UUID v4 字符串） */
    val eventId: EventId

    /** 游戏内发生顺序（1,2,3...），**唯一权威排序键** */
    val occurredOrder: Int

    /** 录入时刻 epoch millis UTC（与 occurredOrder 分开，回填旧事件时保留） */
    val recordedAt: Long

    /** 可见性 */
    val visibility: EventVisibility

    /** 被本事件修订/替代的旧事件 id（审计链；读路径只按 retracted 过滤） */
    val supersedesId: EventId?

    /** 已被撤销或被修订淘汰；replay 只读 `retracted == false` */
    val retracted: Boolean

    /** 所属任务序号（1..5）；SETUP/END 阶段为 null */
    val questNo: Int?

    /** 由 sealed 类型推导，供 DAO 列与 UI 分派；不可变（`val ... get()`） */
    val type: EventType
}

/** 进入第 N 次任务（由 UI「开始第 N 任务」显式产生；也可由首次提交提案时自动补建）。 */
data class QuestStarted(
    override val eventId: EventId,
    override val occurredOrder: Int,
    override val recordedAt: Long,
    override val visibility: EventVisibility = EventVisibility.PUBLIC,
    override val supersedesId: EventId? = null,
    override val retracted: Boolean = false,
    override val questNo: Int,
    val leaderSeat: SeatId
) : MatchEvent {
    override val type: EventType get() = EventType.QUEST_STARTED
}

/** 组队提案（队长提出的一支队伍）。 */
data class TeamProposed(
    override val eventId: EventId,
    override val occurredOrder: Int,
    override val recordedAt: Long,
    override val visibility: EventVisibility = EventVisibility.PUBLIC,
    override val supersedesId: EventId? = null,
    override val retracted: Boolean = false,
    override val questNo: Int,
    /** 任务内第几次提案，1-based */
    val proposalNo: Int,
    val leaderSeat: SeatId,
    /** 允许不完整/重复（非法由 validateEvent 报 issue） */
    val teamSeats: List<SeatId>
) : MatchEvent {
    override val type: EventType get() = EventType.TEAM_PROPOSED
}

/**
 * 组队投票：逐人票 或 汇总票，二者可只填一种。
 *
 * 注意：这是**组队投票**，与任务失败牌（[MissionResolved.failCount]）是不同字段，永不共用。
 */
data class TeamVoteRecorded(
    override val eventId: EventId,
    override val occurredOrder: Int,
    override val recordedAt: Long,
    override val visibility: EventVisibility = EventVisibility.PUBLIC,
    override val supersedesId: EventId? = null,
    override val retracted: Boolean = false,
    override val questNo: Int,
    val proposalNo: Int,
    /** null = 未逐人记录 */
    val votesBySeat: Map<SeatId, TeamVoteChoice>?,
    /** null = 未知；不填不代表 0 */
    val approveCount: Int?,
    val rejectCount: Int?,
    val unknownVoteCount: Int? = null,
    /** 投票时对局人数，用于半数判定 */
    val playerCountAtVote: Int
) : MatchEvent {
    override val type: EventType get() = EventType.TEAM_VOTE_RECORDED
}

/** 组队决议（通过/否决），与票数可能不一致 → `verified = false`。 */
data class TeamProposalResolved(
    override val eventId: EventId,
    override val occurredOrder: Int,
    override val recordedAt: Long,
    override val visibility: EventVisibility = EventVisibility.PUBLIC,
    override val supersedesId: EventId? = null,
    override val retracted: Boolean = false,
    override val questNo: Int,
    val proposalNo: Int,
    val decision: ProposalDecision,
    /** 冗余快照，便于时间线展示 */
    val approveCount: Int?,
    val rejectCount: Int?,
    val playerCountAtVote: Int,
    /** 由 reducer 校验后回填；false = 未核实 */
    val verified: Boolean = false
) : MatchEvent {
    override val type: EventType get() = EventType.TEAM_PROPOSAL_RESOLVED
}

/**
 * 任务牌结果（**与组队投票是不同字段，永不共用**）。
 *
 * @param teamSeats 可为不完整（用户忘记成员）；null = 完全未记录
 * @param teamSizeExpected 该任务应有队伍人数快照
 * @param failCount null = 未知；0 = 已知零失败牌
 * @param observedOutcome 用户直接观察到的成败；null = 未记录
 */
data class MissionResolved(
    override val eventId: EventId,
    override val occurredOrder: Int,
    override val recordedAt: Long,
    override val visibility: EventVisibility = EventVisibility.PUBLIC,
    override val supersedesId: EventId? = null,
    override val retracted: Boolean = false,
    override val questNo: Int,
    val teamSeats: List<SeatId>?,
    val teamSizeExpected: Int,
    val failCount: Int?,
    val observedOutcome: MissionOutcome?,
    val verified: Boolean = false
) : MatchEvent {
    override val type: EventType get() = EventType.MISSION_RESOLVED
}

/** 公开发言 / 身份声称 / 站边（Claim 永远不是事实）。 */
data class PlayerClaimed(
    override val eventId: EventId,
    override val occurredOrder: Int,
    override val recordedAt: Long,
    override val visibility: EventVisibility = EventVisibility.PUBLIC,
    override val supersedesId: EventId? = null,
    override val retracted: Boolean = false,
    override val questNo: Int?,
    val speakerSeat: SeatId,
    val claimType: ClaimType,
    /** 仅 ROLE_CLAIM / COUNTER_CLAIM 使用 */
    val claimedRole: RoleId? = null,
    val targetSeats: List<SeatId> = emptyList(),
    val text: String? = null,
    /** 若该发言发生在具体提案语境下 */
    val proposalNo: Int? = null
) : MatchEvent {
    override val type: EventType get() = EventType.PLAYER_CLAIMED
}

/** 主观判断（怀疑/信任），带可用时间点，**永不进入「事实」**。 */
data class PrivateNoteAdded(
    override val eventId: EventId,
    override val occurredOrder: Int,
    override val recordedAt: Long,
    override val visibility: EventVisibility = EventVisibility.PRIVATE_OBSERVER,
    override val supersedesId: EventId? = null,
    override val retracted: Boolean = false,
    override val questNo: Int?,
    val observerSeat: SeatId,
    val beliefType: BeliefType,
    val targetSeats: List<SeatId> = emptyList(),
    val text: String? = null,
    /** 该判断从哪个位置开始可见；回填历史时用它控制「不提前显示」 */
    val availableFromOrder: Int
) : MatchEvent {
    override val type: EventType get() = EventType.PRIVATE_NOTE_ADDED
}

/** 刺杀结算。 */
data class AssassinationRecorded(
    override val eventId: EventId,
    override val occurredOrder: Int,
    override val recordedAt: Long,
    override val visibility: EventVisibility = EventVisibility.PUBLIC,
    override val supersedesId: EventId? = null,
    override val retracted: Boolean = false,
    override val questNo: Int? = null,
    val assassinSeat: SeatId,
    val targetSeat: SeatId,
    /** null = 角色未揭晓，暂不能判定 */
    val hitMerlin: Boolean? = null
) : MatchEvent {
    override val type: EventType get() = EventType.ASSASSINATION_RECORDED
}

/** 弃局。 */
data class MatchAbandoned(
    override val eventId: EventId,
    override val occurredOrder: Int,
    override val recordedAt: Long,
    override val visibility: EventVisibility = EventVisibility.PUBLIC,
    override val supersedesId: EventId? = null,
    override val retracted: Boolean = false,
    override val questNo: Int? = null,
    val reason: String? = null
) : MatchEvent {
    override val type: EventType get() = EventType.MATCH_ABANDONED
}

/** 终局核对（与 rules 推导结果比对；冲突**不得覆盖**规则结果）。 */
data class MatchFinalized(
    override val eventId: EventId,
    override val occurredOrder: Int,
    override val recordedAt: Long,
    override val visibility: EventVisibility = EventVisibility.PUBLIC,
    override val supersedesId: EventId? = null,
    override val retracted: Boolean = false,
    override val questNo: Int? = null,
    /** 用户填写的胜方，可为 null */
    val winner: Alignment?,
    val winReason: WinReason,
    val note: String? = null
) : MatchEvent {
    override val type: EventType get() = EventType.MATCH_FINALIZED
}
