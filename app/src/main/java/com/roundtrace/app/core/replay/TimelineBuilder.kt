package com.roundtrace.app.core.replay

import com.roundtrace.app.core.model.AssassinationRecorded
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.MatchAbandoned
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MatchFinalized
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.model.PrivateNoteAdded
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.QuestStarted
import com.roundtrace.app.core.model.SeatId
import com.roundtrace.app.core.model.TeamProposalResolved
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteChoice
import com.roundtrace.app.core.model.TeamVoteRecorded

/**
 * 事件 → 时间线条目构建器（TASKS.md T22 / ARCHITECTURE §8.6）。
 *
 * 约定：
 * 1. **每个事件生成恰好 1 条** [TimelineEntry]，按 `occurredOrder` 升序；
 *    同一任务的多次提案会得到多条 entry（带正确的「第 N 任务 · 第 M 次提案」），不做合并，
 *    因此不会丢失信息（T22④）。
 * 2. 未记录字段（`null`）进入 [TimelineEntry.unrecordedFields]，由 UI 渲染为「—— 未记录」，
 *    绝不以 0 或空白代替（T22③，`null` 与 `0` 语义分离）。
 * 3. 本类只做展示层拼装（架构文档将其定义为「时间线 UI 中间模型」），
 *    数值计算与胜负判定一律不在此处。
 */
object TimelineBuilder {

    /** 未记录字段的统一占位文案（UI 用 `stringResource(R.string.unrecorded_placeholder)` 亦可）。 */
    const val UNRECORDED_LABEL: String = "—— 未记录"

    /**
     * 时间线里自由文本（`PlayerClaimed.text`）的展示上限。
     *
     * 超长就截断加省略号：时间线是列表，整段话会把卡片撑爆；完整文本另有详情入口。
     */
    const val MAX_CLAIM_TEXT_LENGTH: Int = 60

    /**
     * 构建时间线。
     *
     * @param events 事件列表（可含 `retracted`，本方法会过滤）
     * @param upTo 逻辑位置上界（`logicalPosition`）。领域事件的 `occurredOrder` 已完成该映射
     *        （见 `EventCodec.decode`，RT-003 Step 3），因此这里仍以 `occurredOrder` 命名。
     *        修订条目会回到它在时间线上的原位置，而不是排在末尾。null = 全部。
     */
    fun build(
        events: List<MatchEvent>,
        upTo: Int? = null,
        /**
         * 可选的**结构化座位格式化回调**（UX-ID-02）。
         * `null` = 逐字沿用旧文案。非 null 时只在 `leaderSeat` / `teamSeats` /
         * `speakerSeat` / `targetSeats` / `observerSeat` / 刺杀座位的展示位置调用。
         * 任务序号、票数、失败牌数、以及 `PlayerClaimed.text` 自由文本**不经过**此回调，
         * 因此「2号很可疑」这类原话永远不会被改写（ID-07）。
         */
        seatLabel: ((SeatId) -> String)? = null
    ): List<TimelineEntry> =
        events.asSequence()
            .filter { !it.retracted }
            .filter { upTo == null || it.occurredOrder <= upTo }
            .sortedWith(compareBy({ it.occurredOrder }, { it.recordedAt }, { it.eventId.raw }))
            .map { toEntry(it, seatLabel) }
            .toList()

