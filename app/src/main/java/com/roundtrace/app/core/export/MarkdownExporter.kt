package com.roundtrace.app.core.export

import com.roundtrace.app.core.data.MatchBundle
import com.roundtrace.app.core.model.AssassinationRecorded
import com.roundtrace.app.core.model.BeliefType
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EvidenceFinding
import com.roundtrace.app.core.model.EvidenceLevel
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MatchFinalized
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.model.PrivateNoteAdded
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.QuestStarted
import com.roundtrace.app.core.model.TeamProposalResolved
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteChoice
import com.roundtrace.app.core.model.TeamVoteRecorded
import com.roundtrace.app.core.model.UnknownItem
import com.roundtrace.app.core.model.WinReason
import com.roundtrace.app.core.rules.GameRules
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Markdown 复盘摘要（TASKS.md T61 / TDD §8）。
 *
 * 结构固定为 8 节：基础信息 → 任务摘要 → 提案与投票 → 任务牌 → 关键发言 →
 * 结局 → 规则确定结论 → 遗留未知。
 *
 * 两条不可妥协的表达纪律：
 * 1. **「反对票」只出现在第 3 节，「失败牌」只出现在第 4 节**——
 *    两种票永远不会出现在同一行文字里（PRD 技术底线）；
 * 2. 「声称」类发言永远带「（声称，不等于事实）」后缀，避免离线报告被当成身份真相。
 *
 * 本类是**文本打印器**：它输出的是给人读的中文，因此中文常量留在这里，
 * 而不是让领域层返回中文（D6 约束的是领域模型，不是导出格式）。
 * 纯 Kotlin：无 Android 依赖，JVM 可测。
 */
class MarkdownExporter(private val rules: GameRules) {

    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * @param findings 已按视角裁剪的证据结论（为空则整节写「暂无可用结论」）
     * @param unknowns 信息不足项（为空则整节写「无」）
     * @param exportedAt 导出时刻，注入以便单测确定
     */
    fun export(
        bundle: MatchBundle,
        options: ExportOptions = ExportOptions.DEFAULT,
        findings: List<EvidenceFinding> = emptyList(),
        unknowns: List<UnknownItem> = emptyList(),
        exportedAt: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val filtered = VisibilityFilter.apply(bundle, options)
        val config = filtered.config
        val playerCount = filtered.summary.playerCount
        val events = filtered.events.sortedBy { it.occurredOrder }
        val stamp = timeFormatter.format(Instant.ofEpochMilli(exportedAt).atZone(zoneId))

        val sb = StringBuilder()
        sb.appendLine("# 局迹复盘 · ${filtered.summary.title}")
        sb.appendLine()
        sb.appendLine("> 导出时间：$stamp")
        sb.appendLine("> 规则：${config.rulesetId} v${config.rulesetVersion} · 人数：$playerCount")
        sb.appendLine(
            "> 脱敏：" + if (options.includeSecrets || options.includePrivateNotes) {
                "含私有信息（仅供本人留档）"
            } else {
                "已隐去未揭晓的真实身份与私人笔记"
            }
        )
        sb.appendLine()

        appendBasics(sb, filtered, events, stamp)
        appendQuestSummary(sb, events, playerCount)
        appendProposals(sb, events, playerCount)
        appendMissions(sb, events, playerCount)
        appendClaims(sb, events)
        appendOutcome(sb, filtered, events)
        appendFindings(sb, findings)

        // 脱敏后消失的私人笔记条数：让读者知道「报告不是全量」
        val hiddenNotes = bundle.events.count { it is PrivateNoteAdded } -
            events.count { it is PrivateNoteAdded }
        appendUnknowns(sb, unknowns, events, playerCount, hiddenNotes)

        return sb.toString()
    }

    // ————————————————————————————————————————————————————————
    // 1. 基础信息
    // ————————————————————————————————————————————————————————

