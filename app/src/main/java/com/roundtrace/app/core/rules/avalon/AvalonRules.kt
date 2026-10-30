package com.roundtrace.app.core.rules.avalon

import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.AssassinationRecorded
import com.roundtrace.app.core.model.GameConfig
import com.roundtrace.app.core.model.GameOutcome
import com.roundtrace.app.core.model.GameState
import com.roundtrace.app.core.model.GameType
import com.roundtrace.app.core.model.IntegrityStatus
import com.roundtrace.app.core.model.MatchAbandoned
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MatchFinalized
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.model.PrivateNoteAdded
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.QuestStarted
import com.roundtrace.app.core.model.ReconstructedMatch
import com.roundtrace.app.core.model.RecordPhase
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.RuleIssue
import com.roundtrace.app.core.model.IssueCode
import com.roundtrace.app.core.model.SeatId
import com.roundtrace.app.core.model.TeamProposalResolved
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteChoice
import com.roundtrace.app.core.model.TeamVoteRecorded

/**
 * 阿瓦隆标准规则（ARCHITECTURE §3 / TDD §4.1）。
 *
 * 本类只负责：规则常量、配置校验、事件静态校验、初始状态、终局推导。
 * 状态机推进逻辑放在 `AvalonReducer.kt`（同一个 `AvalonStateMachine`），本类 `reduce` 转调它，
 * 保证「接口契约」与「状态机实现」只有一份。
 *
 * 纯 Kotlin：**禁止** `@Composable`、禁止持有 `Context`（TASKS.md T18⑤）。
 */
class AvalonRules : com.roundtrace.app.core.rules.GameRules {

    override val gameType: GameType = GameType.AVALON
    override val rulesetId: String = AvalonPreset.RULESET_ID
    override val rulesetVersion: Int = AvalonPreset.RULESET_VERSION

    // ——————————————————————————————————————————————————————————
    // 规则常量
    // ——————————————————————————————————————————————————————————

    override fun teamSize(playerCount: Int, questNo: Int): Int {
        val counts = AvalonPreset.counts(playerCount)
        require(questNo in 1..AvalonPreset.QUEST_COUNT) {
            "questNo 必须在 1..${AvalonPreset.QUEST_COUNT}，实际为 $questNo"
        }
        return counts.teamSizes[questNo - 1]
    }

    /**
     * 失败牌门槛：`if (playerCount >= 7 && questNo == 4) 2 else 1`（TDD §4.1，逐字一致）。
     */
    override fun failThreshold(playerCount: Int, questNo: Int): Int =
        if (playerCount >= 7 && questNo == 4) 2 else 1

    /** 组队是否通过：`approveCount > playerCount / 2`（平票视为否决，R02）。 */
    override fun isTeamApproved(approveCount: Int, playerCount: Int): Boolean =
        approveCount > playerCount / 2

    /** 任务是否失败：失败牌数达到该任务的失败牌门槛。 */
    override fun isMissionFailed(failCount: Int, playerCount: Int, questNo: Int): Boolean =
        failCount >= failThreshold(playerCount, questNo)

    // ——————————————————————————————————————————————————————————
    // 配置校验（7 个配置类 IssueCode 全覆盖）
    // ——————————————————————————————————————————————————————————

