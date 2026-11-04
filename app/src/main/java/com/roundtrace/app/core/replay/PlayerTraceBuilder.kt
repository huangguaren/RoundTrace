package com.roundtrace.app.core.replay

import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.model.PrivateNoteAdded
import com.roundtrace.app.core.model.QuestStarted
import com.roundtrace.app.core.model.SeatId
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteChoice
import com.roundtrace.app.core.model.TeamVoteRecorded

/**
 * 玩家行为时间线构建器（F05-01）。
 *
 * ## 一句话
 * 给定「看谁、看到什么时候、用什么视角」，回答「这个人被记录下来的参与事实有哪些」。
 *
 * ## 三条不可调换的顺序
 * 1. **先按视角过滤，再做座位聚合**。反过来写会把 `PRIVATE_OBSERVER` 的字段
 *    先读进内存、再靠「输出时别再展示」兜底 —— 一旦某个分支忘了兜底就是泄漏。
 *    这里直接复用 [VisibleEventPolicy]，它是「谁能看」的唯一出口。
 * 2. **先过滤 `retracted`，再聚合**。修订后的旧版本不是历史，是副本。
 * 3. **时间闸门在聚合之前**；唯一例外是 [PrivateNoteAdded]，它只看 `availableFromOrder`
 *    （decisions B31：回填笔记的权威可见时刻，与自身 `occurredOrder` 无关）。
 *
 * ## 只读
 * 本对象是纯函数：不写库、不改事件、不持有状态。`match_events` 的写入路径只属于记录页。
 *
 * ## 私有信息在本类的两条硬规则
 * 1. **归属优先**：私有事件只对它的主人产出条目（`PrivateNoteAdded` 看 `observerSeat`，
 *    `PlayerClaimed` 看 `speakerSeat`），绝不从「被提及」这条侧门进到别人的轨迹里。
 * 2. **标记不丢**：条目的 [PlayerTraceItem.sourceVisibility] 原样带上来源事件的可见性，
 *    由 UI 负责渲染「来自私有记录 · 仅本人可见」。
 *
 * ## 明确不做的事
 * - 不从失败牌推断「谁出的失败牌」；
 * - 不从队长/上车记录推断阵营；
 * - 不把汇总票分摊到个人；
 * - 队长自提名时不重复发「被提名上车」（同一动作不发两条）；
 * - 不产出 [PlayerTraceFactLevel.INFERRED]（宁可少显示，也不给推测）。
 */
object PlayerTraceBuilder {

    /**
     * 构建某个玩家的行为时间线。
     *
     * @param events 事件全集（可含 `retracted`、可含私有事件，本方法自行过滤）
     * @param playerSeat 被查看的座位（非法座位返回**空列表**，不抛异常）
     * @param atOrder 时间上界（当前复盘位置）
     * @param mode 视角（`PUBLIC` / `OBSERVER_AT_TIME` / `GOD_AFTER_REVEAL`）
     * @param observerSeat 观察者座位（`OBSERVER_AT_TIME` 下决定私有事件归属）
     * @param playerCount 对局人数（用于座位合法性判定）
     * @param questFilter `null` = 全部任务
     * @param categoryFilter `null` 或空集 = 全部类别
     * @return 按 `occurredOrder` 升序的记录
     */
    fun build(
        events: List<MatchEvent>,
        playerSeat: SeatId,
        atOrder: Int,
        mode: Perspective,
        observerSeat: SeatId?,
        playerCount: Int,
        questFilter: Int? = null,
        categoryFilter: Set<PlayerTraceCategory>? = null,
        /**
         * 可选的**结构化座位格式化回调**（UX-ID-02）。
         * `null` = 逐字沿用旧文案（既有测试与输出零变化）。
         * 非 null 时只在 `leaderSeat` / `teamSeats` / `speakerSeat` / `targetSeats`
         * 这些**模型字段**的展示位置调用；任务序号、票数、失败牌数与玩家自由文本
         * 永远不经过它 —— 这里没有也不允许有 `String.replace` / 正则。
         */
        seatLabel: ((SeatId) -> String)? = null
    ): List<PlayerTraceItem> {
        if (playerCount <= 0 || playerSeat !in 1..playerCount) return emptyList()

        // ① 视角（唯一出口）
        val visible = VisibleEventPolicy.forPerspective(
            events = events,
            mode = mode,
            observerSeat = observerSeat
        )

        // ② active + 时间窗 → ③ 座位聚合
        return visible.asSequence()
            .filter { !it.retracted }
            .filter { visibleAt(it, atOrder) }
            .sortedWith(compareBy({ it.occurredOrder }, { it.recordedAt }, { it.eventId.raw }))
            .flatMap { itemsOf(it, playerSeat, seatLabel).asSequence() }
            .filter { questFilter == null || it.questNo == questFilter }
            .filter { categoryFilter.isNullOrEmpty() || it.category in categoryFilter }
            .sortedWith(compareBy({ it.occurredOrder }, { it.sourceEventId.raw }))
            .toList()
    }

