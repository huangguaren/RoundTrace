package com.roundtrace.app.core.knowledge

import com.roundtrace.app.core.model.Belief
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.FactKind
import com.roundtrace.app.core.model.GamePosition
import com.roundtrace.app.core.model.KnowledgeBasis
import com.roundtrace.app.core.model.KnowledgeLabel
import com.roundtrace.app.core.model.KnowledgeSnapshot
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.model.PrivateFact
import com.roundtrace.app.core.model.PrivateNoteAdded
import com.roundtrace.app.core.model.PrivateRelation
import com.roundtrace.app.core.model.PublicFact
import com.roundtrace.app.core.model.ReconstructedMatch
import com.roundtrace.app.core.model.RevealState
import com.roundtrace.app.core.model.RoleAssignment
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.RoleSource
import com.roundtrace.app.core.model.SeatId
import com.roundtrace.app.core.model.SeatKnowledge
import com.roundtrace.app.core.model.TeamProposalResolved
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteRecorded
import com.roundtrace.app.core.model.UnknownItem
import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.AssassinationRecorded
import com.roundtrace.app.core.replay.ReplayEngine
import com.roundtrace.app.core.rules.GameReducer
import com.roundtrace.app.core.rules.avalon.AvalonRoleKnowledge

/**
 * 阿瓦隆视角引擎（ARCHITECTURE §7.2 / §7.3 / TASKS.md T51）。
 *
 * ## 三道闸门（每一道都可被单测钉住）
 *
 * 1. **时间边界**：`events` 一律先按 `occurredOrder <= at.occurredOrder` 裁剪，
 *    后续发言/结果**不可能**出现在快照里。`at` 是硬边界，不存在「稍微多给一点」。
 * 2. **可见性边界**：`PRIVATE_OBSERVER` 事件只在 `OBSERVER_AT_TIME` 模式下、
 *    并且 `observerSeat == 观察者` 时才进入 `subjectiveBeliefs`；
 *    `PUBLIC` 模式下私有事实与主观判断**恒为空集合**。
 * 3. **知识来源边界**：每条 [PrivateFact] 都带 [KnowledgeBasis]，
 *    能区分「开局规则给的」（`RULESET_INITIAL`）与「按规则重建的初始信息」
 *    （`RECONSTRUCTED_INITIAL`）。后者在快照上另有
 *    [KnowledgeSnapshot.observerRoleIsReconstructed] = true，
 *    UI 必须用不同措辞（「按规则重建的初始信息」vs「你当时知道」）。
 *
 * ## 反剧透硬规则（ARCHITECTURE §7.3 逐条）
 *
 * - `OBSERVER_AT_TIME` **永不**读 `revealState == REVEALED` 当作「当时已知」；
 *   它只读夜间知识规则（[AvalonRoleKnowledge]）与公开事件。
 * - 公开声称只产生 `FactKind.CLAIM` 与 `CLAIMED_*` 标签，**永不写入 `visibleRole`**。
 * - 主观笔记永不进入 `publicFacts`，即使猜对。
 * - 本类不产出结论（`findings` 留空），结论由 [ReplayAnalyzer] 生成，职责分离。
 *
 * @param reducer 仅用于推导当前 `status`（`canOpenGodView` 需要它）；无 IO、无 suspend
 */
