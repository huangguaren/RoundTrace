package com.roundtrace.app.core.knowledge

import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.EvidenceFinding
import com.roundtrace.app.core.model.EvidenceLevel
import com.roundtrace.app.core.model.GamePosition
import com.roundtrace.app.core.model.KnowledgeSnapshot
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.model.PrivateRelation
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.ReconstructedMatch
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.SeatId
import com.roundtrace.app.core.model.TeamProposalResolved
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteRecorded
import com.roundtrace.app.core.rules.avalon.AvalonRules

/**
 * 阿瓦隆证据分析器（ARCHITECTURE §7.5 / TASKS.md T52②）。
 *
 * ## 输出纪律（PRD FR-08，主理人「输出纪律」硬要求）
 *
 * 1. 每条结论都带 `ruleId` 与 `sourceEventIds`；
 * 2. `sourceEventIds` 为空时**只能**是 `INFERENCE`，且 explanation 必须说明「无直接证据」；
 * 3. **绝不输出数值概率**（不出现「89% 是狼」这种假精确度）；
 * 4. 任务成功**不能**推出队员全好（`A04` 必出现）；
 * 5. 失败牌数**不能**定位到具体座位（`A07` 必出现），只能给上下界（`A05`/`A06`）。
 *
 * 12 条 ruleId 与 §7.5 表格逐条对应。
 */