    override fun validateSetup(config: GameConfig): List<RuleIssue> {
        val issues = mutableListOf<RuleIssue>()
        val playerCount = config.playerCount
        val rangeValid = playerCount in AvalonPreset.MIN_PLAYERS..AvalonPreset.MAX_PLAYERS

        if (!rangeValid) {
            issues += issue(
                code = IssueCode.PLAYER_COUNT_OUT_OF_RANGE,
                severity = RuleIssue.Severity.BLOCKING,
                args = mapOf(
                    "min" to AvalonPreset.MIN_PLAYERS.toString(),
                    "max" to AvalonPreset.MAX_PLAYERS.toString(),
                    "actual" to playerCount.toString()
                )
            )
        }

        // 负数角色槽（脏数据只报 issue，不抛异常）
        val negativeSlots = config.roleSlots.filter { it.count < 0 }
        if (negativeSlots.isNotEmpty()) {
            issues += issue(
                code = IssueCode.ROLE_SLOT_NEGATIVE,
                severity = RuleIssue.Severity.BLOCKING,
                args = mapOf(
                    "roles" to negativeSlots.joinToString(",") { it.roleId.name },
                    "counts" to negativeSlots.joinToString(",") { it.count.toString() }
                )
            )
        }

        // 按角色聚合（同一角色重复出现也按总量算）
        val countByRole: Map<RoleId, Int> = config.roleSlots
            .groupBy { it.roleId }
            .mapValues { entry -> entry.value.sumOf { it.count } }

        val plannedTotal = countByRole.values.sum()
        if (rangeValid && plannedTotal != playerCount) {
            issues += issue(
                code = IssueCode.ROLE_COUNT_MISMATCH,
                severity = RuleIssue.Severity.BLOCKING,
                args = mapOf(
                    "expected" to playerCount.toString(),
                    "actual" to plannedTotal.toString()
                )
            )
        }

        if (rangeValid) {
            val expected = AvalonPreset.counts(playerCount)
            val plannedGood = config.plannedAlignmentCount(Alignment.GOOD)
            val plannedEvil = config.plannedAlignmentCount(Alignment.EVIL)
            if (plannedGood != expected.good || plannedEvil != expected.evil) {
                // ARCHITECTURE §9.3-3：计划构成与人数矩阵不一致 → 警告，不阻断（现场可能填错，事后揭晓）
                issues += issue(
                    code = IssueCode.ALIGNMENT_COUNT_MISMATCH,
                    severity = RuleIssue.Severity.WARNING,
                    args = mapOf(
                        "expectedGood" to expected.good.toString(),
                        "expectedEvil" to expected.evil.toString(),
                        "actualGood" to plannedGood.toString(),
                        "actualEvil" to plannedEvil.toString()
                    )
                )
            }
        }

        // 唯一性：至多 1 张的角色（派西维尔/莫甘娜/莫德雷德/奥伯伦）+ 标准局必须唯一的梅林/刺客
        val atMostOne = RoleId.AT_MOST_ONE + RoleId.REQUIRED_SINGLETON_IN_STANDARD
        atMostOne.distinct().forEach { role ->
            val count = countByRole[role] ?: 0
            if (count > 1) {
                issues += issue(
                    code = IssueCode.ROLE_UNIQUENESS_VIOLATED,
                    severity = RuleIssue.Severity.BLOCKING,
                    args = mapOf("role" to role.name, "count" to count.toString(), "max" to "1")
                )
            }
        }

        // 标准局：梅林与刺客必须各恰好 1 张（ARCHITECTURE §9.3-4）
        if (AvalonPreset.isStandardPreset(playerCount, config.presetId)) {
            RoleId.REQUIRED_SINGLETON_IN_STANDARD.forEach { role ->
                val count = countByRole[role] ?: 0
                if (count != 1) {
                    issues += issue(
                        code = IssueCode.ROLE_UNIQUENESS_VIOLATED,
                        severity = RuleIssue.Severity.BLOCKING,
                        args = mapOf("role" to role.name, "count" to count.toString(), "max" to "1")
                    )
                }
            }
        }

        if (rangeValid && config.initialLeaderSeat !in 1..playerCount) {
            issues += issue(
                code = IssueCode.LEADER_SEAT_INVALID,
                severity = RuleIssue.Severity.BLOCKING,
                seats = listOf(config.initialLeaderSeat),
                args = mapOf("seat" to config.initialLeaderSeat.toString(), "max" to playerCount.toString())
            )
        }

        val mySeat = config.mySeat
        if (rangeValid && mySeat != null && mySeat !in 1..playerCount) {
            issues += issue(
                code = IssueCode.MY_SEAT_INVALID,
                severity = RuleIssue.Severity.BLOCKING,
                seats = listOf(mySeat),
                args = mapOf("seat" to mySeat.toString(), "max" to playerCount.toString())
            )
        }

        return issues
    }

    // ——————————————————————————————————————————————————————————
    // 事件静态校验
    // ——————————————————————————————————————————————————————————