class AvalonKnowledgeProvider(
    private val reducer: GameReducer
) : KnowledgeProvider {

    override fun snapshot(
        match: ReconstructedMatch,
        observer: SeatId?,
        at: GamePosition,
        mode: Perspective
    ): KnowledgeSnapshot {
        if (mode == Perspective.GOD_AFTER_REVEAL && match.revealedAt == null) {
            // 调用方必须先检查 canOpenGodView（T51⑥：这里直接抛，绝不「宽容地」返回空快照）
            throw IllegalStateException(
                "对局尚未揭晓（revealedAt == null），不能构建上帝视角快照"
            )
        }

        // —— 闸门 1：时间边界 ——
        val eventsAtTime = match.events
            .asSequence()
            .filter { !it.retracted }
            .filter { at.includes(it.occurredOrder) }
            .sortedBy { it.occurredOrder }
            .toList()

        val gameState = ReplayEngine(reducer).replay(match, at.occurredOrder).state
        val roleTruth = match.roleTruth()
        val playerCount = match.config.playerCount
        val seats: List<SeatId> = (1..playerCount).toList()

        val observerAssignment: RoleAssignment? = observer?.let { match.roleAssignments[it] }
        val observerRole: RoleId? = when (mode) {
            Perspective.PUBLIC -> null
            Perspective.OBSERVER_AT_TIME -> observerAssignment?.roleId ?: RoleId.UNKNOWN
            Perspective.GOD_AFTER_REVEAL -> observer?.let { roleTruth[it] ?: RoleId.UNKNOWN }
        }
        val observerRoleIsReconstructed =
            mode == Perspective.OBSERVER_AT_TIME && isReconstructedRole(match, observerAssignment)

        // —— 闸门 2 + 3：私有知识与来源 ——
        val privateFacts: List<PrivateFact> = when (mode) {
            Perspective.PUBLIC -> emptyList()

            Perspective.OBSERVER_AT_TIME -> observer?.let { seat ->
                buildObserverPrivateFacts(
                    observerSeat = seat,
                    observerRole = observerRole ?: RoleId.UNKNOWN,
                    roleTruth = roleTruth,
                    reconstructed = observerRoleIsReconstructed
                )
            } ?: emptyList()

            Perspective.GOD_AFTER_REVEAL -> buildGodPrivateFacts(roleTruth, seats)
        }

        // 主观笔记的可见性由 `availableFromOrder` 决定，**不是**由事件的 occurredOrder 决定：
        // 允许「事后回填一条可见时间更早的笔记」。这不是时间闸门的漏洞——
        // `availableFromOrder` 才是这条笔记的权威可见时间点（ARCHITECTURE §7.1 / TDD §5.2-4）。
        val notesVisibleAt = match.events
            .filterIsInstance<PrivateNoteAdded>()
            .filter { !it.retracted && at.includes(it.availableFromOrder) }
            .sortedBy { it.occurredOrder }

        val beliefs: List<Belief> = when (mode) {
            Perspective.PUBLIC -> emptyList()

            Perspective.OBSERVER_AT_TIME -> notesVisibleAt
                .filter { it.observerSeat == observer }
                .map { note ->
                    Belief(
                        id = note.eventId.raw,
                        observerSeat = note.observerSeat,
                        beliefType = note.beliefType,
                        targetSeats = note.targetSeats,
                        text = note.text,
                        availableFromOrder = note.availableFromOrder,
                        sourceEventIds = listOf(note.eventId)
                    )
                }

            Perspective.GOD_AFTER_REVEAL -> notesVisibleAt
                .map { note ->
                    Belief(
                        id = note.eventId.raw,
                        observerSeat = note.observerSeat,
                        beliefType = note.beliefType,
                        targetSeats = note.targetSeats,
                        text = note.text,
                        availableFromOrder = note.availableFromOrder,
                        sourceEventIds = listOf(note.eventId)
                    )
                }
        }

        val publicFacts = buildPublicFacts(eventsAtTime)
        val seatKnowledge = buildSeatKnowledge(
            seats = seats,
            mode = mode,
            observerSeat = observer,
            observerRole = observerRole,
            privateFacts = privateFacts,
            eventsAtTime = eventsAtTime,
            roleTruth = roleTruth
        )

        val unknowns = buildUnknowns(
            mode = mode,
            observerRole = observerRole,
            observerSeat = observer,
            seatKnowledge = seatKnowledge,
            eventsAtTime = eventsAtTime,
            playerCount = playerCount
        )

        return KnowledgeSnapshot(
            at = at,
            mode = mode,
            observerSeat = if (mode == Perspective.PUBLIC) null else observer,
            observerRole = observerRole,
            observerRoleIsReconstructed = observerRoleIsReconstructed,
            publicFacts = publicFacts,
            privateRoleFacts = privateFacts,
            subjectiveBeliefs = beliefs,
            seatKnowledge = seatKnowledge,
            findings = emptyList(),
            unknowns = unknowns,
            canOpenGodView = canOpenGodView(match, gameState.status),
            playerCount = playerCount,
            goodCount = matrixGood(playerCount),
            evilCount = matrixEvil(playerCount)
        )
    }

    // ————————————————————————————————————————————————————————
    // 上帝视角开放条件（ARCHITECTURE §9.3-7 的数据条件）
    // ————————————————————————————————————————————————————————

    /**
     * `revealedAt != null && status ∈ {AWAITING_ASSASSINATION, FINISHED_GOOD, FINISHED_EVIL}`。
     *
     * 刻意用**数据条件**而不是「当前状态 != 某值」：
     * PRD 要求「结局揭晓后」，DESIGN_SPEC 只约束「入口不早于刺杀阶段」，两者同时满足只有这一条。
     */
    fun canOpenGodView(match: ReconstructedMatch, status: MatchStatus): Boolean {
        if (match.revealedAt == null) return false
        return status == MatchStatus.AWAITING_ASSASSINATION ||
            status == MatchStatus.FINISHED_GOOD ||
            status == MatchStatus.FINISHED_EVIL
    }

    // ————————————————————————————————————————————————————————
    // 私有事实
    // ————————————————————————————————————————————————————————

    /**
     * 观察者本人当时能掌握的信息。
     *
     * 关键：这里**只**用夜间知识规则对 `roleTruth` 求值，绝不把
     * `revealState == REVEALED` 当成「当时已知」——那正是 TDD §5.2-3 禁止的倒灌。
     */
    private fun buildObserverPrivateFacts(
        observerSeat: SeatId,
        observerRole: RoleId,
        roleTruth: Map<SeatId, RoleId>,
        reconstructed: Boolean
    ): List<PrivateFact> {
        if (observerRole == RoleId.UNKNOWN) return emptyList()

        val basis = if (reconstructed) {
            KnowledgeBasis.RECONSTRUCTED_INITIAL
        } else {
            KnowledgeBasis.RULESET_INITIAL
        }

        val facts = mutableListOf<PrivateFact>()

        // 自己是什么阵营，自己当然知道。
        //
        // 注意：`KNOWN_GOOD_SELF` 的语义是「确定自己是**好人**」，
        // 因此只有好人观察者才产出这条；坏人观察者的自身阵营由
        // buildSeatKnowledge 从 observerRole.alignment 直接填，
        // 否则会把刺客/莫甘娜标成好人（曾经的真实 bug）。
        val ownAlignment = observerRole.alignment
        if (ownAlignment == Alignment.GOOD) {
            facts += PrivateFact(
                id = "own-role-$observerSeat",
                subjectSeat = observerSeat,
                targetSeat = observerSeat,
                relation = PrivateRelation.KNOWN_GOOD_SELF,
                basis = KnowledgeBasis.OBSERVER_OWN_ROLE
            )
        }

        val night = AvalonRoleKnowledge.knowledgeFor(
            seat = observerSeat,
            roleId = observerRole,
            assignments = roleTruth
        )

        // 梅林：确定坏人（规则已排除莫德雷德与奥伯伦）
        night.visibleEvilSeats.forEach { target ->
            facts += PrivateFact(
                id = "merlin-evil-$observerSeat-$target",
                subjectSeat = observerSeat,
                targetSeat = target,
                relation = PrivateRelation.KNOWN_EVIL,
                basis = basis
            )
        }

        // 派西维尔：梅林候选（**不是**「就是梅林」）
        night.merlinCandidates.forEach { target ->
            facts += PrivateFact(
                id = "percival-candidate-$observerSeat-$target",
                subjectSeat = observerSeat,
                targetSeat = target,
                relation = PrivateRelation.MERLIN_CANDIDATE,
                basis = basis
            )
        }

        // 坏人：互认（规则已排除奥伯伦与自身）
        night.evilTeammates.forEach { target ->
            facts += PrivateFact(
                id = "evil-teammate-$observerSeat-$target",
                subjectSeat = observerSeat,
                targetSeat = target,
                relation = PrivateRelation.EVIL_TEAMMATE,
                basis = basis
            )
        }

        return facts
    }

    /** 上帝视角：每个座位对每个其他座位的关系都是「已知阵营」。 */
    private fun buildGodPrivateFacts(
        roleTruth: Map<SeatId, RoleId>,
        seats: List<SeatId>
    ): List<PrivateFact> = buildList {
        seats.forEach { subject ->
            seats.forEach { target ->
                if (subject == target) return@forEach
                val role = roleTruth[target] ?: RoleId.UNKNOWN
                when (role.alignment) {
                    Alignment.EVIL -> add(
                        PrivateFact(
                            id = "god-evil-$subject-$target",
                            subjectSeat = subject,
                            targetSeat = target,
                            relation = PrivateRelation.KNOWN_EVIL,
                            basis = KnowledgeBasis.PUBLIC_EVENT
                        )
                    )

                    Alignment.GOOD -> add(
                        PrivateFact(
                            id = "god-good-$subject-$target",
                            subjectSeat = subject,
                            targetSeat = target,
                            relation = PrivateRelation.KNOWN_GOOD_SELF,
                            basis = KnowledgeBasis.PUBLIC_EVENT
                        )
                    )

                    Alignment.UNKNOWN -> Unit
                }
            }
        }
    }

    // ————————————————————————————————————————————————————————
    // 公开事实
    // ————————————————————————————————————————————————————————

    /**
     * 只看 `PUBLIC` 可见性的事件。
     *
     * `summary` 是展示用字符串（本类型被 ARCHITECTURE 定义为 UI 中间模型），
     * 与 `TimelineEntry` 同类；规则判定不使用它。
     */
    private fun buildPublicFacts(eventsAtTime: List<MatchEvent>): List<PublicFact> =
        eventsAtTime.mapNotNull { event ->
            when (event) {
                is TeamProposed -> PublicFact(
                    id = event.eventId.raw,
                    kind = FactKind.TEAM_ANNOUNCED,
                    questNo = event.questNo,
                    proposalNo = event.proposalNo,
                    occurredOrder = event.occurredOrder,
                    summary = "第 ${event.questNo} 任务第 ${event.proposalNo} 次提案：" +
                        "队长 ${event.leaderSeat} 号，队伍 " +
                        event.teamSeats.joinToString("/") { "$it 号" },
                    sourceEventIds = listOf(event.eventId)
                )

                is TeamVoteRecorded -> PublicFact(
                    id = event.eventId.raw,
                    kind = FactKind.VOTE_RESULT,
                    questNo = event.questNo,
                    proposalNo = event.proposalNo,
                    occurredOrder = event.occurredOrder,
                    summary = "组队投票：赞成 ${event.approveCount ?: "未记录"} / " +
                        "反对 ${event.rejectCount ?: "未记录"}",
                    sourceEventIds = listOf(event.eventId)
                )

                is TeamProposalResolved -> PublicFact(
                    id = event.eventId.raw,
                    kind = FactKind.VOTE_RESULT,
                    questNo = event.questNo,
                    proposalNo = event.proposalNo,
                    occurredOrder = event.occurredOrder,
                    summary = "组队决议：" + when (event.decision.name) {
                        "APPROVED" -> "通过"
                        "REJECTED" -> "否决"
                        else -> "待定"
                    },
                    sourceEventIds = listOf(event.eventId)
                )

                is MissionResolved -> PublicFact(
                    id = event.eventId.raw,
                    kind = FactKind.MISSION_RESULT,
                    questNo = event.questNo,
                    proposalNo = null,
                    occurredOrder = event.occurredOrder,
                    summary = "第 ${event.questNo} 任务结果：失败牌 ${event.failCount ?: "未记录"} 张",
                    sourceEventIds = listOf(event.eventId)
                )

                is PlayerClaimed -> PublicFact(
                    id = event.eventId.raw,
                    kind = FactKind.CLAIM,
                    questNo = event.questNo,
                    proposalNo = event.proposalNo,
                    occurredOrder = event.occurredOrder,
                    summary = "${event.speakerSeat} 号声称：" +
                        (event.claimedRole?.name ?: event.claimType.name),
                    sourceEventIds = listOf(event.eventId)
                )

                is AssassinationRecorded -> PublicFact(
                    id = event.eventId.raw,
                    kind = FactKind.ASSASSINATION,
                    questNo = event.questNo,
                    proposalNo = null,
                    occurredOrder = event.occurredOrder,
                    summary = "${event.assassinSeat} 号刺杀 ${event.targetSeat} 号",
                    sourceEventIds = listOf(event.eventId)
                )

                // 私有笔记永不进入 publicFacts（临时规则 §7.3-4）
                is PrivateNoteAdded -> null

                else -> null
            }
        }

    // ————————————————————————————————————————————————————————
    // 座位认知
    // ————————————————————————————————————————————————————————

    private fun buildSeatKnowledge(
        seats: List<SeatId>,
        mode: Perspective,
        observerSeat: SeatId?,
        observerRole: RoleId?,
        privateFacts: List<PrivateFact>,
        eventsAtTime: List<MatchEvent>,
        roleTruth: Map<SeatId, RoleId>
    ): Map<SeatId, SeatKnowledge> {
        val claimLabels = claimLabels(eventsAtTime)

        return seats.associateWith { seat ->
            var knownAlignment: Alignment? = null
            var visibleRole: RoleId? = null
            val candidateRoles = mutableListOf<RoleId>()
            val labels = claimLabels[seat]?.toMutableSet() ?: mutableSetOf()
            val evidence = mutableListOf<EventId>()

            when (mode) {
                Perspective.PUBLIC -> Unit

                Perspective.OBSERVER_AT_TIME -> {
                    if (seat == observerSeat && observerRole != null && observerRole != RoleId.UNKNOWN) {
                        labels += KnowledgeLabel.SELF
                        observerRole.alignment.takeIf { it != Alignment.UNKNOWN }?.let {
                            knownAlignment = it
                        }
                    }
                    privateFacts.filter { it.targetSeat == seat }.forEach { fact ->
                        when (fact.relation) {
                            PrivateRelation.KNOWN_EVIL,
                            PrivateRelation.EVIL_TEAMMATE -> {
                                knownAlignment = Alignment.EVIL
                            }

                            PrivateRelation.MERLIN_CANDIDATE -> {
                                candidateRoles += RoleId.MERLIN
                                candidateRoles += RoleId.MORGANA
                            }

                            PrivateRelation.KNOWN_GOOD_SELF -> {
                                knownAlignment = Alignment.GOOD
                            }
                        }
                        evidence += fact.sourceEventIds
                    }
                }

                Perspective.GOD_AFTER_REVEAL -> {
                    val role = roleTruth[seat] ?: RoleId.UNKNOWN
                    if (role != RoleId.UNKNOWN) {
                        visibleRole = role
                        role.alignment.takeIf { it != Alignment.UNKNOWN }?.let {
                            knownAlignment = it
                        }
                    }
                }
            }

            SeatKnowledge(
                seat = seat,
                knownAlignment = knownAlignment,
                visibleRole = visibleRole,
                candidateRoles = candidateRoles.distinct(),
                labels = labels,
                evidenceIds = evidence.distinct()
            )
        }
    }

    /**
     * 公开声称 → 标签。
     *
     * **永不写入 `visibleRole`**：`CLAIMED_MERLIN` 只是「他声称自己是梅林」，
     * 与真实身份是两件事（K08）。
     */
    private fun claimLabels(eventsAtTime: List<MatchEvent>): Map<SeatId, Set<KnowledgeLabel>> {
        val result = mutableMapOf<SeatId, MutableSet<KnowledgeLabel>>()
        eventsAtTime.filterIsInstance<PlayerClaimed>().forEach { claim ->
            val label = when (claim.claimedRole) {
                RoleId.MERLIN -> KnowledgeLabel.CLAIMED_MERLIN
                RoleId.PERCIVAL -> KnowledgeLabel.CLAIMED_PERCIVAL
                else -> null
            } ?: return@forEach
            result.getOrPut(claim.speakerSeat) { mutableSetOf() } += label
        }
        return result
    }

    // ————————————————————————————————————————————————————————
    // 未知项
    // ————————————————————————————————————————————————————————

    private fun buildUnknowns(
        mode: Perspective,
        observerRole: RoleId?,
        observerSeat: SeatId?,
        seatKnowledge: Map<SeatId, SeatKnowledge>,
        eventsAtTime: List<MatchEvent>,
        playerCount: Int
    ): List<UnknownItem> {
        val unknowns = mutableListOf<UnknownItem>()

        if (mode == Perspective.OBSERVER_AT_TIME) {
            if (observerRole == null || observerRole == RoleId.UNKNOWN) {
                // 观察者角色未知 → 整个视角退化为「什么都不知道」
                unknowns += UnknownItem(
                    code = UNKNOWN_OBSERVER_ROLE,
                    seats = listOfNotNull(observerSeat)
                )
            }
            val unknownSeats = seatKnowledge
                .filterValues { it.isUnknown && it.seat != observerSeat }
                .keys
                .sorted()
            if (unknownSeats.isNotEmpty()) {
                unknowns += UnknownItem(code = UNKNOWN_MOST_SEATS, seats = unknownSeats)
            }
        }

        // 数据残缺：任务结果未记录失败牌数
        eventsAtTime.filterIsInstance<MissionResolved>()
            .filter { it.failCount == null }
            .forEach { unknowns += UnknownItem(code = UNKNOWN_FAIL_COUNT, questNo = it.questNo) }

        // 队伍成员「未记录」或「记录了但不完整」——两者都要报 UNKNOWN
        eventsAtTime.filterIsInstance<MissionResolved>().forEach { mission ->
            val expected = expectedTeamSize(playerCount, mission.questNo)
            val recorded = mission.teamSeats?.distinct()
            val incomplete = recorded == null ||
                (expected > 0 && recorded.size != expected)
            if (incomplete) {
                unknowns += UnknownItem(code = UNKNOWN_MISSION_TEAM, questNo = mission.questNo)
            }
        }

        return unknowns
    }

    // ————————————————————————————————————————————————————————
    // 工具
    // ————————————————————————————————————————————————————————

    /**
     * 角色是否为「结局后补录」。
     *
     * 判定优先级：
     * 1. 来源就是 `REVEALED_AFTER_END` → 一定是补录；
     * 2. 对局已揭晓且该座位 `revealState == REVEALED` → 视为揭晓时才知道（补录）；
     * 3. 其余（`KNOWN_TO_SELF` / 创建时手填）→ 当时就知道，不做重建。
     */
    private fun isReconstructedRole(
        match: ReconstructedMatch,
        assignment: RoleAssignment?
    ): Boolean {
        if (assignment == null) return false
        if (assignment.roleId == RoleId.UNKNOWN) return false
        if (assignment.source == RoleSource.REVEALED_AFTER_END) return true
        return match.revealedAt != null && assignment.revealState == RevealState.REVEALED
    }

    /** 该人数下第 questNo 个任务的期望队伍人数（非法输入返回 0 = 不判定完整性）。 */
    private fun expectedTeamSize(playerCount: Int, questNo: Int): Int = runCatching {
        com.roundtrace.app.core.rules.avalon.AvalonPreset
            .counts(playerCount)
            .teamSizes[questNo - 1]
    }.getOrDefault(0)

    /** 该人数下的好人总数（人数非法时返回 0，分析器会因此降级为「信息不足」）。 */
    private fun matrixGood(playerCount: Int): Int = runCatching {
        com.roundtrace.app.core.rules.avalon.AvalonPreset.counts(playerCount).good
    }.getOrDefault(0)

    /** 该人数下的坏人总数。 */
    private fun matrixEvil(playerCount: Int): Int = runCatching {
        com.roundtrace.app.core.rules.avalon.AvalonPreset.counts(playerCount).evil
    }.getOrDefault(0)

    companion object {
        /** 观察者角色未知。 */
        const val UNKNOWN_OBSERVER_ROLE: String = "OBSERVER_ROLE_UNKNOWN"

        /** 大多数座位身份未知。 */
        const val UNKNOWN_MOST_SEATS: String = "MOST_SEATS_UNKNOWN"

        /** 任务失败牌数未记录。 */
        const val UNKNOWN_FAIL_COUNT: String = "FAIL_COUNT_UNRECORDED"

        /** 任务队伍成员未记录。 */
        const val UNKNOWN_MISSION_TEAM: String = "MISSION_TEAM_UNRECORDED"
    }
}