class AvalonEvidenceAnalyzer(
    private val rules: AvalonRules = AvalonRules()
) : ReplayAnalyzer {

    override fun analyze(
        snapshot: KnowledgeSnapshot,
        history: List<MatchEvent>
    ): List<EvidenceFinding> {
        val findings = mutableListOf<EvidenceFinding>()
        val knownGood = snapshot.seatKnowledge
            .filterValues { it.knownAlignment == Alignment.GOOD }
            .keys
        val knownEvil = snapshot.seatKnowledge
            .filterValues { it.knownAlignment == Alignment.EVIL }
            .keys
        val level = observationLevel(snapshot)

        // —— A11：夜间确定的坏人（观察者专属）——
        snapshot.privateRoleFacts
            .filter { it.relation == PrivateRelation.KNOWN_EVIL }
            .groupBy { it.targetSeat }
            .forEach { (seat, facts) ->
                if (snapshot.mode != Perspective.OBSERVER_AT_TIME) return@forEach
                findings += EvidenceFinding(
                    id = "A11-$seat",
                    label = "$seat 号是你开局就知道的坏人",
                    level = EvidenceLevel.OBSERVER_CERTAIN,
                    explanation = "来自开局夜间规则（梅林视野），不含莫德雷德与奥伯伦；" +
                        "这是规则确定性信息，不是推测。",
                    ruleId = RULE_A11,
                    sourceEventIds = facts.flatMap { it.sourceEventIds },
                    relatedSeats = listOf(seat)
                )
            }

        // —— 逐任务分析 ——
        val proposals = history.filterIsInstance<TeamProposed>().groupBy { it.questNo }
        proposals.forEach { (questNo, list) ->
            list.forEach { proposal ->
                // A01：队伍人数应为 N 人
                val expected = rules.expectedTeamSizeFor(snapshot.playerCount, questNo)
                if (expected > 0) {
                    findings += EvidenceFinding(
                        id = "A01-$questNo-${proposal.proposalNo}",
                        label = "第 $questNo 任务队伍应为 $expected 人",
                        level = EvidenceLevel.RULE_CERTAIN,
                        explanation = "${snapshot.playerCount} 人局第 $questNo 任务的队伍人数由规则矩阵决定，" +
                            "与玩家强弱无关。",
                        ruleId = RULE_A01,
                        sourceEventIds = listOf(proposal.eventId),
                        relatedQuestNo = questNo
                    )
                }

                // A05：该队伍至少含 k 名坏人（用 bounds，绝不点名）
                val bounds = bounds(
                    teamSeats = proposal.teamSeats,
                    goodCount = snapshot.goodCount,
                    evilCount = snapshot.evilCount,
                    knownGood = knownGood,
                    knownEvil = knownEvil
                )
                if (bounds.atLeastOneEvil) {
                    findings += EvidenceFinding(
                        id = "A05-$questNo-${proposal.proposalNo}",
                        label = "该队伍至少含 ${bounds.minEvil} 名坏人",
                        level = level,
                        explanation = "按你确定的好人/坏人座位与规则矩阵推导：" +
                            "队伍 ${proposal.teamSeats.size} 人，最多只有 " +
                            "${proposal.teamSeats.size - bounds.minEvil} 人可以确定是好人，" +
                            "因此至少 ${bounds.minEvil} 名是坏人。" +
                            "**但无法据此确定具体是哪一位**（可能是队伍中任一身份未定者）。",
                        ruleId = RULE_A05,
                        sourceEventIds = listOf(proposal.eventId),
                        relatedSeats = proposal.teamSeats,
                        relatedQuestNo = questNo
                    )
                }
            }
        }

        // —— 投票与决议 ——
        history.filterIsInstance<TeamVoteRecorded>().forEach { vote ->
            val computed = rules.decideProposal(
                approveCount = vote.approveCount,
                rejectCount = vote.rejectCount,
                unknownVoteCount = vote.unknownVoteCount,
                playerCount = vote.playerCountAtVote
            )
            if (computed == ProposalDecision.REJECTED) {
                val approve = vote.approveCount ?: return@forEach
                findings += EvidenceFinding(
                    id = "A02-${vote.questNo}-${vote.proposalNo}",
                    label = "赞成 $approve 票，未超过半数，组队被否决",
                    level = EvidenceLevel.RULE_CERTAIN,
                    explanation = "${vote.playerCountAtVote} 人局半数票为 " +
                        "${vote.playerCountAtVote / 2}，赞成 $approve 票" +
                        (if (approve == vote.playerCountAtVote / 2) "（平票视为否决）" else "，不足半数") + "。",
                    ruleId = RULE_A02,
                    sourceEventIds = listOf(vote.eventId),
                    relatedQuestNo = vote.questNo
                )
            }
        }

        history.filterIsInstance<TeamProposalResolved>().forEach { resolved ->
            if (resolved.decision == ProposalDecision.REJECTED) {
                findings += EvidenceFinding(
                    id = "A10-${resolved.questNo}-${resolved.proposalNo}",
                    label = "第 ${resolved.questNo} 任务第 ${resolved.proposalNo} 次提案被否决",
                    level = EvidenceLevel.RULE_CERTAIN,
                    explanation = "同一任务连续 5 次否决将直接判坏人胜利（当前第 " +
                        "${resolved.proposalNo} 次）。",
                    ruleId = RULE_A10,
                    sourceEventIds = listOf(resolved.eventId),
                    relatedQuestNo = resolved.questNo
                )
            }
        }

        // —— 任务结果 ——
        history.filterIsInstance<MissionResolved>().forEach { mission ->
            val questNo = mission.questNo
            val failCount = mission.failCount
            val threshold = rules.failThreshold(snapshot.playerCount, questNo)

            // A03：任务门槛
            if (failCount != null && snapshot.playerCount > 0) {
                findings += EvidenceFinding(
                    id = "A03-$questNo",
                    label = "第 $questNo 任务需 ≥$threshold 张失败牌",
                    level = EvidenceLevel.RULE_CERTAIN,
                    explanation = "7–10 人局的第 4 个任务门槛为 2，其余任务门槛为 1" +
                        "（当前 ${snapshot.playerCount} 人局第 $questNo 任务门槛 = $threshold）。",
                    ruleId = RULE_A03,
                    sourceEventIds = listOf(mission.eventId),
                    relatedQuestNo = questNo
                )
            }

            // A06：f 张失败牌 → 队伍内至少 f 名坏人
            if (failCount != null && failCount > 0) {
                findings += EvidenceFinding(
                    id = "A06-$questNo",
                    label = "该队伍内至少有 $failCount 名坏人",
                    level = EvidenceLevel.RULE_CERTAIN,
                    explanation = "只有坏人能出失败牌（好人只能出成功牌），" +
                        "出现 $failCount 张失败牌说明至少有 $failCount 名坏人在这支队伍里。",
                    ruleId = RULE_A06,
                    sourceEventIds = listOf(mission.eventId),
                    relatedSeats = mission.teamSeats ?: emptyList(),
                    relatedQuestNo = questNo
                )
            }

            // A07：无法定位具体是谁出的失败牌（纠错类，必出现）
            if (failCount != null && failCount > 0) {
                val team = mission.teamSeats
                findings += EvidenceFinding(
                    id = "A07-$questNo",
                    label = "无法确定具体是谁出的失败牌",
                    level = EvidenceLevel.UNKNOWN,
                    explanation = "本 App 只记录公开的失败牌总数，不记录每个人出了什么牌。" +
                        "失败牌可能来自队伍中任一" +
                        (team?.let { "${it.size} 人中的任何一位" } ?: "身份未定者") +
                        "，不能锁定某一位。" +
                        (if (team == null) "（且队伍成员未记录完整，信息不足）" else ""),
                    ruleId = RULE_A07,
                    sourceEventIds = listOf(mission.eventId),
                    relatedSeats = team ?: emptyList(),
                    relatedQuestNo = questNo
                )
            }

            // A04：任务成功不能推出队员全好（纠错类，必出现）
            if (mission.observedOutcome == MissionOutcome.SUCCESS ||
                (failCount != null && failCount == 0)
            ) {
                findings += EvidenceFinding(
                    id = "A04-$questNo",
                    label = "任务成功不等于队员都是好人",
                    level = EvidenceLevel.RULE_CERTAIN,
                    explanation = "坏人可以选择出成功牌来伪装。" +
                        "第 $questNo 任务成功只能说明失败牌为 0，不能把队伍成员标记为已证实好人。",
                    ruleId = RULE_A04,
                    sourceEventIds = listOf(mission.eventId),
                    relatedSeats = mission.teamSeats ?: emptyList(),
                    relatedQuestNo = questNo
                )
            }

            // A12：数据残缺
            if (failCount == null) {
                findings += EvidenceFinding(
                    id = "A12-fail-$questNo",
                    label = "第 $questNo 任务的失败牌数未记录",
                    level = EvidenceLevel.UNKNOWN,
                    explanation = "缺少失败牌数，无法判断该任务成败，也无法推导队伍内坏人的下界。" +
                        "结论暂不可用（不是「任务成功」，也不是「任务失败」）。",
                    ruleId = RULE_A12,
                    sourceEventIds = listOf(mission.eventId),
                    relatedQuestNo = questNo
                )
            }
            if (mission.teamSeats == null) {
                findings += EvidenceFinding(
                    id = "A12-team-$questNo",
                    label = "第 $questNo 任务的队伍成员未记录完整",
                    level = EvidenceLevel.UNKNOWN,
                    explanation = "队员不完整时，任何「谁在这支队伍里」的推论都不可靠；" +
                        "本任务不参与好人/坏人计数。",
                    ruleId = RULE_A12,
                    sourceEventIds = listOf(mission.eventId),
                    relatedQuestNo = questNo
                )
            }
        }

        // —— 声称：Claim 永远不是事实（A08）——
        history.filterIsInstance<PlayerClaimed>().forEach { claim ->
            if (claim.claimedRole == null) return@forEach
            findings += EvidenceFinding(
                id = "A08-${claim.eventId.raw}",
                label = "${claim.speakerSeat} 号「${roleLabel(claim.claimedRole)}」只是声称",
                level = EvidenceLevel.RULE_CERTAIN,
                explanation = "这是一个 Claim，不是事实。声称可以被对跳（COUNTER_CLAIM），" +
                    "也可能来自莫甘娜（伪装成梅林出现在派西维尔的候选里）。" +
                    "只有结局揭晓的真实角色才算身份证据。",
                ruleId = RULE_A08,
                sourceEventIds = listOf(claim.eventId),
                relatedSeats = listOf(claim.speakerSeat),
                relatedQuestNo = claim.questNo
            )
        }

        // —— 队长顺延（A09）——
        // 规则期望值只在第 1 个任务有独立事实源（配置的首任队长），其余任务不臆造
        history.filterIsInstance<TeamProposed>().forEach { proposal ->
            if (proposal.leaderSeat !in 1..(snapshot.playerCount.coerceAtLeast(1))) {
                findings += EvidenceFinding(
                    id = "A09-${proposal.eventId.raw}",
                    label = "队长座位 ${proposal.leaderSeat} 号超出人数范围",
                    level = EvidenceLevel.INFERENCE,
                    explanation = "记录中的队长座位不在 1..${snapshot.playerCount} 内，" +
                        "可能是录入错误；无直接证据表明顺延规则被违反，需人工核对。",
                    ruleId = RULE_A09,
                    sourceEventIds = listOf(proposal.eventId),
                    relatedSeats = listOf(proposal.leaderSeat),
                    relatedQuestNo = proposal.questNo
                )
            }
        }

        return findings
    }

    override fun analyzeAt(
        match: ReconstructedMatch,
        observer: SeatId?,
        at: GamePosition,
        mode: Perspective,
        knowledgeProvider: KnowledgeProvider
    ): List<EvidenceFinding> {
        val snapshot = knowledgeProvider.snapshot(match, observer, at, mode)
        // 与快照同一时间边界内的公开事件——**不要**用 match.events（会击穿时间闸门）
        val history = match.events
            .asSequence()
            .filter { !it.retracted }
            .filter { at.includes(it.occurredOrder) }
            .sortedBy { it.occurredOrder }
            .toList()
        return analyze(snapshot, history)
    }

    /**
     * 结论的确定性等级。
     *
     * 上帝视角 = 规则确定（有真相）；观察者视角且角色已知 = 本人可知；
     * 其余（公开视角 / 观察者角色未知）= 推测。
     */
    private fun observationLevel(snapshot: KnowledgeSnapshot): EvidenceLevel =
        when {
            snapshot.mode == Perspective.GOD_AFTER_REVEAL -> EvidenceLevel.RULE_CERTAIN
            snapshot.mode == Perspective.OBSERVER_AT_TIME &&
                snapshot.observerRole != null &&
                snapshot.observerRole != RoleId.UNKNOWN -> EvidenceLevel.OBSERVER_CERTAIN

            else -> EvidenceLevel.INFERENCE
        }

    private fun roleLabel(role: RoleId?): String = when (role) {
        RoleId.MERLIN -> "梅林"
        RoleId.PERCIVAL -> "派西维尔"
        RoleId.MORGANA -> "莫甘娜"
        RoleId.ASSASSIN -> "刺客"
        RoleId.MORDRED -> "莫德雷德"
        RoleId.OBERON -> "奥伯伦"
        RoleId.MINION -> "爪牙"
        RoleId.LOYAL_SERVANT -> "忠臣"
        else -> "未知"
    }

    companion object {
        const val RULE_A01: String = "A01_TEAM_SIZE"
        const val RULE_A02: String = "A02_VOTE_NOT_APPROVED"
        const val RULE_A03: String = "A03_MISSION_FAILED_THRESHOLD"
        const val RULE_A04: String = "A04_SUCCESS_NOT_CLEAR"
        const val RULE_A05: String = "A05_TEAM_CONTAINS_EVIL"
        const val RULE_A06: String = "A06_FAIL_COUNT_IMPLIES_EVIL_IN_TEAM"
        const val RULE_A07: String = "A07_CANNOT_PINPOINT_FAIL_VOTER"
        const val RULE_A08: String = "A08_CLAIM_IS_NOT_FACT"
        const val RULE_A09: String = "A09_LEADER_SEQUENCE"
        const val RULE_A10: String = "A10_REJECTION_STREAK"
        const val RULE_A11: String = "A11_KNOWN_EVIL_FROM_NIGHT"
        const val RULE_A12: String = "A12_DATA_INCOMPLETE"

        /** 全部 ruleId，供测试断言「12 条全实现」。 */
        val ALL_RULE_IDS: List<String> = listOf(
            RULE_A01, RULE_A02, RULE_A03, RULE_A04, RULE_A05, RULE_A06,
            RULE_A07, RULE_A08, RULE_A09, RULE_A10, RULE_A11, RULE_A12
        )
    }
}