    override fun validateEvent(state: GameState, event: MatchEvent): List<RuleIssue> {
        val issues = mutableListOf<RuleIssue>()
        val playerCount = state.config.playerCount

        // 1) 任务序号范围
        val questNo = event.questNo
        if (questNo != null && questNo !in 1..AvalonPreset.QUEST_COUNT) {
            issues += issue(
                code = IssueCode.QUEST_OUT_OF_ORDER,
                severity = RuleIssue.Severity.WARNING,
                questNo = questNo,
                eventId = event.eventId,
                args = mapOf("questNo" to questNo.toString())
            )
        }

        // 2) 已终局后又有事实类事件
        if (state.isFinished &&
            event !is PlayerClaimed &&
            event !is PrivateNoteAdded &&
            event !is MatchFinalized &&
            event !is AssassinationRecorded
        ) {
            issues += issue(
                code = IssueCode.EVENT_AFTER_MATCH_FINISHED,
                severity = RuleIssue.Severity.WARNING,
                questNo = questNo,
                eventId = event.eventId,
                args = mapOf("status" to state.status.name, "eventType" to event.type.name)
            )
        }

        when (event) {
            is QuestStarted -> {
                if (event.leaderSeat !in 1..playerCount) {
                    issues += issue(
                        code = IssueCode.LEADER_SEAT_INVALID,
                        severity = RuleIssue.Severity.WARNING,
                        questNo = event.questNo,
                        eventId = event.eventId,
                        seats = listOf(event.leaderSeat),
                        args = mapOf("seat" to event.leaderSeat.toString(), "max" to playerCount.toString())
                    )
                }
                val expectedLeader = expectedLeaderSeat(state)
                if (expectedLeader != null && event.leaderSeat != expectedLeader) {
                    issues += issue(
                        code = IssueCode.LEADER_NOT_EXPECTED,
                        severity = RuleIssue.Severity.INFO,
                        questNo = event.questNo,
                        eventId = event.eventId,
                        seats = listOf(event.leaderSeat),
                        args = mapOf(
                            "expected" to expectedLeader.toString(),
                            "actual" to event.leaderSeat.toString()
                        )
                    )
                }
            }

            is TeamProposed -> issues += validateTeamSeats(
                teamSeats = event.teamSeats,
                expectedSize = expectedTeamSize(state, event.questNo),
                playerCount = playerCount,
                questNo = event.questNo,
                proposalNo = event.proposalNo,
                eventId = event.eventId
            )

            is TeamVoteRecorded -> {
                val approve = event.approveCount ?: 0
                val reject = event.rejectCount ?: 0
                if (approve < 0 || reject < 0) {
                    issues += issue(
                        code = IssueCode.VOTE_COUNT_EXCEEDS_PLAYERS,
                        severity = RuleIssue.Severity.BLOCKING,
                        questNo = event.questNo,
                        proposalNo = event.proposalNo,
                        eventId = event.eventId,
                        args = mapOf("approve" to approve.toString(), "reject" to reject.toString(), "players" to event.playerCountAtVote.toString())
                    )
                }
                if (approve + reject > event.playerCountAtVote) {
                    issues += issue(
                        code = IssueCode.VOTE_COUNT_EXCEEDS_PLAYERS,
                        severity = RuleIssue.Severity.BLOCKING,
                        questNo = event.questNo,
                        proposalNo = event.proposalNo,
                        eventId = event.eventId,
                        args = mapOf(
                            "approve" to approve.toString(),
                            "reject" to reject.toString(),
                            "players" to event.playerCountAtVote.toString()
                        )
                    )
                }
                // 逐人票与汇总票自洽性
                val votesBySeat = event.votesBySeat
                if (votesBySeat != null) {
                    val bySeatOutOfRange = votesBySeat.keys.filter { it !in 1..playerCount }
                    if (bySeatOutOfRange.isNotEmpty()) {
                        issues += issue(
                            code = IssueCode.SEAT_OUT_OF_RANGE,
                            severity = RuleIssue.Severity.WARNING,
                            questNo = event.questNo,
                            proposalNo = event.proposalNo,
                            seats = bySeatOutOfRange.sorted(),
                            eventId = event.eventId,
                            args = mapOf("seats" to bySeatOutOfRange.sorted().joinToString(","))
                        )
                    }
                    val approveFromSeats = votesBySeat.values.count { it == TeamVoteChoice.APPROVE }
                    val rejectFromSeats = votesBySeat.values.count { it == TeamVoteChoice.REJECT }
                    val mismatch = (event.approveCount != null && event.approveCount != approveFromSeats) ||
                        (event.rejectCount != null && event.rejectCount != rejectFromSeats)
                    if (mismatch) {
                        issues += issue(
                            code = IssueCode.VOTE_SUM_MISMATCH,
                            severity = RuleIssue.Severity.WARNING,
                            questNo = event.questNo,
                            proposalNo = event.proposalNo,
                            eventId = event.eventId,
                            args = mapOf(
                                "approveSummary" to (event.approveCount?.toString() ?: "null"),
                                "approveBySeat" to approveFromSeats.toString(),
                                "rejectSummary" to (event.rejectCount?.toString() ?: "null"),
                                "rejectBySeat" to rejectFromSeats.toString()
                            )
                        )
                    }
                }
                val decision = AvalonStateMachine.decideProposal(
                    approveCount = event.approveCount,
                    rejectCount = event.rejectCount,
                    unknownVoteCount = event.unknownVoteCount,
                    playerCount = event.playerCountAtVote
                )
                if (decision == ProposalDecision.UNKNOWN) {
                    issues += issue(
                        code = IssueCode.VOTE_DECISION_UNDECIDABLE,
                        severity = RuleIssue.Severity.INFO,
                        questNo = event.questNo,
                        proposalNo = event.proposalNo,
                        eventId = event.eventId
                    )
                }
            }

            is TeamProposalResolved -> {
                val proposal = state.quests
                    .firstOrNull { it.questNo == event.questNo }
                    ?.proposals
                    ?.firstOrNull { it.proposalNo == event.proposalNo }
                val computed = AvalonStateMachine.decideProposal(
                    approveCount = proposal?.approveCount ?: event.approveCount,
                    rejectCount = proposal?.rejectCount ?: event.rejectCount,
                    unknownVoteCount = proposal?.unknownVoteCount,
                    playerCount = event.playerCountAtVote
                )
                if (computed == ProposalDecision.UNKNOWN && event.decision != ProposalDecision.UNKNOWN) {
                    issues += issue(
                        code = IssueCode.VOTE_DECISION_UNDECIDABLE,
                        severity = RuleIssue.Severity.WARNING,
                        questNo = event.questNo,
                        proposalNo = event.proposalNo,
                        eventId = event.eventId,
                        args = mapOf("decision" to event.decision.name)
                    )
                }
                if (computed != ProposalDecision.UNKNOWN && computed != event.decision) {
                    // ARCHITECTURE §9.3-9：保留用户现场 observation，只标记未核实
                    issues += issue(
                        code = IssueCode.DECISION_VOTE_CONFLICT,
                        severity = RuleIssue.Severity.WARNING,
                        questNo = event.questNo,
                        proposalNo = event.proposalNo,
                        eventId = event.eventId,
                        args = mapOf(
                            "recorded" to event.decision.name,
                            "computed" to computed.name
                        )
                    )
                }
            }

            is MissionResolved -> {
                if (state.phase != RecordPhase.MISSION_PENDING) {
                    issues += issue(
                        code = IssueCode.MISSION_WITHOUT_APPROVED_PROPOSAL,
                        severity = RuleIssue.Severity.BLOCKING,
                        questNo = event.questNo,
                        eventId = event.eventId,
                        args = mapOf("phase" to state.phase.name)
                    )
                }
                val quest = state.quests.firstOrNull { it.questNo == event.questNo }
                if (quest != null && quest.isResolved) {
                    issues += issue(
                        code = IssueCode.MISSION_ALREADY_RESOLVED,
                        severity = RuleIssue.Severity.WARNING,
                        questNo = event.questNo,
                        eventId = event.eventId
                    )
                }
                val failCount = event.failCount
                val teamSizeForCheck = event.teamSeats?.size ?: event.teamSizeExpected
                if (failCount != null && (failCount < 0 || failCount > teamSizeForCheck)) {
                    issues += issue(
                        code = IssueCode.FAIL_COUNT_EXCEEDS_TEAM,
                        severity = RuleIssue.Severity.BLOCKING,
                        questNo = event.questNo,
                        seats = event.teamSeats ?: emptyList(),
                        eventId = event.eventId,
                        args = mapOf("failCount" to failCount.toString(), "teamSize" to teamSizeForCheck.toString())
                    )
                }
                if (failCount != null && event.observedOutcome != null) {
                    val derived = if (isMissionFailed(failCount, playerCount, event.questNo)) {
                        MissionOutcome.FAILED
                    } else {
                        MissionOutcome.SUCCESS
                    }
                    if (derived != event.observedOutcome) {
                        issues += issue(
                            code = IssueCode.FAIL_COUNT_OUTCOME_CONFLICT,
                            severity = RuleIssue.Severity.WARNING,
                            questNo = event.questNo,
                            eventId = event.eventId,
                            args = mapOf("derived" to derived.name, "observed" to event.observedOutcome.name)
                        )
                    }
                }
                val approvedTeam = quest?.teamSeats
                val actualTeam = event.teamSeats
                if (approvedTeam != null && actualTeam != null && approvedTeam.toSet() != actualTeam.toSet()) {
                    issues += issue(
                        code = IssueCode.MISSION_TEAM_MISMATCH,
                        severity = RuleIssue.Severity.WARNING,
                        questNo = event.questNo,
                        seats = actualTeam,
                        eventId = event.eventId,
                        args = mapOf(
                            "approved" to approvedTeam.sorted().joinToString(","),
                            "recorded" to actualTeam.sorted().joinToString(",")
                        )
                    )
                }
                if (actualTeam == null || failCount == null) {
                    issues += issue(
                        code = IssueCode.DATA_INCOMPLETE,
                        severity = RuleIssue.Severity.WARNING,
                        questNo = event.questNo,
                        eventId = event.eventId,
                        args = mapOf(
                            "teamSeats" to (if (actualTeam == null) "null" else "ok"),
                            "failCount" to (if (failCount == null) "null" else "ok")
                        )
                    )
                } else if (actualTeam.size != quest?.expectedTeamSize) {
                    issues += issue(
                        code = IssueCode.DATA_INCOMPLETE,
                        severity = RuleIssue.Severity.WARNING,
                        questNo = event.questNo,
                        seats = actualTeam,
                        eventId = event.eventId,
                        args = mapOf(
                            "expected" to (quest?.expectedTeamSize ?: -1).toString(),
                            "actual" to actualTeam.size.toString()
                        )
                    )
                }
            }

            is PlayerClaimed -> {
                val badSeats = buildList {
                    if (event.speakerSeat !in 1..playerCount) add(event.speakerSeat)
                    addAll(event.targetSeats.filter { it !in 1..playerCount })
                }
                if (badSeats.isNotEmpty()) {
                    issues += issue(
                        code = IssueCode.SEAT_OUT_OF_RANGE,
                        severity = RuleIssue.Severity.WARNING,
                        questNo = event.questNo,
                        seats = badSeats.sorted(),
                        eventId = event.eventId,
                        args = mapOf("seats" to badSeats.sorted().joinToString(","))
                    )
                }
            }

            is PrivateNoteAdded -> {
                val badSeats = buildList {
                    if (event.observerSeat !in 1..playerCount) add(event.observerSeat)
                    addAll(event.targetSeats.filter { it !in 1..playerCount })
                }
                if (badSeats.isNotEmpty()) {
                    issues += issue(
                        code = IssueCode.SEAT_OUT_OF_RANGE,
                        severity = RuleIssue.Severity.WARNING,
                        questNo = event.questNo,
                        seats = badSeats.sorted(),
                        eventId = event.eventId,
                        args = mapOf("seats" to badSeats.sorted().joinToString(","))
                    )
                }
            }

            is AssassinationRecorded -> {
                if (state.status != MatchStatus.AWAITING_ASSASSINATION) {
                    issues += issue(
                        code = IssueCode.ASSASSINATION_NOT_ALLOWED,
                        severity = RuleIssue.Severity.BLOCKING,
                        eventId = event.eventId,
                        args = mapOf("status" to state.status.name)
                    )
                }
                val badSeats = listOf(event.assassinSeat, event.targetSeat).filter { it !in 1..playerCount }
                if (badSeats.isNotEmpty()) {
                    issues += issue(
                        code = IssueCode.SEAT_OUT_OF_RANGE,
                        severity = RuleIssue.Severity.BLOCKING,
                        seats = badSeats.sorted(),
                        eventId = event.eventId,
                        args = mapOf("seats" to badSeats.sorted().joinToString(","))
                    )
                }
                val targetRole = state.roleTruth[event.targetSeat] ?: RoleId.UNKNOWN
                if (event.hitMerlin == null && targetRole == RoleId.UNKNOWN) {
                    issues += issue(
                        code = IssueCode.UNKNOWN_ROLE_FOR_OUTCOME,
                        severity = RuleIssue.Severity.WARNING,
                        seats = listOf(event.targetSeat),
                        eventId = event.eventId,
                        args = mapOf("seat" to event.targetSeat.toString())
                    )
                }
            }

            is MatchAbandoned -> {
                if (state.isFinished) {
                    issues += issue(
                        code = IssueCode.EVENT_AFTER_MATCH_FINISHED,
                        severity = RuleIssue.Severity.INFO,
                        eventId = event.eventId,
                        args = mapOf("status" to state.status.name)
                    )
                }
            }

            is MatchFinalized -> {
                val outcome = state.outcome
                if (outcome != null) {
                    val winnerConflict = event.winner != null && event.winner != outcome.winner
                    val reasonConflict = event.winReason != outcome.reason &&
                        event.winReason != com.roundtrace.app.core.model.WinReason.UNKNOWN
                    if (winnerConflict || reasonConflict) {
                        issues += issue(
                            code = IssueCode.FINALIZED_CONFLICT,
                            severity = RuleIssue.Severity.WARNING,
                            eventId = event.eventId,
                            args = mapOf(
                                "recordedWinner" to (event.winner?.name ?: "null"),
                                "computedWinner" to outcome.winner.name,
                                "recordedReason" to event.winReason.name,
                                "computedReason" to outcome.reason.name
                            )
                        )
                    }
                }
            }
        }

        // 否决次数超限（在决议事件之前已到 5 次）
        if (state.consecutiveRejections > AvalonPreset.MAX_CONSECUTIVE_REJECTIONS) {
            issues += issue(
                code = IssueCode.REJECTION_STREAK_EXCEEDED,
                severity = RuleIssue.Severity.WARNING,
                questNo = questNo,
                eventId = event.eventId,
                args = mapOf("count" to state.consecutiveRejections.toString())
            )
        }

        return issues
    }

