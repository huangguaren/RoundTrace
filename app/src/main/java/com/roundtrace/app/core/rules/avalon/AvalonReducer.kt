package com.roundtrace.app.core.rules.avalon

import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.AssassinationRecorded
import com.roundtrace.app.core.model.AssassinationState
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.GameOutcome
import com.roundtrace.app.core.model.GameState
import com.roundtrace.app.core.model.IntegrityStatus
import com.roundtrace.app.core.model.IssueCode
import com.roundtrace.app.core.model.MatchAbandoned
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MatchFinalized
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.model.PrivateNoteAdded
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.ProposalState
import com.roundtrace.app.core.model.QuestStarted
import com.roundtrace.app.core.model.QuestState
import com.roundtrace.app.core.model.ReconstructedMatch
import com.roundtrace.app.core.model.RecordPhase
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.RuleIssue
import com.roundtrace.app.core.model.SeatId
import com.roundtrace.app.core.model.TeamProposalResolved
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteChoice
import com.roundtrace.app.core.model.TeamVoteRecorded
import com.roundtrace.app.core.model.WinReason
import com.roundtrace.app.core.replay.ReplayEngine
import com.roundtrace.app.core.rules.GameReducer
import com.roundtrace.app.core.rules.ReductionResult

/**
 * 阿瓦隆状态机（ARCHITECTURE §6.1 / §6.2 / §6.3，TASKS.md T20）。
 *
 * 契约：
 * - `reduce` **绝不抛异常**：任何非法事件只产生 [RuleIssue] 并降级处理（T20⑤）；
 * - 终局优先级严格按 §6.2；
 * - `consecutiveRejections`（R）在 `QUEST_STARTED`、提案批准、任务结算后归零（T20③）；
 * - 未核实（verified=false）的任务**不计入** `goodSuccessCount` / `evilFailureCount`（T20④）；
 * - 提案可判定性按 §6.3 三分支实现（T20⑥）。
 *
 * 所有取值都来自 [AvalonRules]，本文件不重复定义规则常量（规则不散落）。
 */
class AvalonReducer(
    private val rules: AvalonRules = AvalonRules()
) : GameReducer {

    override fun initialState(match: ReconstructedMatch): GameState = rules.initialState(match)

    override fun step(state: GameState, event: MatchEvent): ReductionResult {
        // 1) 静态校验（不改变状态）
        val validationIssues = rules.validateEvent(state, event)
        // 2) 状态推进（内部只累积 issue，不抛异常）
        val next = rules.reduce(state, event)
        // 3) 只返回本次新增的 issue（state.issues 是累积容器）
        val producedByTransition = next.issues.drop(state.issues.size)
        return ReductionResult(state = next, issues = validationIssues + producedByTransition)
    }

    override fun replay(match: ReconstructedMatch, upToOccurredOrder: Int?): ReductionResult =
        ReplayEngine(this).replay(match = match, upTo = upToOccurredOrder)
}

/**
 * 状态机的唯一实现（内部对象）。
 *
 * 放在 `AvalonReducer.kt` 里由 [AvalonReducer] 与 [AvalonRules.reduce] 共同转调，
 * 保证「同一个状态机只有一份代码」。
 */
internal object AvalonStateMachine {

    /**
     * 提案通过的可判定性（ARCHITECTURE §6.3，三分支）。
     *
     * ```
     * if a == null                                   -> UNKNOWN
     * else if a > n/2                                -> APPROVED
     * else if a + (u ?: (n - a - (r ?: 0))) <= n/2   -> REJECTED
     * else                                            -> UNKNOWN
     * ```
     */
    fun decideProposal(
        approveCount: Int?,
        rejectCount: Int?,
        unknownVoteCount: Int?,
        playerCount: Int
    ): ProposalDecision {
        if (approveCount == null) return ProposalDecision.UNKNOWN
        val n = playerCount.coerceAtLeast(0)
        if (approveCount > n / 2) return ProposalDecision.APPROVED
        val remaining = (n - approveCount - (rejectCount ?: 0)).coerceAtLeast(0)
        val unknown = unknownVoteCount ?: remaining
        return if (approveCount + unknown <= n / 2) {
            ProposalDecision.REJECTED
        } else {
            ProposalDecision.UNKNOWN
        }
    }

    /** 单事件推进（不抛异常）。 */
    fun reduce(rules: AvalonRules, state: GameState, event: MatchEvent): GameState {
        val advanced = state.copy(
            eventCount = state.eventCount + 1,
            lastAppliedOrder = maxOf(state.lastAppliedOrder, event.occurredOrder)
        )

        val next: GameState = when (event) {
            is QuestStarted -> onQuestStarted(rules, advanced, event)
            is TeamProposed -> onTeamProposed(advanced, event)
            is TeamVoteRecorded -> onVoteRecorded(advanced, event)
            is TeamProposalResolved -> onProposalResolved(rules, advanced, event)
            is MissionResolved -> onMissionResolved(rules, advanced, event)
            is PlayerClaimed -> onPlayerClaimed(advanced, event)
            is PrivateNoteAdded -> onPrivateNoteAdded(advanced, event)
            is AssassinationRecorded -> onAssassination(advanced, event)
            is MatchAbandoned -> onMatchAbandoned(advanced, event)
            is MatchFinalized -> onMatchFinalized(advanced, event)
        }

        return evaluateFinalization(next, event)
    }