    /** 单个事件 → 单个条目。 */
    fun toEntry(
        event: MatchEvent,
        seatLabel: ((SeatId) -> String)? = null
    ): TimelineEntry = when (event) {
        is QuestStarted -> TimelineEntry(
            occurredOrder = event.occurredOrder,
            phaseLabel = questPhaseLabel(event.questNo),
            title = "第 ${event.questNo} 任务开始，队长 ${seatPhrase(event.leaderSeat, seatLabel)}",
            detail = "任务编号 ${event.questNo}",
            sourceEventIds = listOf(event.eventId),
            eventType = event.type
        )

        is TeamProposed -> TimelineEntry(
            occurredOrder = event.occurredOrder,
            phaseLabel = questPhaseLabel(event.questNo),
            title = "队长 ${seatPhrase(event.leaderSeat, seatLabel)}提出队伍：" +
                seatListPhrase(event.teamSeats, seatLabel),
            detail = proposalContext(event.questNo, event.proposalNo),
            sourceEventIds = listOf(event.eventId),
            eventType = event.type
        )

        is TeamVoteRecorded -> {
            val unrecorded = buildList {
                if (event.approveCount == null && event.votesBySeat == null) add("赞成票数")
                if (event.rejectCount == null && event.votesBySeat == null) add("反对票数")
            }
            TimelineEntry(
                occurredOrder = event.occurredOrder,
                phaseLabel = questPhaseLabel(event.questNo),
                title = voteSummary(event),
                detail = proposalContext(event.questNo, event.proposalNo) +
                    " · 人数 ${event.playerCountAtVote}",
                unrecordedFields = unrecorded,
                sourceEventIds = listOf(event.eventId),
                eventType = event.type
            )
        }

        is TeamProposalResolved -> {
            val decisionLabel = when (event.decision) {
                ProposalDecision.APPROVED -> "通过"
                ProposalDecision.REJECTED -> "否决"
                ProposalDecision.UNKNOWN -> "待定"
            }
            val unrecorded = buildList {
                if (event.approveCount == null) add("赞成票数")
                if (event.rejectCount == null) add("反对票数")
                if (event.decision == ProposalDecision.UNKNOWN) add("组队决议")
            }
            TimelineEntry(
                occurredOrder = event.occurredOrder,
                phaseLabel = questPhaseLabel(event.questNo),
                title = "组队决议：$decisionLabel" + if (event.verified) "" else "（未核实）",
                detail = proposalContext(event.questNo, event.proposalNo) +
                    " · 赞成 ${event.approveCount ?: UNRECORDED_LABEL}" +
                    " / 反对 ${event.rejectCount ?: UNRECORDED_LABEL}",
                unrecordedFields = unrecorded,
                sourceEventIds = listOf(event.eventId),
                eventType = event.type
            )
        }

        is MissionResolved -> {
            val outcomeLabel = when (event.observedOutcome) {
                MissionOutcome.SUCCESS -> "成功"
                MissionOutcome.FAILED -> "失败"
                MissionOutcome.UNKNOWN, null -> "结果未知"
            }
            val unrecorded = buildList {
                if (event.teamSeats == null) add("任务队伍")
                if (event.failCount == null) add("失败牌数")
                if (event.observedOutcome == null) add("任务成败")
            }
            TimelineEntry(
                occurredOrder = event.occurredOrder,
                phaseLabel = questPhaseLabel(event.questNo),
                title = "任务牌结果：$outcomeLabel" +
                    if (event.verified) "" else "（未核实）",
                detail = questPhaseLabel(event.questNo) +
                    " · 队伍 " + (event.teamSeats?.let { seatListPhrase(it, seatLabel) }
                        ?: UNRECORDED_LABEL) +
                    " · 失败牌 ${event.failCount ?: UNRECORDED_LABEL}",
                unrecordedFields = unrecorded,
                sourceEventIds = listOf(event.eventId),
                eventType = event.type
            )
        }

        is PlayerClaimed -> TimelineEntry(
            occurredOrder = event.occurredOrder,
            phaseLabel = questPhaseLabel(event.questNo),
            title = claimTitle(event, seatLabel),
            detail = claimDetail(event, seatLabel),
            sourceEventIds = listOf(event.eventId),
            eventType = event.type
        )

        is PrivateNoteAdded -> TimelineEntry(
            occurredOrder = event.occurredOrder,
            phaseLabel = questPhaseLabel(event.questNo),
            title = "${seatPhrase(event.observerSeat, seatLabel)}的私人笔记：${beliefTypeLabel(event.beliefType.name)}",
            detail = "自第 ${event.availableFromOrder} 个事件起可见" +
                if (event.visibility == EventVisibility.PRIVATE_OBSERVER) " · 仅本人可见" else "",
            unrecordedFields = if (event.targetSeats.isEmpty()) listOf("判断对象") else emptyList(),
            sourceEventIds = listOf(event.eventId),
            eventType = event.type
        )

        is AssassinationRecorded -> {
            val hitLabel = when (event.hitMerlin) {
                true -> "命中梅林"
                false -> "未命中梅林"
                null -> "结果未知（角色未揭晓）"
            }
            TimelineEntry(
                occurredOrder = event.occurredOrder,
                phaseLabel = "刺杀阶段",
                title = "${seatPhrase(event.assassinSeat, seatLabel)}刺杀 ${seatPhrase(event.targetSeat, seatLabel)}：$hitLabel",
                detail = "刺杀结算",
                unrecordedFields = if (event.hitMerlin == null) listOf("是否命中梅林") else emptyList(),
                sourceEventIds = listOf(event.eventId),
                eventType = event.type
            )
        }

        is MatchAbandoned -> TimelineEntry(
            occurredOrder = event.occurredOrder,
            phaseLabel = "终局",
            title = "对局已弃局",
            detail = event.reason ?: "未填写原因",
            unrecordedFields = if (event.reason == null) listOf("弃局原因") else emptyList(),
            sourceEventIds = listOf(event.eventId),
            eventType = event.type
        )

        is MatchFinalized -> TimelineEntry(
            occurredOrder = event.occurredOrder,
            phaseLabel = "终局",
            title = "终局核对：${winReasonLabel(event.winReason.name)}",
            detail = event.winner?.let { "填写胜方 $it" } ?: "胜方未填写",
            unrecordedFields = if (event.winner == null) listOf("胜方") else emptyList(),
            sourceEventIds = listOf(event.eventId),
            eventType = event.type
        )
    }