    /**
     * 表单草稿的队伍校验（TASKS.md T46 需要「允许只选部分成员后暂存」）。
     *
     * 与 [validateTeamSeats] 的区别：草稿**只产出非阻断提示**（WARNING），
     * 因此 `canSaveDraft` 恒为 true，而「确认提案」仍走 [validateEvent] 的 BLOCKING 判定（R13）。
     */
    fun validateTeamDraft(
        teamSeats: List<SeatId>,
        playerCount: Int,
        questNo: Int
    ): List<RuleIssue> = validateTeamSeats(
        teamSeats = teamSeats,
        expectedSize = expectedTeamSizeFor(playerCount, questNo),
        playerCount = playerCount,
        questNo = questNo,
        proposalNo = null,
        eventId = null
    ).map { it.copy(severity = RuleIssue.Severity.WARNING) }

    /**
     * 提案通过的可判定性（ARCHITECTURE §6.3）。
     *
     * 作为规则的一部分对外暴露，供 reducer 与单测共用同一实现（避免测试复制一份判定逻辑）。
     */
    fun decideProposal(
        approveCount: Int?,
        rejectCount: Int?,
        unknownVoteCount: Int?,
        playerCount: Int
    ): ProposalDecision = AvalonStateMachine.decideProposal(
        approveCount = approveCount,
        rejectCount = rejectCount,
        unknownVoteCount = unknownVoteCount,
        playerCount = playerCount
    )