    private fun appendBasics(
        sb: StringBuilder,
        bundle: MatchBundle,
        events: List<MatchEvent>,
        stamp: String
    ) {
        val config = bundle.config
        sb.appendLine("## 1. 基础信息")
        sb.appendLine()
        sb.appendLine("| 项 | 值 |")
        sb.appendLine("| --- | --- |")
        sb.appendLine("| 人数 | ${bundle.summary.playerCount} 人 |")
        sb.appendLine("| 我的座位 | ${config.mySeat?.let { "$it 号" } ?: "未填写"} |")
        sb.appendLine("| 首任队长 | ${config.initialLeaderSeat} 号 |")
        sb.appendLine("| 状态 | ${statusLabel(bundle.summary.status.name)} |")
        sb.appendLine("| 已开始任务 | ${events.count { it is QuestStarted }} 个 |")
        sb.appendLine("| 事件条数 | ${events.size} 条 |")
        sb.appendLine("| 身份揭晓 | ${bundle.revealedAt?.let { "已揭晓" } ?: "未揭晓"} |")
        sb.appendLine()

        val roles = bundle.roleAssignments
        if (roles.isNotEmpty()) {
            sb.appendLine("**角色（已揭晓部分）**")
            sb.appendLine()
            roles.entries.sortedBy { it.key }.forEach { (seat, role) ->
                sb.appendLine("- $seat 号：${role.roleId.name}")
            }
            sb.appendLine()
        } else {
            sb.appendLine("> 角色未揭晓或未填写，本报告不含真实身份。")
            sb.appendLine()
        }
        sb.appendLine("<sub>生成于 $stamp</sub>")
        sb.appendLine()
    }

    // ————————————————————————————————————————————————————————
    // 2. 任务摘要
    // ————————————————————————————————————————————————————————

    private fun appendQuestSummary(sb: StringBuilder, events: List<MatchEvent>, playerCount: Int) {
        sb.appendLine("## 2. 任务摘要")
        sb.appendLine()
        sb.appendLine("| 任务 | 应上人数 | 实际队伍 | 结果 |")
        sb.appendLine("| --- | ---: | --- | --- |")

        val started = events.filterIsInstance<QuestStarted>().map { it.questNo }.distinct().sorted()
        if (started.isEmpty()) {
            sb.appendLine("| — | — | — | 尚未开始任何任务 |")
            sb.appendLine()
            return
        }
        started.forEach { questNo ->
            val mission = events.filterIsInstance<MissionResolved>().lastOrNull { it.questNo == questNo }
            val team = mission?.teamSeats?.sorted()?.joinToString("/") ?: "未记录"
            val expected = runCatching { rules.teamSize(playerCount, questNo) }.getOrNull() ?: 0
            sb.appendLine("| 第 $questNo 任务 | $expected | $team | ${missionResultLabel(mission, playerCount)} |")
        }
        sb.appendLine()
    }

    private fun missionResultLabel(mission: MissionResolved?, playerCount: Int): String {
        if (mission == null) return "未记录"
        val failCount = mission.failCount ?: return "未判定（牌数未知）"
        val failed = rules.isMissionFailed(failCount, playerCount, mission.questNo)
        return if (failed) "失败" else "成功"
    }

    // ————————————————————————————————————————————————————————
    // 3. 组队提案与投票（「反对票」只在这里出现）
    // ————————————————————————————————————————————————————————

    private fun appendProposals(sb: StringBuilder, events: List<MatchEvent>, playerCount: Int) {
        sb.appendLine("## 3. 组队提案与投票")
        sb.appendLine()
        val proposals = events.filterIsInstance<TeamProposed>()
        if (proposals.isEmpty()) {
            sb.appendLine("暂无提案记录。")
            sb.appendLine()
            return
        }

        proposals.forEach { proposal ->
            val vote = events.filterIsInstance<TeamVoteRecorded>()
                .lastOrNull { it.questNo == proposal.questNo && it.proposalNo == proposal.proposalNo }
            val resolved = events.filterIsInstance<TeamProposalResolved>()
                .lastOrNull { it.questNo == proposal.questNo && it.proposalNo == proposal.proposalNo }

            sb.appendLine(
                "### 第 ${proposal.questNo} 任务 · 第 ${proposal.proposalNo} 次提案（队长 ${proposal.leaderSeat} 号）"
            )
            sb.appendLine()
            sb.appendLine(
                "- 队伍：" + proposal.teamSeats.sorted().joinToString("/").ifEmpty { "未记录" } +
                    "（应上 ${runCatching { rules.teamSize(playerCount, proposal.questNo) }.getOrDefault(0)} 人）"
            )
            sb.appendLine("- " + voteLine(vote))
            sb.appendLine("- 决议：" + decisionLabel(resolved))
            sb.appendLine()
        }
    }