    /**
     * 时间闸门。
     *
     * `PrivateNoteAdded` 只看 `availableFromOrder` —— 这是 B31 钉住的唯一例外，
     * 不是「顺手放宽」：它让事后补录的笔记在语义上生效于更早的时刻，
     * 而 `availableFromOrder` 本身由记录者填写，不存在提前泄漏。
     */
    private fun visibleAt(event: MatchEvent, atOrder: Int): Boolean = when (event) {
        is PrivateNoteAdded -> event.availableFromOrder <= atOrder
        else -> event.occurredOrder <= atOrder
    }

    private fun itemsOf(
        event: MatchEvent,
        playerSeat: SeatId,
        seatLabel: ((SeatId) -> String)?
    ): List<PlayerTraceItem> = when (event) {
        is QuestStarted -> if (event.leaderSeat == playerSeat) {
            listOf(
                item(
                    event = event,
                    category = PlayerTraceCategory.LEADER,
                    relationship = PlayerTraceRelationship.LED_QUEST,
                    title = "第 ${event.questNo} 任务队长（记录为队长）",
                    detail = "任务开始记录 · 仅为队长身份记录，不推断阵营"
                )
            )
        } else {
            emptyList()
        }

        is TeamProposed -> buildList {
            val team = seatList(event.teamSeats, seatLabel)
            val isLeader = event.leaderSeat == playerSeat
            val onTeam = playerSeat in event.teamSeats
            when {
                // 队长自己也上车时只出 1 条：同一事件既发「提出提案」又发「被提名」
                // 会在列表里读成两件事，而它其实是同一个动作的两个侧面。
                isLeader -> add(
                    item(
                        event = event,
                        category = PlayerTraceCategory.LEADER,
                        relationship = PlayerTraceRelationship.PROPOSED_TEAM,
                        title = "作为队长提出${proposalLabel(event.questNo, event.proposalNo)}",
                        detail = "提案队伍：$team · 仅为队长记录，不推断阵营" +
                            if (onTeam) " · 本人也在提案队伍中" else ""
                    )
                )

                onTeam -> add(
                    item(
                        event = event,
                        category = PlayerTraceCategory.NOMINATED,
                        relationship = PlayerTraceRelationship.ON_PROPOSED_TEAM,
                        title = "被提议加入${proposalLabel(event.questNo, event.proposalNo)}的队伍",
                        detail = "提案队伍：$team · 被提议上车 ≠ 实际执行任务"
                    )
                )
            }
        }

        is TeamVoteRecorded -> {
            // 只有逐人票能归属到个人；汇总票一律不摊派（票数 ≠ 谁投的）
            val choice = event.votesBySeat?.get(playerSeat)
            if (choice == null) {
                emptyList()
            } else {
                val level = if (choice == TeamVoteChoice.UNKNOWN) {
                    PlayerTraceFactLevel.UNKNOWN
                } else {
                    PlayerTraceFactLevel.RECORDED
                }
                listOf(
                    item(
                        event = event,
                        category = PlayerTraceCategory.PERSONAL_VOTE,
                        relationship = PlayerTraceRelationship.CAST_VOTE,
                        title = "个人组队投票：${voteChoiceLabel(choice)}",
                        detail = "${proposalLabel(event.questNo, event.proposalNo)} · 本人逐人票" +
                            "（汇总票不分摊到个人）",
                        factLevel = level
                    )
                )
            }
        }

        is MissionResolved -> {
            // teamSeats == null = 完全没记队伍 → 谁在场无从谈起，绝不猜
            val team = event.teamSeats
            if (team == null || playerSeat !in team) {
                emptyList()
            } else {
                listOf(
                    item(
                        event = event,
                        category = PlayerTraceCategory.MISSION_MEMBER,
                        relationship = PlayerTraceRelationship.ON_MISSION_TEAM,
                        title = "记录为第 ${event.questNo} 任务队员",
                        detail = "任务队伍：${seatList(team, seatLabel)} · ${failCountLabel(event)}" +
                            if (event.verified) " · 已核实" else " · 未核实"
                    )
                )
            }
        }

        is PlayerClaimed -> buildList {
            // 私有声称（PRIVATE_OBSERVER）只在**发言者本人**的轨迹里出现。
            // 判据是记录者给的可见性标记，不是「这句话有没有发生过」：
            // 标成私有就是不想让它被当成公开历史，因此它也不得从「被提及」这条侧门
            // 进到别人的参与记录里（与 `PrivateNoteAdded` 的保守口径一致）。
            val isPrivateClaim = event.visibility == EventVisibility.PRIVATE_OBSERVER
            if (event.speakerSeat == playerSeat) {
                add(
                    item(
                        event = event,
                        category = PlayerTraceCategory.SPEAKER,
                        relationship = PlayerTraceRelationship.SPOKE,
                        title = speakerTitle(event, seatLabel),
                        detail = claimDetail(event, seatLabel)
                    )
                )
            } else if (!isPrivateClaim && playerSeat in event.targetSeats) {
                add(
                    item(
                        event = event,
                        category = PlayerTraceCategory.MENTIONED,
                        relationship = PlayerTraceRelationship.NAMED_BY_OTHER,
                        title = "被 ${seatPhrase(event.speakerSeat, seatLabel)}在发言中提及",
                        detail = phasePrefix(event.questNo) +
                            "发言类型：${claimTypeLabel(event.claimType.name)} · 被提及 ≠ 身份判定"
                    )
                )
            }
        }

        is PrivateNoteAdded -> if (event.observerSeat == playerSeat) {
            listOf(
                item(
                    event = event,
                    category = PlayerTraceCategory.PRIVATE_NOTE,
                    relationship = PlayerTraceRelationship.OWN_BELIEF,
                    title = "私人判断：${beliefTypeLabel(event.beliefType.name)}" +
                        if (event.targetSeats.isEmpty()) "（未记录对象）" else " ${seatList(event.targetSeats, seatLabel)}",
                    detail = "仅本人可见 · 自第 ${event.availableFromOrder} 个事件起可见" +
                        claimTextSuffix(event.text),
                    // B31：定位点是权威可见时刻，不是事件自身被录入的位置
                    occurredOrderOverride = event.availableFromOrder
                )
            )
        } else {
            emptyList()
        }

        // 刺杀 / 终局 / 弃局不属于「某个玩家的参与记录」，此处不产出条目
        else -> emptyList()
    }