    /** 该配置下第 [questNo] 个任务的期望队伍人数；人数/任务号非法时返回 -1（不抛异常）。 */
    fun expectedTeamSizeFor(playerCount: Int, questNo: Int): Int {
        if (playerCount !in AvalonPreset.MIN_PLAYERS..AvalonPreset.MAX_PLAYERS) return -1
        if (questNo !in 1..AvalonPreset.QUEST_COUNT) return -1
        return teamSize(playerCount, questNo)
    }

    // ——————————————————————————————————————————————————————————
    // 初始状态 / 推进 / 终局
    // ——————————————————————————————————————————————————————————

    override fun initialState(match: ReconstructedMatch): GameState = GameState(
        matchId = match.matchId,
        config = match.config,
        roleTruth = match.roleTruth(),
        status = MatchStatus.DRAFT,
        phase = RecordPhase.NOT_STARTED,
        currentQuestNo = 0,
        currentProposalNo = 0,
        currentLeaderSeat = match.config.initialLeaderSeat,
        quests = emptyList(),
        consecutiveRejections = 0,
        goodSuccessCount = 0,
        evilFailureCount = 0,
        assassination = null,
        outcome = null,
        integrity = IntegrityStatus.OK,
        issues = emptyList(),
        lastAppliedOrder = 0,
        eventCount = 0
    )