    private fun voteLine(vote: TeamVoteRecorded?): String {
        if (vote == null) return "票数：—— 未记录"

        val approve = vote.approveCount
        val reject = vote.rejectCount
        val unknown = vote.unknownVoteCount

        if (approve == null && reject == null) {
            val perSeat = vote.votesBySeat
            if (perSeat.isNullOrEmpty()) return "票数：—— 未记录"
            val approveCount = perSeat.values.count { it == TeamVoteChoice.APPROVE }
            val rejectCount = perSeat.values.count { it == TeamVoteChoice.REJECT }
            val unknownCount = perSeat.values.count { it == TeamVoteChoice.UNKNOWN }
            return "赞成 $approveCount 票 / 反对 $rejectCount 票 / 未知 $unknownCount 票"
        }

        val approveText = approve?.toString() ?: "未知"
        val rejectText = reject?.toString() ?: "未知"
        val unknownText = unknown?.let { "$it 票" } ?: "未记录"
        return "赞成 $approveText 票 / 反对 $rejectText 票 / 未知 $unknownText"
    }

    private fun decisionLabel(resolved: TeamProposalResolved?): String = when (resolved?.decision) {
        ProposalDecision.APPROVED -> "通过" + if (resolved.verified) "" else "（未核实）"
        ProposalDecision.REJECTED -> "否决" + if (resolved.verified) "" else "（未核实）"
        ProposalDecision.UNKNOWN -> "未判定"
        null -> "—— 未记录"
    }

    // ————————————————————————————————————————————————————————
    // 4. 任务牌结果（「失败牌」只在这里出现）
    // ————————————————————————————————————————————————————————

    private fun appendMissions(sb: StringBuilder, events: List<MatchEvent>, playerCount: Int) {
        sb.appendLine("## 4. 任务牌结果")
        sb.appendLine()
        val missions = events.filterIsInstance<MissionResolved>()
        if (missions.isEmpty()) {
            sb.appendLine("暂无任务结果记录。")
            sb.appendLine()
            return
        }

        missions.sortedBy { it.questNo }.forEach { mission ->
            val threshold = runCatching {
                rules.failThreshold(playerCount, mission.questNo)
            }.getOrDefault(1)

            sb.appendLine("### 第 ${mission.questNo} 任务")
            sb.appendLine()
            sb.appendLine("- 上车玩家：" + (mission.teamSeats?.sorted()?.joinToString("/") ?: "未记录"))
            when {
                mission.failCount == null -> {
                    sb.appendLine("- 失败牌：—— 未记录（无法判定任务结果）")
                }

                else -> {
                    val failed = rules.isMissionFailed(mission.failCount, playerCount, mission.questNo)
                    sb.appendLine(
                        "- 失败牌：${mission.failCount} 张（本任务门槛 $threshold 张）" +
                            if (failed) " → 任务失败" else " → 任务成功"
                    )
                }
            }
            if (mission.failCount != null && !mission.verified) {
                sb.appendLine("- 备注：队伍成员不完整，结论仅按已记录信息判定")
            }
            sb.appendLine()
        }
    }

    // ————————————————————————————————————————————————————————
    // 5. 关键发言
    // ————————————————————————————————————————————————————————