    private fun item(
        event: MatchEvent,
        category: PlayerTraceCategory,
        relationship: PlayerTraceRelationship,
        title: String,
        detail: String,
        factLevel: PlayerTraceFactLevel = PlayerTraceFactLevel.RECORDED,
        occurredOrderOverride: Int? = null
    ): PlayerTraceItem = PlayerTraceItem(
        sourceEventId = event.eventId,
        occurredOrder = occurredOrderOverride ?: event.occurredOrder,
        questNo = event.questNo,
        category = category,
        relationship = relationship,
        title = title,
        detail = detail,
        factLevel = factLevel,
        // 来源可见性必须一路带到 UI：私有来源要显式标注，见 PlayerTraceItem.sourceVisibility
        sourceVisibility = event.visibility
    )

    // ————————————————————————————————————————————————————————
    // 文案（领域层只产出「记录陈述」，不产出身份判定，见 D6 / B11）
    // ————————————————————————————————————————————————————————

    /**
     * 座位列表文案。默认「2 / 3 / 4 号」（数字流 + 一次单位），
     * 有结构化映射时逐人写「小明（1号） / 阿华（2号）」。
     */
    private fun seatList(seats: List<SeatId>, seatLabel: ((SeatId) -> String)?): String = when {
        seats.isEmpty() -> "未记录"
        seatLabel == null -> seats.joinToString(" / ") + " 号"
        else -> seats.joinToString(" / ") { seatLabel(it) }
    }