    /** 单个座位引用。默认「2 号」，有结构化映射时「阿华（2号）」。 */
    private fun seatPhrase(seat: SeatId, seatLabel: ((SeatId) -> String)?): String =
        seatLabel?.invoke(seat) ?: "$seat 号"

    /** 座位列表。默认逐人「2 号 / 3 号」，有映射时「阿华（2号） / 小林（3号）」。 */
    private fun seatListPhrase(seats: List<SeatId>, seatLabel: ((SeatId) -> String)?): String =
        if (seatLabel == null) {
            seats.joinToString(" / ") { "$it 号" }
        } else {
            seats.joinToString(" / ") { seatLabel(it) }
        }

    /** 阶段标签。 */
    private fun questPhaseLabel(questNo: Int?): String =
        if (questNo == null) "准备阶段" else "第 $questNo 任务"

    /** 「第 N 任务 · 第 M 次提案」。 */
    private fun proposalContext(questNo: Int?, proposalNo: Int?): String = when {
        questNo == null && proposalNo == null -> "准备阶段"
        questNo == null -> "第 $proposalNo 次提案"
        proposalNo == null -> questPhaseLabel(questNo)
        else -> "第 $questNo 任务 · 第 $proposalNo 次提案"
    }

    /** 投票摘要；逐人票与汇总票都要能正确表达。 */
    private fun voteSummary(event: TeamVoteRecorded): String {
        val seats = event.votesBySeat
        if (seats != null && event.approveCount == null && event.rejectCount == null) {
            val approve = seats.values.count { it == TeamVoteChoice.APPROVE }
            val reject = seats.values.count { it == TeamVoteChoice.REJECT }
            val unknown = seats.values.count { it == TeamVoteChoice.UNKNOWN }
            return "组队投票：赞成 $approve / 反对 $reject / 未知 $unknown"
        }
        val approve = event.approveCount?.toString() ?: UNRECORDED_LABEL
        val reject = event.rejectCount?.toString() ?: UNRECORDED_LABEL
        val unknown = event.unknownVoteCount?.toString()
        return buildString {
            append("组队投票：赞成 ")
            append(approve)
            append(" / 反对 ")
            append(reject)
            if (unknown != null) {
                append(" / 未知 ")
                append(unknown)
            }
        }
    }

    /** 发言摘要：「声称」前缀用于跳身份，与真实身份在文案上永久区分。 */
    private fun claimTitle(event: PlayerClaimed, seatLabel: ((SeatId) -> String)?): String {
        val who = seatPhrase(event.speakerSeat, seatLabel)
        val role = event.claimedRole
        return when {
            role != null -> "${who}声称自己是${roleLabel(role.name)}"
            else -> "${who}发言（${claimTypeLabel(event.claimType.name)}）"
        }
    }