    private fun appendClaims(sb: StringBuilder, events: List<MatchEvent>) {
        sb.appendLine("## 5. 关键发言")
        sb.appendLine()
        val claims = events.filterIsInstance<PlayerClaimed>()
        val notes = events.filterIsInstance<PrivateNoteAdded>()

        if (claims.isEmpty() && notes.isEmpty()) {
            sb.appendLine("暂无发言记录（不影响复盘）。")
            sb.appendLine()
            return
        }
        claims.forEach { claim ->
            val place = claim.questNo?.let { "第 $it 任务" } ?: "开局"
            val target = if (claim.targetSeats.isEmpty()) {
                ""
            } else {
                " → " + claim.targetSeats.sorted().joinToString("/") + " 号"
            }
            val text = claim.text?.takeIf { it.isNotBlank() }?.let { "：「$it」" } ?: ""
            sb.appendLine("- [$place] ${claim.speakerSeat} 号：${claimLabel(claim)}$target$text")
        }

        if (notes.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("**私人笔记（仅本人可见，非公开事实）**")
            sb.appendLine()
            notes.forEach { note ->
                val place = note.questNo?.let { "第 $it 任务" } ?: "开局"
                val target = if (note.targetSeats.isEmpty()) {
                    ""
                } else {
                    " → " + note.targetSeats.sorted().joinToString("/") + " 号"
                }
                val text = note.text?.takeIf { it.isNotBlank() }?.let { "：「$it」" } ?: ""
                sb.appendLine("- [$place] ${note.observerSeat} 号：${beliefLabel(note)}$target$text")
            }
        }
        sb.appendLine()
    }

    private fun beliefLabel(note: PrivateNoteAdded): String = when (note.beliefType) {
        BeliefType.SUSPECT -> "怀疑"
        BeliefType.TRUST -> "信任"
        BeliefType.ROLE_GUESS -> "猜测身份"
        BeliefType.OTHER -> "个人备注"
    }

    private fun claimLabel(claim: PlayerClaimed): String = when (claim.claimType) {
        ClaimType.ROLE_CLAIM ->
            "声称自己是 ${claim.claimedRole?.name ?: "某身份"}（声称，不等于事实）"

        ClaimType.COUNTER_CLAIM ->
            "对跳 ${claim.claimedRole?.name ?: "某身份"}（声称，不等于事实）"

        ClaimType.SUSPECT -> "怀疑"
        ClaimType.PROTECT -> "保护/信任"
        ClaimType.TEAM_RECOMMEND -> "推荐组队"
        ClaimType.STANCE_CHANGE -> "改站边"
        ClaimType.KEY_STATEMENT -> "关键言论"
        ClaimType.OTHER -> "其他"
    }

    // ————————————————————————————————————————————————————————
    // 6. 结局
    // ————————————————————————————————————————————————————————

    private fun appendOutcome(sb: StringBuilder, bundle: MatchBundle, events: List<MatchEvent>) {
        sb.appendLine("## 6. 结局")
        sb.appendLine()
        val finalized = events.filterIsInstance<MatchFinalized>().lastOrNull()
        val assassination = events.filterIsInstance<AssassinationRecorded>().lastOrNull()

        sb.appendLine("- 对局状态：${statusLabel(bundle.summary.status.name)}")
        if (assassination != null) {
            val hit = when (assassination.hitMerlin) {
                true -> "命中梅林 → 坏人胜"
                false -> "未命中梅林 → 好人胜"
                null -> "结果未记录"
            }
            sb.appendLine(
                "- 刺杀：${assassination.assassinSeat} 号刺杀 ${assassination.targetSeat} 号，$hit"
            )
        }
        if (finalized != null) {
            sb.appendLine("- 记录胜方：${winnerLabel(finalized)}")
            sb.appendLine("- 获胜原因：${winReasonLabel(finalized.winReason)}")
            finalized.note?.takeIf { it.isNotBlank() }?.let { sb.appendLine("- 备注：$it") }
        }
        if (assassination == null && finalized == null) {
            sb.appendLine("- 结局尚未记录，本报告只包含过程事实。")
        }
        sb.appendLine()
    }

    private fun winnerLabel(finalized: MatchFinalized): String = when (finalized.winner?.name) {
        "GOOD" -> "好人阵营"
        "EVIL" -> "坏人阵营"
        else -> "未记录"
    }