    override fun reduce(state: GameState, event: MatchEvent): GameState =
        AvalonStateMachine.reduce(this, state, event)

    /**
     * 终局推导。
     *
     * 硬约束（TASKS.md T18④）：`status == AWAITING_ASSASSINATION` 时返回 `null`——
     * 三次任务成功只是「进入刺杀阶段」，不能直接认定好人获胜（R09）。
     */
    override fun determineOutcome(state: GameState): GameOutcome? {
        if (state.status == MatchStatus.AWAITING_ASSASSINATION) return null
        if (state.status == MatchStatus.ABANDONED) return null
        return state.outcome
    }

    // ——————————————————————————————————————————————————————————
    // 内部工具
    // ——————————————————————————————————————————————————————————

    /** 按规则顺延下一任队长：`(current % playerCount) + 1`（ARCHITECTURE §9.3-13）。 */
    fun nextLeaderSeat(currentLeaderSeat: SeatId?, playerCount: Int): SeatId {
        if (playerCount <= 0) return 1
        val current = currentLeaderSeat ?: 0
        if (current !in 1..playerCount) return 1
        return (current % playerCount) + 1
    }

    private fun expectedTeamSize(state: GameState, questNo: Int): Int =
        expectedTeamSizeFor(state.config.playerCount, questNo)