    /**
     * 发言条目的第二行：类型 / 目标 + **用户录入的自由文本**（F4）。
     *
     * 文本要进 detail 而不是 title：title 保持一行可扫读，长文放 detail。
     * 超过 [MAX_CLAIM_TEXT_LENGTH] 截断并加省略号 —— 时间线是列表，整段话会把卡片撑爆，
     * 完整文本在复盘详情里另有入口。
     *
     * 注意：这里拿到的事件**已经过可见性过滤**（`ReplayViewModel` 先用
     * `VisibleEventPolicy.forPerspective` 过滤，`RecordScreen` 用的是本人记录不受影响），
     * 本函数不做任何可见性判断，也不能绕过那条唯一出口。
     */
    private fun claimDetail(event: PlayerClaimed, seatLabel: ((SeatId) -> String)?): String {
        val meta = buildString {
            append(claimTypeLabel(event.claimType.name))
            if (event.targetSeats.isNotEmpty()) {
                append(" · 目标 ")
                append(seatListPhrase(event.targetSeats, seatLabel))
            }
        }
        val spoken = event.text?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::truncateClaimText)
        return listOf(meta, spoken.orEmpty())
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
            // `meta` 来自 `claimTypeLabel(...)`，恒非空，所以本结果恒非空。
            // 原先这里有个 `ifEmpty { "公开发言" }` 兜底——那是不可达分支，
            // 留着会让读代码的人以为「可能没有类型标签」，故删除（QA P3 建议）。
    }

    /** 时间线里的自由文本上限：超过就截断加省略号。 */
    private fun truncateClaimText(text: String): String =
        if (text.length <= MAX_CLAIM_TEXT_LENGTH) {
            text
        } else {
            text.take(MAX_CLAIM_TEXT_LENGTH) + "…"
        }

    /**
     * 角色名中文标签。
     *
     * `internal`（而非 `private`）：`PlayerTraceBuilder` 复用同一张标签表，
     * 避免「时间线写梅林、玩家轨迹写 MERLIN」这种同一事实两种叫法。
     * 仅供展示；其余领域层不产出 UI 文案（见 D6）。
     */
    internal fun roleLabel(roleName: String): String = when (roleName) {
        "MERLIN" -> "梅林"
        "PERCIVAL" -> "派西维尔"
        "LOYAL_SERVANT" -> "忠臣"
        "MORGANA" -> "莫甘娜"
        "ASSASSIN" -> "刺客"
        "MORDRED" -> "莫德雷德"
        "MINION" -> "爪牙"
        "OBERON" -> "奥伯伦"
        else -> roleName
    }

    /** 发言类型中文标签（`internal`：与 `PlayerTraceBuilder` 共用）。 */
    internal fun claimTypeLabel(raw: String): String = when (raw) {
        "SUSPECT" -> "怀疑"
        "PROTECT" -> "保护"
        "ROLE_CLAIM" -> "跳身份"
        "COUNTER_CLAIM" -> "对跳"
        "TEAM_RECOMMEND" -> "推荐组队"
        "STANCE_CHANGE" -> "改站边"
        "KEY_STATEMENT" -> "关键言论"
        "OTHER" -> "其他"
        else -> raw
    }

    /** 主观判断类型中文标签（`internal`：与 `PlayerTraceBuilder` 共用）。 */
    internal fun beliefTypeLabel(raw: String): String = when (raw) {
        "SUSPECT" -> "怀疑"
        "TRUST" -> "信任"
        "ROLE_GUESS" -> "猜身份"
        "OTHER" -> "其他"
        else -> raw
    }

    /** 终局原因中文标签。 */
    private fun winReasonLabel(raw: String): String = when (raw) {
        "GOOD_WINS_QUESTS" -> "好人任务获胜"
        "EVIL_WINS_QUESTS" -> "坏人任务获胜"
        "ASSASSINATION_SUCCESS" -> "刺杀命中梅林"
        "ASSASSINATION_FAILED" -> "刺杀未命中"
        "NO_ASSASSIN_PRESENT" -> "无刺客"
        "ABANDONED" -> "弃局"
        "UNKNOWN" -> "未知"
        else -> raw
    }
}