    private fun winReasonLabel(reason: WinReason): String = when (reason) {
        WinReason.THREE_MISSIONS_FAILED -> "三次任务失败"
        WinReason.FIVE_CONSECUTIVE_REJECTS -> "同一任务连续五次否决"
        WinReason.ASSASSIN_HIT_MERLIN -> "刺客命中梅林"
        WinReason.ASSASSIN_MISSED -> "刺客未命中梅林"
        WinReason.NO_ASSASSIN_PRESENT -> "本局无刺客，好人达成三次任务成功"
        WinReason.ABANDONED -> "弃局"
        WinReason.UNKNOWN -> "未记录"
    }

    // ————————————————————————————————————————————————————————
    // 7. 规则确定结论
    // ————————————————————————————————————————————————————————

    private fun appendFindings(sb: StringBuilder, findings: List<EvidenceFinding>) {
        sb.appendLine("## 7. 规则确定结论")
        sb.appendLine()
        if (findings.isEmpty()) {
            sb.appendLine("暂无可用结论（可能是信息不足，或所选视角看不到）。")
            sb.appendLine()
            return
        }
        findings.forEach { finding ->
            val badge = levelBadge(finding.level)
            val evidence = if (finding.sourceEventIds.isEmpty()) {
                "无直接证据"
            } else {
                "证据事件 ${finding.sourceEventIds.size} 条"
            }
            sb.appendLine("- **[$badge] ${finding.label}**")
            sb.appendLine("  - 依据：${finding.ruleId} · $evidence")
            sb.appendLine("  - 说明：${finding.explanation}")
        }
        sb.appendLine()
        sb.appendLine("> 结论只来自确定性规则；「推测」不代表事实，且不输出数值概率。")
        sb.appendLine()
    }

    private fun levelBadge(level: EvidenceLevel): String = when (level) {
        EvidenceLevel.RULE_CERTAIN -> "规则确定"
        EvidenceLevel.OBSERVER_CERTAIN -> "本人可知"
        EvidenceLevel.INFERENCE -> "推测"
        EvidenceLevel.UNKNOWN -> "信息不足"
    }

    // ————————————————————————————————————————————————————————
    // 8. 遗留未知
    // ————————————————————————————————————————————————————————

    private fun appendUnknowns(
        sb: StringBuilder,
        unknowns: List<UnknownItem>,
        events: List<MatchEvent>,
        playerCount: Int,
        hiddenNotes: Int
    ) {
        sb.appendLine("## 8. 遗留未知事项")
        sb.appendLine()
        val items = mutableListOf<String>()

        unknowns.forEach { unknown ->
            val where = unknown.questNo?.let { "第 $it 任务" } ?: "全局"
            val seats = if (unknown.seats.isEmpty()) "" else "（座位 ${unknown.seats.sorted().joinToString("/")}）"
            items += "$where：${unknown.code}$seats"
        }

        events.filterIsInstance<MissionResolved>().forEach { mission ->
            if (mission.failCount == null) {
                items += "第 ${mission.questNo} 任务：牌数未记录，无法判定成败"
            }
            val expected = runCatching { rules.teamSize(playerCount, mission.questNo) }.getOrNull()
            if (expected != null && (mission.teamSeats?.size ?: 0) < expected) {
                items += "第 ${mission.questNo} 任务：上车玩家不完整（应 $expected 人）"
            }
        }

        if (items.isEmpty()) {
            sb.appendLine("无。所有已记录信息完整。")
        } else {
            items.forEach { sb.appendLine("- $it") }
        }
        if (hiddenNotes > 0) {
            sb.appendLine("- 另有 $hiddenNotes 条私人笔记已按脱敏策略隐去（本人留档请选择「包含私有信息」）")
        }
        sb.appendLine()
    }

    // ————————————————————————————————————————————————————————
    // 公共工具
    // ————————————————————————————————————————————————————————

    private fun statusLabel(status: String): String = when (status) {
        "DRAFT" -> "草稿"
        "IN_PROGRESS" -> "进行中"
        "AWAITING_ASSASSINATION" -> "等待刺杀"
        "FINISHED_GOOD" -> "已结束 · 好人胜"
        "FINISHED_EVIL" -> "已结束 · 坏人胜"
        "ABANDONED" -> "弃局"
        else -> status
    }
}