    private fun expectedLeaderSeat(state: GameState): SeatId? {
        val config = state.config
        if (config.playerCount !in AvalonPreset.MIN_PLAYERS..AvalonPreset.MAX_PLAYERS) return null
        // 每个任务的首个提案由「上一任队长的下一位」担任；第 1 任务用配置的首任队长。
        if (state.currentQuestNo <= 0) return config.initialLeaderSeat
        return null
    }

    private fun validateTeamSeats(
        teamSeats: List<SeatId>,
        expectedSize: Int,
        playerCount: Int,
        questNo: Int,
        proposalNo: Int?,
        eventId: com.roundtrace.app.core.model.EventId?
    ): List<RuleIssue> {
        val issues = mutableListOf<RuleIssue>()

        val outOfRange = teamSeats.filter { it !in 1..playerCount }
        if (outOfRange.isNotEmpty()) {
            issues += issue(
                code = IssueCode.SEAT_OUT_OF_RANGE,
                severity = RuleIssue.Severity.BLOCKING,
                questNo = questNo,
                proposalNo = proposalNo,
                seats = outOfRange.sorted(),
                eventId = eventId,
                args = mapOf("seats" to outOfRange.sorted().joinToString(","), "max" to playerCount.toString())
            )
        }

        val duplicates = teamSeats.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        if (duplicates.isNotEmpty()) {
            issues += issue(
                code = IssueCode.DUPLICATE_SEAT,
                severity = RuleIssue.Severity.BLOCKING,
                questNo = questNo,
                proposalNo = proposalNo,
                seats = duplicates,
                eventId = eventId,
                args = mapOf("seats" to duplicates.joinToString(","))
            )
        }

        if (expectedSize > 0 && teamSeats.toSet().size != expectedSize) {
            issues += issue(
                code = IssueCode.TEAM_SIZE_MISMATCH,
                severity = RuleIssue.Severity.BLOCKING,
                questNo = questNo,
                proposalNo = proposalNo,
                seats = teamSeats,
                eventId = eventId,
                args = mapOf("expected" to expectedSize.toString(), "actual" to teamSeats.toSet().size.toString())
            )
        }

        return issues
    }

    private fun issue(
        code: IssueCode,
        severity: RuleIssue.Severity,
        questNo: Int? = null,
        proposalNo: Int? = null,
        seats: List<SeatId> = emptyList(),
        eventId: com.roundtrace.app.core.model.EventId? = null,
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