    /** 单个座位引用。默认「2 号」，有映射时「阿华（2号）」。 */
    private fun seatPhrase(seat: SeatId, seatLabel: ((SeatId) -> String)?): String =
        seatLabel?.invoke(seat) ?: "$seat 号"

    private fun proposalLabel(questNo: Int?, proposalNo: Int?): String = when {
        questNo == null && proposalNo == null -> "准备阶段"
        questNo == null -> "第 $proposalNo 次提案"
        proposalNo == null -> "第 $questNo 任务"
        else -> "第 $questNo 任务 · 第 $proposalNo 次提案"
    }

    private fun phasePrefix(questNo: Int?): String =
        if (questNo == null) "准备阶段 · " else "第 $questNo 任务 · "

    private fun voteChoiceLabel(choice: TeamVoteChoice): String = when (choice) {
        TeamVoteChoice.APPROVE -> "赞成"
        TeamVoteChoice.REJECT -> "反对"
        TeamVoteChoice.UNKNOWN -> "未知"
    }

    /** `null` 是「没记」，`0` 是「确定零失败牌」；两者文案必须不同。 */
    private fun failCountLabel(event: MissionResolved): String = when (event.failCount) {
        null -> "失败牌数：未记录"
        0 -> "失败牌数：0（确定零失败牌）"
        else -> "失败牌数：${event.failCount}（不推断是谁出的）"
    }

    private fun speakerTitle(event: PlayerClaimed, seatLabel: ((SeatId) -> String)?): String {
        val who = seatPhrase(event.speakerSeat, seatLabel)
        val role = event.claimedRole
        return if (role != null) {
            "${who}声称自己是${roleLabel(role.name)}"
        } else {
            "${who}发言：${claimTypeLabel(event.claimType.name)}"
        }
    }

    private fun claimDetail(event: PlayerClaimed, seatLabel: ((SeatId) -> String)?): String {
        val target = if (event.targetSeats.isEmpty()) {
            ""
        } else {
            " · 提及 ${seatList(event.targetSeats, seatLabel)}"
        }
        val boundary = if (event.claimedRole != null) {
            " · 声称内容不等于身份事实"
        } else {
            " · 仅为本人说法"
        }
        return phasePrefix(event.questNo) + claimTypeLabel(event.claimType.name) +
            target + boundary + claimTextSuffix(event.text)
    }

    private fun claimTextSuffix(text: String?): String {
        val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
        return " · " + truncate(trimmed)
    }

    private fun truncate(text: String): String =
        if (text.length <= TimelineBuilder.MAX_CLAIM_TEXT_LENGTH) {
            text
        } else {
            text.take(TimelineBuilder.MAX_CLAIM_TEXT_LENGTH) + "…"
        }

    private fun roleLabel(roleName: String): String = TimelineBuilder.roleLabel(roleName)

    private fun claimTypeLabel(raw: String): String = TimelineBuilder.claimTypeLabel(raw)

    private fun beliefTypeLabel(raw: String): String = TimelineBuilder.beliefTypeLabel(raw)
}
