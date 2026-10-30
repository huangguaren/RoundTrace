package com.roundtrace.app.core.model

/**
 * 对局派生状态（ARCHITECTURE §2.4）。
 *
 * **不是事实源**：本对象 100% 由 `ReplayEngine` 从事件重算得出，不落库为权威数据。
 *
 * @param roleTruth 重放时的角色真值快照（可能含 UNKNOWN）。规则仅用它裁决刺杀命中，不做推断。
 * @param currentQuestNo 0 = 未开始
 * @param currentProposalNo 当前任务内第几次提案（0 = 尚无提案）
 * @param quests 已完成 + 进行中（**按 questNo 升序不可变**）
 * @param consecutiveRejections 仅当前任务内累计，任务结算/开始时重置
 * @param goodSuccessCount 仅计 verified 的任务成功
 * @param evilFailureCount 仅计 verified 的任务失败
 * @param outcome null = 尚未终局
 * @param lastAppliedOrder 已应用到的最大 occurredOrder
 * @param eventCount 已应用事件数（用于「事件修订号」）
 */
data class GameState(
    val matchId: MatchId,
    val config: GameConfig,
    val roleTruth: Map<SeatId, RoleId>,
    val status: MatchStatus,
    val phase: RecordPhase,
    val currentQuestNo: Int,
    val currentProposalNo: Int,
    val currentLeaderSeat: SeatId?,
    val quests: List<QuestState>,
    val consecutiveRejections: Int,
    val goodSuccessCount: Int,
    val evilFailureCount: Int,
    val assassination: AssassinationState?,
    val outcome: GameOutcome?,
    val integrity: IntegrityStatus,
    val issues: List<RuleIssue>,
    val lastAppliedOrder: Int,
    val eventCount: Int
) {

    /** 当前任务的派生状态；无则 null。 */
    val currentQuest: QuestState?
        get() = quests.firstOrNull { it.questNo == currentQuestNo }

    /** 当前任务内最后一个提案（= 待处理提案）。 */
    val pendingProposal: ProposalState?
        get() = currentQuest?.proposals?.lastOrNull { it.proposalNo == currentProposalNo }

    /** 是否已终局。 */
    val isFinished: Boolean
        get() = status == MatchStatus.FINISHED_GOOD || status == MatchStatus.FINISHED_EVIL

    init {
        // 单次事件推进时保持升序；序列化/构造脏数据也不允许乱序（TASKS.md T15①）。
        require(quests == quests.sortedBy { it.questNo }) {
            "quests 必须按 questNo 升序：${quests.map { it.questNo }}"
        }
    }
}

/** 单个任务的状态。 */
data class QuestState(
    val questNo: Int,
    /** 该任务首个提案的队长 */
    val leaderSeat: SeatId?,
    /** 空 = 尚未提案 */
    val proposals: List<ProposalState>,
    /** 规则矩阵值 */
    val expectedTeamSize: Int,
    /** 1 或 2 */
    val failThreshold: Int,
    /** 通过队伍（来自 APPROVED 提案；可被任务事件覆盖） */
    val teamSeats: List<SeatId>?,
    val failCount: Int?,
    /** 结算结果；unknown 表示不可判定 */
    val outcome: MissionOutcome?,
    /** 是否可计入 good/evil 计数 */
    val verified: Boolean,
    val resolvedAtOrder: Int?
) {

    /** 是否已结算。 */
    val isResolved: Boolean
        get() = resolvedAtOrder != null

    /** 当前提案号（0 = 尚无提案）。 */
    val latestProposalNo: Int
        get() = proposals.maxOfOrNull { it.proposalNo } ?: 0
}

/** 单次组队提案的状态。 */
data class ProposalState(
    val questNo: Int,
    val proposalNo: Int,
    val leaderSeat: SeatId?,
    val teamSeats: List<SeatId>,
    val expectedTeamSize: Int,
    val approveCount: Int?,
    val rejectCount: Int?,
    val unknownVoteCount: Int?,
    val votesBySeat: Map<SeatId, TeamVoteChoice>?,
    val decision: ProposalDecision,
    val decisionVerified: Boolean,
    val proposedAtOrder: Int,
    val resolvedAtOrder: Int?,
    val sourceEventIds: List<EventId>
) {

    /** 是否已有决议。 */
    val isResolved: Boolean
        get() = decision != ProposalDecision.UNKNOWN
}

/** 刺杀结算状态。 */
data class AssassinationState(
    val assassinSeat: SeatId?,
    val targetSeat: SeatId?,
    val hitMerlin: Boolean?,
    val recordedAtOrder: Int?
)