    // ——————————————————————————————————————————————————————————
    // 事件处理
    // ——————————————————————————————————————————————————————————

    /** §6.1 第 1 行：QUEST_STARTED */
    private fun onQuestStarted(
        rules: AvalonRules,
        state: GameState,
        event: QuestStarted
    ): GameState {
        val n = state.config.playerCount
        val q = event.questNo

        if (q !in 1..AvalonPreset.QUEST_COUNT) {
            return withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.QUEST_OUT_OF_ORDER,
                    severity = RuleIssue.Severity.WARNING,
                    questNo = q,
                    eventId = event.eventId,
                    args = mapOf("questNo" to q.toString())
                ),
                needsReview = true
            )
        }
        if (event.leaderSeat !in 1..n) {
            return withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.LEADER_SEAT_INVALID,
                    severity = RuleIssue.Severity.WARNING,
                    questNo = q,
                    seats = listOf(event.leaderSeat),
                    eventId = event.eventId,
                    args = mapOf("seat" to event.leaderSeat.toString(), "max" to n.toString())
                ),
                needsReview = true
            )
        }

        val existing = state.quests.firstOrNull { it.questNo == q }
        val startsNewQuest = q == state.currentQuestNo + 1
        val isLeaderRevision = !startsNewQuest &&
            q == state.currentQuestNo &&
            state.phase == RecordPhase.PROPOSAL_OPEN &&
            existing != null &&
            existing.proposals.isEmpty()

        // §6.1 合法前置 phase：只看任务号是不够的——待刺杀（ASSASSINATION）与终局（ENDED）
        // 阶段即使任务号连续，也不允许再开新任务。
        val phaseAllowsQuestStart = state.phase == RecordPhase.NOT_STARTED ||
            state.phase == RecordPhase.QUEST_RESOLVED ||
            state.phase == RecordPhase.PROPOSAL_OPEN

        if (!phaseAllowsQuestStart) {
            return withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.QUEST_OUT_OF_ORDER,
                    severity = RuleIssue.Severity.WARNING,
                    questNo = q,
                    eventId = event.eventId,
                    args = mapOf(
                        "questNo" to q.toString(),
                        "currentQuestNo" to state.currentQuestNo.toString(),
                        "phase" to state.phase.name
                    )
                ),
                needsReview = true
            )
        }

        return when {
            startsNewQuest -> {
                val expectedSize = rules.expectedTeamSizeFor(n, q)
                val threshold = if (emptyMatrixGuard(n, q)) rules.failThreshold(n, q) else 1
                val quest = QuestState(
                    questNo = q,
                    leaderSeat = event.leaderSeat,
                    proposals = emptyList(),
                    expectedTeamSize = expectedSize,
                    failThreshold = threshold,
                    teamSeats = null,
                    failCount = null,
                    outcome = null,
                    verified = false,
                    resolvedAtOrder = null
                )
                state.copy(
                    currentQuestNo = q,
                    currentProposalNo = 0,
                    consecutiveRejections = 0,
                    currentLeaderSeat = event.leaderSeat,
                    phase = RecordPhase.PROPOSAL_OPEN,
                    status = if (state.status == MatchStatus.DRAFT) {
                        MatchStatus.IN_PROGRESS
                    } else {
                        state.status
                    },
                    quests = (state.quests + quest).sortedBy { it.questNo }
                )
            }

            isLeaderRevision -> state.copy(
                currentLeaderSeat = event.leaderSeat,
                quests = state.quests.map {
                    if (it.questNo == q) it.copy(leaderSeat = event.leaderSeat) else it
                }
            )

            else -> withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.QUEST_OUT_OF_ORDER,
                    severity = RuleIssue.Severity.WARNING,
                    questNo = q,
                    eventId = event.eventId,
                    args = mapOf(
                        "questNo" to q.toString(),
                        "currentQuestNo" to state.currentQuestNo.toString(),
                        "phase" to state.phase.name
                    )
                ),
                needsReview = true
            )
        }
    }

    /** §6.1 第 2 行：TEAM_PROPOSED */
    private fun onTeamProposed(state: GameState, event: TeamProposed): GameState {
        val n = state.config.playerCount

        if (event.questNo != state.currentQuestNo) {
            return withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.QUEST_OUT_OF_ORDER,
                    severity = RuleIssue.Severity.WARNING,
                    questNo = event.questNo,
                    proposalNo = event.proposalNo,
                    eventId = event.eventId,
                    args = mapOf(
                        "questNo" to event.questNo.toString(),
                        "currentQuestNo" to state.currentQuestNo.toString()
                    )
                ),
                needsReview = true
            )
        }
        if (state.phase != RecordPhase.PROPOSAL_OPEN && state.phase != RecordPhase.VOTING) {
            return withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.PROPOSAL_AFTER_RESOLVED,
                    severity = RuleIssue.Severity.WARNING,
                    questNo = event.questNo,
                    proposalNo = event.proposalNo,
                    eventId = event.eventId,
                    args = mapOf("phase" to state.phase.name)
                ),
                needsReview = true
            )
        }

        val isNextProposal = event.proposalNo == state.currentProposalNo + 1
        val isRevisionOfCurrent =
            event.proposalNo == state.currentProposalNo && state.currentProposalNo > 0
        if (!isNextProposal && !isRevisionOfCurrent) {
            return withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.PROPOSAL_NO_OUT_OF_ORDER,
                    severity = RuleIssue.Severity.WARNING,
                    questNo = event.questNo,
                    proposalNo = event.proposalNo,
                    eventId = event.eventId,
                    args = mapOf(
                        "proposalNo" to event.proposalNo.toString(),
                        "currentProposalNo" to state.currentProposalNo.toString()
                    )
                ),
                needsReview = true
            )
        }

        val quest = state.quests.firstOrNull { it.questNo == event.questNo }
            ?: return withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.DATA_INCOMPLETE,
                    severity = RuleIssue.Severity.WARNING,
                    questNo = event.questNo,
                    eventId = event.eventId,
                    args = mapOf("missing" to "quest")
                ),
                needsReview = true
            )

        val expectedSize = quest.expectedTeamSize
        val issues = mutableListOf<RuleIssue>()

        val outOfRange = event.teamSeats.filter { it !in 1..n }.distinct().sorted()
        if (outOfRange.isNotEmpty()) {
            issues += issue(
                code = IssueCode.SEAT_OUT_OF_RANGE,
                severity = RuleIssue.Severity.BLOCKING,
                questNo = event.questNo,
                proposalNo = event.proposalNo,
                seats = outOfRange,
                eventId = event.eventId,
                args = mapOf("seats" to outOfRange.joinToString(","), "max" to n.toString())
            )
        }

        val duplicates = event.teamSeats
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()
        if (duplicates.isNotEmpty()) {
            issues += issue(
                code = IssueCode.DUPLICATE_SEAT,
                severity = RuleIssue.Severity.BLOCKING,
                questNo = event.questNo,
                proposalNo = event.proposalNo,
                seats = duplicates,
                eventId = event.eventId,
                args = mapOf("seats" to duplicates.joinToString(","))
            )
        }

        val distinctSize = event.teamSeats.distinct().size
        if (expectedSize > 0 && distinctSize != expectedSize) {
            issues += issue(
                code = IssueCode.TEAM_SIZE_MISMATCH,
                severity = RuleIssue.Severity.BLOCKING,
                questNo = event.questNo,
                proposalNo = event.proposalNo,
                seats = event.teamSeats,
                eventId = event.eventId,
                args = mapOf("expected" to expectedSize.toString(), "actual" to distinctSize.toString())
            )
        }

        val valid = issues.isEmpty()
        val proposal = ProposalState(
            questNo = event.questNo,
            proposalNo = event.proposalNo,
            leaderSeat = event.leaderSeat,
            teamSeats = event.teamSeats,
            expectedTeamSize = expectedSize,
            approveCount = null,
            rejectCount = null,
            unknownVoteCount = null,
            votesBySeat = null,
            decision = ProposalDecision.UNKNOWN,
            decisionVerified = false,
            proposedAtOrder = event.occurredOrder,
            resolvedAtOrder = null,
            sourceEventIds = listOf(event.eventId)
        )

        val updatedQuest = quest.copy(
            leaderSeat = quest.leaderSeat ?: event.leaderSeat,
            proposals = (quest.proposals.filterNot { it.proposalNo == event.proposalNo } + proposal)
                .sortedBy { it.proposalNo }
        )

        return state.copy(
            currentProposalNo = event.proposalNo,
            currentLeaderSeat = event.leaderSeat,
            // 人数不符/重复/越界 → 保存事件但 phase 不变，integrity=NEEDS_REVIEW（§6.1 降级处理）
            phase = if (valid) RecordPhase.VOTING else state.phase,
            quests = replaceQuest(state.quests, updatedQuest),
            issues = state.issues + issues,
            integrity = if (valid) state.integrity else IntegrityStatus.NEEDS_REVIEW
        )
    }

    /** §6.1 第 3 行：TEAM_VOTE_RECORDED */
    private fun onVoteRecorded(state: GameState, event: TeamVoteRecorded): GameState {
        val n = state.config.playerCount

        if (state.phase != RecordPhase.VOTING) {
            return withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.PROPOSAL_AFTER_RESOLVED,
                    severity = RuleIssue.Severity.WARNING,
                    questNo = event.questNo,
                    proposalNo = event.proposalNo,
                    eventId = event.eventId,
                    args = mapOf("phase" to state.phase.name)
                ),
                needsReview = true
            )
        }
        if (event.questNo != state.currentQuestNo || event.proposalNo != state.currentProposalNo) {
            return withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.PROPOSAL_NO_OUT_OF_ORDER,
                    severity = RuleIssue.Severity.WARNING,
                    questNo = event.questNo,
                    proposalNo = event.proposalNo,
                    eventId = event.eventId,
                    args = mapOf(
                        "proposalNo" to event.proposalNo.toString(),
                        "currentProposalNo" to state.currentProposalNo.toString()
                    )
                ),
                needsReview = true
            )
        }

        val quest = state.quests.firstOrNull { it.questNo == event.questNo }
            ?: return withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.DATA_INCOMPLETE,
                    severity = RuleIssue.Severity.WARNING,
                    questNo = event.questNo,
                    eventId = event.eventId,
                    args = mapOf("missing" to "quest")
                ),
                needsReview = true
            )
        val proposal = quest.proposals.firstOrNull { it.proposalNo == event.proposalNo }
            ?: return withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.DATA_INCOMPLETE,
                    severity = RuleIssue.Severity.WARNING,
                    questNo = event.questNo,
                    proposalNo = event.proposalNo,
                    eventId = event.eventId,
                    args = mapOf("missing" to "proposal")
                ),
                needsReview = true
            )

        val issues = mutableListOf<RuleIssue>()
        val seatsVotes = event.votesBySeat
        val approveFromSeats = seatsVotes?.values?.count { it == TeamVoteChoice.APPROVE }
        val rejectFromSeats = seatsVotes?.values?.count { it == TeamVoteChoice.REJECT }
        val unknownFromSeats = seatsVotes?.values?.count { it == TeamVoteChoice.UNKNOWN }

        val approve = event.approveCount ?: approveFromSeats
        val reject = event.rejectCount ?: rejectFromSeats
        val unknown = event.unknownVoteCount ?: unknownFromSeats

        val badSeats = seatsVotes?.keys?.filter { it !in 1..n }?.sorted().orEmpty()
        if (badSeats.isNotEmpty()) {
            issues += issue(
                code = IssueCode.SEAT_OUT_OF_RANGE,
                severity = RuleIssue.Severity.WARNING,
                questNo = event.questNo,
                proposalNo = event.proposalNo,
                seats = badSeats,
                eventId = event.eventId,
                args = mapOf("seats" to badSeats.joinToString(","))
            )
        }

        if ((approve ?: 0) + (reject ?: 0) > event.playerCountAtVote) {
            issues += issue(
                code = IssueCode.VOTE_COUNT_EXCEEDS_PLAYERS,
                severity = RuleIssue.Severity.BLOCKING,
                questNo = event.questNo,
                proposalNo = event.proposalNo,
                eventId = event.eventId,
                args = mapOf(
                    "approve" to (approve ?: 0).toString(),
                    "reject" to (reject ?: 0).toString(),
                    "players" to event.playerCountAtVote.toString()
                )
            )
        }

        if (seatsVotes != null &&
            ((event.approveCount != null && approveFromSeats != null && event.approveCount != approveFromSeats) ||
                (event.rejectCount != null && rejectFromSeats != null && event.rejectCount != rejectFromSeats))
        ) {
            issues += issue(
                code = IssueCode.VOTE_SUM_MISMATCH,
                severity = RuleIssue.Severity.WARNING,
                questNo = event.questNo,
                proposalNo = event.proposalNo,
                eventId = event.eventId,
                args = mapOf(
                    "approveSummary" to (event.approveCount?.toString() ?: "null"),
                    "approveBySeat" to (approveFromSeats?.toString() ?: "null"),
                    "rejectSummary" to (event.rejectCount?.toString() ?: "null"),
                    "rejectBySeat" to (rejectFromSeats?.toString() ?: "null")
                )
            )
        }

        val updatedProposal = proposal.copy(
            approveCount = approve,
            rejectCount = reject,
            unknownVoteCount = unknown,
            votesBySeat = seatsVotes,
            sourceEventIds = proposal.sourceEventIds + event.eventId
        )
        val updatedQuest = quest.copy(
            proposals = quest.proposals.map {
                if (it.proposalNo == updatedProposal.proposalNo) updatedProposal else it
            }
        )

        return state.copy(
            quests = replaceQuest(state.quests, updatedQuest),
            issues = state.issues + issues,
            integrity = if (issues.any { it.isBlocking }) IntegrityStatus.NEEDS_REVIEW else state.integrity
        )
    }

    /** §6.1 第 4 行：TEAM_PROPOSAL_RESOLVED */
    private fun onProposalResolved(
        rules: AvalonRules,
        state: GameState,
        event: TeamProposalResolved
    ): GameState {
        val n = state.config.playerCount

        if (state.phase != RecordPhase.VOTING) {
            return withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.PROPOSAL_AFTER_RESOLVED,
                    severity = RuleIssue.Severity.WARNING,
                    questNo = event.questNo,
                    proposalNo = event.proposalNo,
                    eventId = event.eventId,
                    args = mapOf("phase" to state.phase.name)
                ),
                needsReview = true
            )
        }
        if (event.questNo != state.currentQuestNo || event.proposalNo != state.currentProposalNo) {
            return withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.PROPOSAL_NO_OUT_OF_ORDER,
                    severity = RuleIssue.Severity.WARNING,
                    questNo = event.questNo,
                    proposalNo = event.proposalNo,
                    eventId = event.eventId,
                    args = mapOf(
                        "proposalNo" to event.proposalNo.toString(),
                        "currentProposalNo" to state.currentProposalNo.toString()
                    )
                ),
                needsReview = true
            )
        }

        val quest = state.quests.firstOrNull { it.questNo == event.questNo }
            ?: return withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.DATA_INCOMPLETE,
                    severity = RuleIssue.Severity.WARNING,
                    questNo = event.questNo,
                    eventId = event.eventId,
                    args = mapOf("missing" to "quest")
                ),
                needsReview = true
            )
        val proposal = quest.proposals.firstOrNull { it.proposalNo == event.proposalNo }
            ?: return withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.DATA_INCOMPLETE,
                    severity = RuleIssue.Severity.WARNING,
                    questNo = event.questNo,
                    proposalNo = event.proposalNo,
                    eventId = event.eventId,
                    args = mapOf("missing" to "proposal")
                ),
                needsReview = true
            )

        // 用「已记录的票数」重算可判定性，与用户填的 decision 比对
        val computed = decideProposal(
            approveCount = proposal.approveCount ?: event.approveCount,
            rejectCount = proposal.rejectCount ?: event.rejectCount,
            unknownVoteCount = proposal.unknownVoteCount,
            playerCount = event.playerCountAtVote
        )
        val conflict = computed != ProposalDecision.UNKNOWN && computed != event.decision
        val undecidable = computed == ProposalDecision.UNKNOWN && event.decision != ProposalDecision.UNKNOWN
        val verified = !conflict && !undecidable && event.decision != ProposalDecision.UNKNOWN

        val issues = mutableListOf<RuleIssue>()
        if (conflict) {
            issues += issue(
                code = IssueCode.DECISION_VOTE_CONFLICT,
                severity = RuleIssue.Severity.WARNING,
                questNo = event.questNo,
                proposalNo = event.proposalNo,
                eventId = event.eventId,
                args = mapOf("recorded" to event.decision.name, "computed" to computed.name)
            )
        }
        if (undecidable) {
            issues += issue(
                code = IssueCode.VOTE_DECISION_UNDECIDABLE,
                severity = RuleIssue.Severity.WARNING,
                questNo = event.questNo,
                proposalNo = event.proposalNo,
                eventId = event.eventId,
                args = mapOf("recorded" to event.decision.name)
            )
        }

        val resolvedProposal = proposal.copy(
            decision = event.decision,
            decisionVerified = verified,
            approveCount = proposal.approveCount ?: event.approveCount,
            rejectCount = proposal.rejectCount ?: event.rejectCount,
            resolvedAtOrder = event.occurredOrder,
            sourceEventIds = proposal.sourceEventIds + event.eventId
        )

        var updatedQuest = quest.copy(
            proposals = quest.proposals.map {
                if (it.proposalNo == resolvedProposal.proposalNo) resolvedProposal else it
            }
        )

        var nextState = state.copy(
            quests = replaceQuest(state.quests, updatedQuest),
            issues = state.issues + issues,
            integrity = if (conflict || undecidable) IntegrityStatus.NEEDS_REVIEW else state.integrity
        )

        when (event.decision) {
            ProposalDecision.APPROVED -> {
                // 提案通过 → 进入任务执行；R 归零（§6.4）
                updatedQuest = updatedQuest.copy(teamSeats = proposal.teamSeats)
                nextState = nextState.copy(
                    phase = RecordPhase.MISSION_PENDING,
                    consecutiveRejections = 0,
                    quests = replaceQuest(nextState.quests, updatedQuest)
                )
            }

            ProposalDecision.REJECTED -> {
                val rejections = state.consecutiveRejections + 1
                val nextLeader = rules.nextLeaderSeat(state.currentLeaderSeat, n)
                nextState = nextState.copy(
                    consecutiveRejections = rejections,
                    currentProposalNo = state.currentProposalNo + 1,
                    currentLeaderSeat = nextLeader,
                    phase = RecordPhase.PROPOSAL_OPEN
                )
            }

            ProposalDecision.UNKNOWN -> {
                // 决议未知：保留 phase，等待补录（不推进）
            }
        }

        return nextState
    }

    /** §6.1 第 5 行：MISSION_RESOLVED */
    private fun onMissionResolved(
        rules: AvalonRules,
        state: GameState,
        event: MissionResolved
    ): GameState {
        val n = state.config.playerCount

        if (state.phase != RecordPhase.MISSION_PENDING) {
            return withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.MISSION_WITHOUT_APPROVED_PROPOSAL,
                    severity = RuleIssue.Severity.BLOCKING,
                    questNo = event.questNo,
                    eventId = event.eventId,
                    args = mapOf("phase" to state.phase.name)
                )
            )
        }
        if (event.questNo != state.currentQuestNo) {
            return withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.QUEST_OUT_OF_ORDER,
                    severity = RuleIssue.Severity.WARNING,
                    questNo = event.questNo,
                    eventId = event.eventId,
                    args = mapOf(
                        "questNo" to event.questNo.toString(),
                        "currentQuestNo" to state.currentQuestNo.toString()
                    )
                ),
                needsReview = true
            )
        }

        val quest = state.quests.firstOrNull { it.questNo == event.questNo }
            ?: return withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.DATA_INCOMPLETE,
                    severity = RuleIssue.Severity.WARNING,
                    questNo = event.questNo,
                    eventId = event.eventId,
                    args = mapOf("missing" to "quest")
                ),
                needsReview = true
            )

        if (quest.isResolved) {
            return withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.MISSION_ALREADY_RESOLVED,
                    severity = RuleIssue.Severity.WARNING,
                    questNo = event.questNo,
                    eventId = event.eventId,
                    args = mapOf("resolvedAtOrder" to (quest.resolvedAtOrder ?: -1).toString())
                ),
                needsReview = true
            )
        }

        val issues = mutableListOf<RuleIssue>()
        var verified = true
        val approvedTeam = quest.teamSeats
        val recordedTeam = event.teamSeats

        if (recordedTeam == null) {
            verified = false
            issues += issue(
                code = IssueCode.DATA_INCOMPLETE,
                severity = RuleIssue.Severity.WARNING,
                questNo = event.questNo,
                eventId = event.eventId,
                args = mapOf("missing" to "teamSeats")
            )
        } else {
            val badSeats = recordedTeam.filter { it !in 1..n }.distinct().sorted()
            if (badSeats.isNotEmpty()) {
                verified = false
                issues += issue(
                    code = IssueCode.SEAT_OUT_OF_RANGE,
                    severity = RuleIssue.Severity.BLOCKING,
                    questNo = event.questNo,
                    seats = badSeats,
                    eventId = event.eventId,
                    args = mapOf("seats" to badSeats.joinToString(","))
                )
            }
            if (recordedTeam.size != recordedTeam.distinct().size) {
                verified = false
                issues += issue(
                    code = IssueCode.DUPLICATE_SEAT,
                    severity = RuleIssue.Severity.BLOCKING,
                    questNo = event.questNo,
                    seats = recordedTeam,
                    eventId = event.eventId,
                    args = mapOf("seats" to recordedTeam.sorted().joinToString(","))
                )
            }
            if (quest.expectedTeamSize > 0 && recordedTeam.distinct().size != quest.expectedTeamSize) {
                verified = false
                issues += issue(
                    code = IssueCode.DATA_INCOMPLETE,
                    severity = RuleIssue.Severity.WARNING,
                    questNo = event.questNo,
                    seats = recordedTeam,
                    eventId = event.eventId,
                    args = mapOf(
                        "expected" to quest.expectedTeamSize.toString(),
                        "actual" to recordedTeam.distinct().size.toString()
                    )
                )
            }
            if (approvedTeam != null && approvedTeam.toSet() != recordedTeam.toSet()) {
                // ARCHITECTURE §9.3-10：以 MissionResolved.teamSeats 为准，标记未核实、不计数
                verified = false
                issues += issue(
                    code = IssueCode.MISSION_TEAM_MISMATCH,
                    severity = RuleIssue.Severity.WARNING,
                    questNo = event.questNo,
                    seats = recordedTeam,
                    eventId = event.eventId,
                    args = mapOf(
                        "approved" to approvedTeam.sorted().joinToString(","),
                        "recorded" to recordedTeam.sorted().joinToString(",")
                    )
                )
            }
        }

        val failCount = event.failCount
        val teamSizeForCheck = recordedTeam?.distinct()?.size ?: event.teamSizeExpected
        if (failCount == null) {
            verified = false
            issues += issue(
                code = IssueCode.DATA_INCOMPLETE,
                severity = RuleIssue.Severity.WARNING,
                questNo = event.questNo,
                eventId = event.eventId,
                args = mapOf("missing" to "failCount")
            )
        } else if (failCount < 0 || failCount > teamSizeForCheck) {
            verified = false
            issues += issue(
                code = IssueCode.FAIL_COUNT_EXCEEDS_TEAM,
                severity = RuleIssue.Severity.BLOCKING,
                questNo = event.questNo,
                seats = recordedTeam ?: emptyList(),
                eventId = event.eventId,
                args = mapOf(
                    "failCount" to failCount.toString(),
                    "teamSize" to teamSizeForCheck.toString()
                )
            )
        }

        val derivedOutcome: MissionOutcome? = if (failCount != null && failCount in 0..teamSizeForCheck) {
            if (rules.isMissionFailed(failCount, n, event.questNo)) {
                MissionOutcome.FAILED
            } else {
                MissionOutcome.SUCCESS
            }
        } else {
            null
        }

        if (derivedOutcome != null && event.observedOutcome != null &&
            derivedOutcome != event.observedOutcome
        ) {
            verified = false
            issues += issue(
                code = IssueCode.FAIL_COUNT_OUTCOME_CONFLICT,
                severity = RuleIssue.Severity.WARNING,
                questNo = event.questNo,
                eventId = event.eventId,
                args = mapOf(
                    "derived" to derivedOutcome.name,
                    "observed" to event.observedOutcome.name
                )
            )
        }

        // 结果：优先用失败牌数推导；不能推导时退回用户观察值；都没有则 UNKNOWN
        val finalOutcome = derivedOutcome ?: event.observedOutcome ?: MissionOutcome.UNKNOWN

        val updatedQuest = quest.copy(
            teamSeats = recordedTeam ?: approvedTeam,
            failCount = failCount,
            outcome = finalOutcome,
            verified = verified,
            resolvedAtOrder = event.occurredOrder
        )

        var nextState = state.copy(
            quests = replaceQuest(state.quests, updatedQuest),
            phase = RecordPhase.QUEST_RESOLVED,
            // 任务结算后 R 归零（T20③）
            consecutiveRejections = 0,
            issues = state.issues + issues,
            integrity = when {
                issues.any { it.isBlocking } -> IntegrityStatus.NEEDS_REVIEW
                issues.isNotEmpty() -> IntegrityStatus.NEEDS_REVIEW
                else -> state.integrity
            }
        )

        // 任务结果明确就计数（verified 只影响"是否需要复核"，不阻断终局判定）
        when (finalOutcome) {
            MissionOutcome.SUCCESS -> nextState =
                nextState.copy(goodSuccessCount = nextState.goodSuccessCount + 1)

            MissionOutcome.FAILED -> nextState =
                nextState.copy(evilFailureCount = nextState.evilFailureCount + 1)

            MissionOutcome.UNKNOWN -> Unit
        }

        return nextState
    }

    /** §6.1 第 6 行：PLAYER_CLAIMED（只追加事实，不改状态） */
    private fun onPlayerClaimed(state: GameState, event: PlayerClaimed): GameState {
        val n = state.config.playerCount
        val badSeats = buildList {
            if (event.speakerSeat !in 1..n) add(event.speakerSeat)
            addAll(event.targetSeats.filter { it !in 1..n })
        }.distinct().sorted()
        if (badSeats.isEmpty()) return state
        return withIssue(
            state = state,
            issue = issue(
                code = IssueCode.SEAT_OUT_OF_RANGE,
                severity = RuleIssue.Severity.WARNING,
                questNo = event.questNo,
                seats = badSeats,
                eventId = event.eventId,
                args = mapOf("seats" to badSeats.joinToString(","))
            )
        )
    }

    /** §6.1 第 7 行：PRIVATE_NOTE_ADDED（只追加，不改状态） */
    private fun onPrivateNoteAdded(state: GameState, event: PrivateNoteAdded): GameState {
        val n = state.config.playerCount
        val badSeats = buildList {
            if (event.observerSeat !in 1..n) add(event.observerSeat)
            addAll(event.targetSeats.filter { it !in 1..n })
        }.distinct().sorted()
        if (badSeats.isEmpty()) return state
        return withIssue(
            state = state,
            issue = issue(
                code = IssueCode.SEAT_OUT_OF_RANGE,
                severity = RuleIssue.Severity.WARNING,
                questNo = event.questNo,
                seats = badSeats,
                eventId = event.eventId,
                args = mapOf("seats" to badSeats.joinToString(","))
            )
        )
    }

    /** §6.1 第 8 行：ASSASSINATION_RECORDED */
    private fun onAssassination(state: GameState, event: AssassinationRecorded): GameState {
        val n = state.config.playerCount

        if (state.status != MatchStatus.AWAITING_ASSASSINATION) {
            return withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.ASSASSINATION_NOT_ALLOWED,
                    severity = RuleIssue.Severity.BLOCKING,
                    eventId = event.eventId,
                    args = mapOf("status" to state.status.name)
                )
            )
        }
        val badSeats = listOf(event.assassinSeat, event.targetSeat).filter { it !in 1..n }.distinct().sorted()
        if (badSeats.isNotEmpty()) {
            return withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.SEAT_OUT_OF_RANGE,
                    severity = RuleIssue.Severity.BLOCKING,
                    seats = badSeats,
                    eventId = event.eventId,
                    args = mapOf("seats" to badSeats.joinToString(","))
                )
            )
        }

        val targetRole = state.roleTruth[event.targetSeat] ?: RoleId.UNKNOWN
        val derived: Boolean? = when {
            targetRole == RoleId.MERLIN -> true
            targetRole == RoleId.UNKNOWN -> null
            else -> false
        }
        val hit = event.hitMerlin ?: derived

        val assassination = AssassinationState(
            assassinSeat = event.assassinSeat,
            targetSeat = event.targetSeat,
            hitMerlin = hit,
            recordedAtOrder = event.occurredOrder
        )

        if (hit == null) {
            // 目标角色未知 → 维持待刺杀，绝不猜测命中（ARCHITECTURE §9.3-6）
            return withIssue(
                state = state.copy(assassination = assassination),
                issue = issue(
                    code = IssueCode.UNKNOWN_ROLE_FOR_OUTCOME,
                    severity = RuleIssue.Severity.WARNING,
                    seats = listOf(event.targetSeat),
                    eventId = event.eventId,
                    args = mapOf("seat" to event.targetSeat.toString())
                ),
                needsReview = true
            )
        }

        val outcome = if (hit) {
            GameOutcome(
                winner = Alignment.EVIL,
                reason = WinReason.ASSASSIN_HIT_MERLIN,
                decidedAtOrder = event.occurredOrder,
                evidenceEventIds = listOf(event.eventId)
            )
        } else {
            GameOutcome(
                winner = Alignment.GOOD,
                reason = WinReason.ASSASSIN_MISSED,
                decidedAtOrder = event.occurredOrder,
                evidenceEventIds = listOf(event.eventId)
            )
        }

        return state.copy(
            assassination = assassination,
            status = if (hit) MatchStatus.FINISHED_EVIL else MatchStatus.FINISHED_GOOD,
            phase = RecordPhase.ENDED,
            outcome = outcome
        )
    }

    /** §6.1 第 9 行：MATCH_ABANDONED */
    private fun onMatchAbandoned(state: GameState, event: MatchAbandoned): GameState {
        if (state.isFinished) {
            return withIssue(
                state = state,
                issue = issue(
                    code = IssueCode.EVENT_AFTER_MATCH_FINISHED,
                    severity = RuleIssue.Severity.INFO,
                    eventId = event.eventId,
                    args = mapOf("status" to state.status.name)
                )
            )
        }
        return state.copy(
            status = MatchStatus.ABANDONED,
            phase = RecordPhase.ENDED,
            integrity = IntegrityStatus.INCOMPLETE
        )
    }

    /** §6.1 第 10 行：MATCH_FINALIZED（只做核对，绝不覆盖规则结果） */
    private fun onMatchFinalized(state: GameState, event: MatchFinalized): GameState {
        val outcome = state.outcome ?: return state
        val winnerConflict = event.winner != null && event.winner != outcome.winner
        val reasonConflict = event.winReason != WinReason.UNKNOWN && event.winReason != outcome.reason
        if (!winnerConflict && !reasonConflict) return state
        return withIssue(
            state = state,
            issue = issue(
                code = IssueCode.FINALIZED_CONFLICT,
                severity = RuleIssue.Severity.WARNING,
                eventId = event.eventId,
                args = mapOf(
                    "recordedWinner" to (event.winner?.name ?: "null"),
                    "computedWinner" to outcome.winner.name,
                    "recordedReason" to event.winReason.name,
                    "computedReason" to outcome.reason.name
                )
            ),
            needsReview = true
        )
    }

    // ——————————————————————————————————————————————————————————
    // 终局评估（ARCHITECTURE §6.2）
    // ——————————————————————————————————————————————————————————

    /**
     * 终局优先级：
     * 1. 已在等刺杀 → 不动（`determineOutcome` 返回 null）
     * 2. `evilFailureCount >= 3` → 坏人胜 / THREE_MISSIONS_FAILED
     * 3. `consecutiveRejections >= 5` → 坏人胜 / FIVE_CONSECUTIVE_REJECTS
     * 4. `goodSuccessCount >= 3` → 4a 计划含刺客：AWAITING_ASSASSINATION；4b 不含：好人胜 / NO_ASSASSIN_PRESENT
     * 5. 其他 → 不终局
     */
    private fun evaluateFinalization(state: GameState, event: MatchEvent): GameState {
        if (state.status == MatchStatus.FINISHED_GOOD ||
            state.status == MatchStatus.FINISHED_EVIL ||
            state.status == MatchStatus.ABANDONED
        ) {
            return state
        }
        if (state.status == MatchStatus.AWAITING_ASSASSINATION) return state

        if (state.evilFailureCount >= AvalonPreset.MISSIONS_TO_WIN) {
            return state.copy(
                status = MatchStatus.FINISHED_EVIL,
                phase = RecordPhase.ENDED,
                outcome = GameOutcome(
                    winner = Alignment.EVIL,
                    reason = WinReason.THREE_MISSIONS_FAILED,
                    decidedAtOrder = event.occurredOrder,
                    evidenceEventIds = listOf(event.eventId)
                )
            )
        }

        if (state.consecutiveRejections >= AvalonPreset.MAX_CONSECUTIVE_REJECTIONS) {
            return state.copy(
                status = MatchStatus.FINISHED_EVIL,
                phase = RecordPhase.ENDED,
                outcome = GameOutcome(
                    winner = Alignment.EVIL,
                    reason = WinReason.FIVE_CONSECUTIVE_REJECTS,
                    decidedAtOrder = event.occurredOrder,
                    evidenceEventIds = listOf(event.eventId)
                )
            )
        }

        if (state.goodSuccessCount >= AvalonPreset.MISSIONS_TO_WIN) {
            return if (state.config.hasRole(RoleId.ASSASSIN)) {
                state.copy(
                    status = MatchStatus.AWAITING_ASSASSINATION,
                    phase = RecordPhase.ASSASSINATION
                )
            } else {
                state.copy(
                    status = MatchStatus.FINISHED_GOOD,
                    phase = RecordPhase.ENDED,
                    outcome = GameOutcome(
                        winner = Alignment.GOOD,
                        reason = WinReason.NO_ASSASSIN_PRESENT,
                        decidedAtOrder = event.occurredOrder,
                        evidenceEventIds = listOf(event.eventId)
                    )
                )
            }
        }

        return state
    }

    // ——————————————————————————————————————————————————————————
    // 工具
    // ——————————————————————————————————————————————————————————

    private fun emptyMatrixGuard(playerCount: Int, questNo: Int): Boolean =
        playerCount in AvalonPreset.MIN_PLAYERS..AvalonPreset.MAX_PLAYERS &&
            questNo in 1..AvalonPreset.QUEST_COUNT

    private fun replaceQuest(quests: List<QuestState>, quest: QuestState): List<QuestState> =
        quests.map { if (it.questNo == quest.questNo) quest else it }.sortedBy { it.questNo }

    private fun withIssue(
        state: GameState,
        issue: RuleIssue,
        needsReview: Boolean = false
    ): GameState = state.copy(
        issues = state.issues + issue,
        integrity = if (needsReview && state.integrity == IntegrityStatus.OK) {
            IntegrityStatus.NEEDS_REVIEW
        } else {
            state.integrity
        }
    )

    private fun issue(
        code: IssueCode,
        severity: RuleIssue.Severity,
        questNo: Int? = null,
        proposalNo: Int? = null,
        seats: List<SeatId> = emptyList(),
        eventId: EventId? = null,
        args: Map<String, String> = emptyMap()
    ): RuleIssue = RuleIssue(
        code = code,
        severity = severity,
        questNo = questNo,
        proposalNo = proposalNo,
        seats = seats,
        eventId = eventId,
        args = args
    )
}
