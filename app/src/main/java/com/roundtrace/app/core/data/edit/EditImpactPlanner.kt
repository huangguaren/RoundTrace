package com.roundtrace.app.core.data.edit

import com.roundtrace.app.core.data.EventRevisionPolicy
import com.roundtrace.app.core.data.MatchBundle
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventType
import com.roundtrace.app.core.model.IssueCode
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.ReconstructedMatch
import com.roundtrace.app.core.model.TeamProposalResolved
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteRecorded
import com.roundtrace.app.core.replay.ReplayEngine
import com.roundtrace.app.core.rules.GameReducer
import kotlin.coroutines.cancellation.CancellationException

/**
 * 历史安全编辑的**影响范围纯函数**（NX-06 / PRD §2.2，TASKS.md A2）。
 *
 * ## 这个类解决的问题
 *
 * 旧实现改历史时只有两条路：`reviseEvent()` 改一条、`retractAfterOrder()` 按逻辑位置截断。
 * 后者把「位置大于 cutoff 的全部事件」当成受影响集合 —— 那是**推定**，不是依赖：
 * 改第 1 任务的提案会连带删掉第 3 任务的投票、别人的发言和笔记。
 * 本类只回答一个问题：**这条修改到底让哪些具体事件失去了前提**。
 *
 * ## 三条硬约束
 *
 * 1. **只做纯计算**：不读库、不写库、不碰 Android/Room，输入是「一致快照」+「快照令牌」+「替换事件」；
 * 2. **禁止按位置推定**：集合只能由「同任务 + 同提案」「同任务任务结果」「修改后是否终局」三类真实依赖推导，
 *    绝不允许 `logicalPosition > cutoff` 全选（PRD §2.2 决定性约束）；
 * 3. **证明不了就只说、不动手**：无法可靠判定时把原因放进 [EditImpactPlan.manualReviewReasons]，
 *    并且**不产生任何 RETRACT**；`requiresManualReview && isDestructive` 的组合会被仓储直接拒签。
 *
 * ## 依赖规则（实现口径，逐条对应 PRD §2.2 表格）
 *
 * | 修改对象 | RETRACT（确实失去前提） | REVIEW_ONLY（只提示） |
 * |---|---|---|
 * | `TeamProposed`（同 Q 同 P） | 同 Q 同 P 的投票、同 Q 同 P 的决议、**由同 Q 同 P 这次提案执行**的任务结果 | 任务结果存在但该任务没有通过决议、归属无法证明 |
 * | `TeamVoteRecorded` | 无（B35：决议是不可编辑的现场事实） | 同 Q 同 P 的决议与重算结论矛盾时 |
 * | `MissionResolved` | 仅当修改使对局在**该结果处**进入终局时，其后不再可能发生的任务性事件 | 修改使对局**不再是**终局时 |
 *
 * 两条容易踩空的边界（各对应一个已修复的真实缺陷）：
 *
 * 1. **内容一字未改 → 零影响**：提案的队长与队伍都与替换事件完全一致时直接返回空结果。
 *    「打开编辑 → 直接保存」在 UI 上是可达路径，不能靠「用户不会这么干」兜底。
 * 2. **任务结果的归属看「哪一次提案被通过」，不看「车队像不像」**：
 *    同一任务里「提案 1 被否决 → 提案 2 用相同队伍通过」是常态，仅凭车队相同就撤销任务结果
 *    等于凭空删数据。判定依据是本任务**最后一个 active 的通过决议**的 `proposalNo`。
 *
 * 关于任务结果的「依赖」判定（比「同 Q 就撤销」更窄，也更安全）：
 * 只有**本提案就是把队伍送上任务的那一次**（本任务最后一个通过决议属于本提案）时，
 * 任务结果才算依赖本次修改。否则（例如改的是同一任务里被否决的那个提案）撤掉任务是**凭空删数据**。
 */
class EditImpactPlanner(private val reducer: GameReducer) {

    /**
     * 计算影响范围。
     *
     * @param bundle 当前一致快照（`bundle.events` 只含 active，按逻辑位置升序）
     * @param expectedActive 快照令牌，**必须来自 DAO 实体行**（`logicalPosition` / `revisionSeq`
     *        在领域事件上不存在，不允许在这里凭空填充）
     * @param targetEventId 被修改的事件
     * @param replacement 替换事件（由 UI 用被编辑事件的轮次/提案号构造）
     */
    fun plan(
        bundle: MatchBundle,
        expectedActive: List<ActiveEventToken>,
        targetEventId: EventId,
        replacement: MatchEvent
    ): EditPlanResult {
        val events = bundle.events

        verifySnapshot(events, expectedActive)?.let { problem ->
            return EditPlanResult.Rejected(EditPlanRejection.INCONSISTENT_SNAPSHOT, problem)
        }

        val target = events.firstOrNull { it.eventId == targetEventId }
            ?: return EditPlanResult.Rejected(
                EditPlanRejection.TARGET_NOT_FOUND,
                "目标事件不在本局有效事件中（可能已被撤销或从未写入）：${targetEventId.raw}"
            )

        if (target.type !in EDITABLE_TYPES) {
            return EditPlanResult.Rejected(
                EditPlanRejection.TARGET_NOT_EDITABLE,
                "事件类型不可编辑：${target.type.name}（只允许 $EDITABLE_TYPES_TEXT）"
            )
        }

        if (events.any { it.eventId == replacement.eventId }) {
            return EditPlanResult.Rejected(
                EditPlanRejection.DUPLICATE_EVENT_ID,
                "替换事件的 id 与本局已有事件重复：${replacement.eventId.raw}"
            )
        }

        val revision = EventRevisionPolicy.check(target, replacement, sameMatch = true)
        if (!revision.allowed) {
            return EditPlanResult.Rejected(
                EditPlanRejection.REVISION_NOT_ALLOWED,
                revision.reasonOrEmpty
            )
        }

        // 提案号是「这是哪一次提案」的身份：允许改队伍/票数，不允许借编辑之名把事件搬到另一次提案
        if (proposalNoOf(target) != null && proposalNoOf(target) != proposalNoOf(replacement)) {
            return EditPlanResult.Rejected(
                EditPlanRejection.REVISION_NOT_ALLOWED,
                "替换事件必须与被替代事件属于同一提案：" +
                    "期望 ${proposalNoOf(target)}，实际 ${proposalNoOf(replacement)}"
            )
        }

        // P2：业务字段等价 → 不开放修订。放在这里（而不是只放在 UI）是为了让**预览与确认
        // 走同一道闸**：确认时会在新事务里重算计划，因此并发场景下「内容已被别人改回原值」
        // 同样会被拒，不会出现「UI 提示无修改、仓储照样写一跳」的 TOCTOU。
        if (businessEquivalent(target, replacement)) {
            return EditPlanResult.Rejected(
                EditPlanRejection.NO_BUSINESS_CHANGE,
                "内容未修改（${businessFieldSummary(target)}），不会生成新的修订版本"
            )
        }

        val findings = try {
            when (target) {
                is TeamProposed -> proposalFindings(events, target, replacement)
                is TeamVoteRecorded -> voteFindings(bundle, target, replacement)
                is MissionResolved -> missionFindings(bundle, target, replacement)
                else -> Findings(emptyList(), emptyList())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // 说不清就不动手：只记录原因，不产生任何 RETRACT
            Findings(
                affected = emptyList(),
                manualReviewReasons = listOf(
                    "无法完成修改后的重放推演（${failure::class.java.simpleName}），请人工核对后续记录"
                )
            )
        }

        val positionOf = events.withIndex().associate { (index, event) -> event.eventId to index }
        val affected = findings.affected
            // 目标本身永远不在受影响清单里：它是被修订的那一条
            .filter { it.eventId != targetEventId }
            .groupBy { it.eventId }
            .map { (_, sameEvent) ->
                // 同一条事件既有 RETRACT 又有 REVIEW_ONLY 时以更强的语义为准
                sameEvent.firstOrNull { it.operation == ImpactOperation.RETRACT } ?: sameEvent.first()
            }
            .sortedBy { positionOf[it.eventId] ?: Int.MAX_VALUE }

        return EditPlanResult.Ready(
            EditImpactPlan(
                matchId = bundle.summary.matchId,
                targetId = targetEventId,
                replacement = replacement,
                expectedActive = expectedActive.sortedBy { it.logicalPosition },
                affected = affected,
                manualReviewReasons = findings.manualReviewReasons.distinct()
            )
        )
    }

    // ————————————————————————————————————————————————————————
    // 业务等价比较（P2：无变化编辑不得产生新修订）
    // ————————————————————————————————————————————————————————

    /**
     * 两份事件在**业务字段**上是否等价。
     *
     * 只比较「玩家记错了可以改的东西」：
     * - [TeamProposed]：`leaderSeat` + `teamSeats`
     * - [TeamVoteRecorded]：逐人票、汇总票、未知票、投票时人数
     * - [MissionResolved]：失败牌数、观察结果、队伍、应有队伍人数
     *
     * 刻意**不比较** `eventId`、`recordedAt`、`occurredOrder`、`supersedesId`、`visibility`、
     * `retracted` —— 它们是「这条记录是怎么存进来的」的元数据，不是玩家要维护的事实。
     * `verified` 同样排除：它是 reducer 回填的可复核性标记，随上下文变化，不构成用户修改。
     *
     * 两个容易写错的地方：
     * 1. **顺序不代表变化**：`teamSeats` 是集合语义，同一批座位换了录入顺序仍是同一支队伍，
     *    因此比较前先排序（保留重复元素的重数，非法数据也能稳定比较）。
     * 2. **`null` 与 `0` 不等价**：`failCount = null` 是「没记」，`0` 是「确定没出失败牌」；
     *    `approveCount = null` 同理。`Int?` 直接用 `==` 比较即可保住这个区分，不要 `?: 0`。
     */
    private fun businessEquivalent(target: MatchEvent, replacement: MatchEvent): Boolean =
        when (target) {
            is TeamProposed -> {
                val after = replacement as? TeamProposed ?: return false
                target.leaderSeat == after.leaderSeat &&
                    target.teamSeats.sorted() == after.teamSeats.sorted()
            }

            is TeamVoteRecorded -> {
                val after = replacement as? TeamVoteRecorded ?: return false
                target.votesBySeat == after.votesBySeat &&
                    target.approveCount == after.approveCount &&
                    target.rejectCount == after.rejectCount &&
                    target.unknownVoteCount == after.unknownVoteCount &&
                    target.playerCountAtVote == after.playerCountAtVote
            }

            is MissionResolved -> {
                val after = replacement as? MissionResolved ?: return false
                target.failCount == after.failCount &&
                    target.observedOutcome == after.observedOutcome &&
                    target.teamSizeExpected == after.teamSizeExpected &&
                    sameSeats(target.teamSeats, after.teamSeats)
            }

            // 不可编辑类型在 plan() 上游已拒；这里返回 false（视作有变化）不会放行任何写入，
            // 只是把「是否无变化」的判定留给真正的白名单闸门，避免此处成为绕过点。
            else -> false
        }

    /** 座位清单等价：`null`（完全未记录）与空列表不是一回事。 */
    private fun sameSeats(before: List<Int>?, after: List<Int>?): Boolean = when {
        before == null || after == null -> before == null && after == null
        else -> before.sorted() == after.sorted()
    }

    /** 「内容未修改」提示里说明比对了哪些字段，避免用户以为改的东西被吞了。 */
    private fun businessFieldSummary(event: MatchEvent): String = when (event) {
        is TeamProposed -> "队长与队伍均未改变"
        is TeamVoteRecorded -> "票数与投票时人数均未改变"
        is MissionResolved -> "失败牌数、结果与队伍均未改变"
        else -> "业务字段均未改变"
    }

    // ————————————————————————————————————————————————————————
    // 各类型的依赖规则
    // ————————————————————————————————————————————————————————

    /**
     * 改组队提案：队伍变了，关于这支队伍的投票与决议就不再是同一件事。
     *
     * ## 两条必须守住的边界（独立 QA 对抗性验收各抓到一条）
     *
     * 1. **内容一字未改 → 零影响**。`openEdit` 用原值预填、`canSubmit` 只校验人数，
     *    所以「打开编辑 → 直接保存」是现场最容易触发的路径。旧实现从不比较新旧提案内容，
     *    一次误触就把同提案的投票、决议和任务结果一起撤销（3 条真实记录）。
     * 2. **任务结果的归属按「是哪一次提案把队伍送上任务的」判定，不按「车队像不像」判定**。
     *    同一任务里「提案 1 被否决 → 提案 2 用**完全相同的队伍**通过」是真实牌局常态，
     *    只看车队会把任务结果错算成提案 1 的依赖，凭空删掉一条真实记录。
     *
     * **后续任务一律不动**：任务结果只由胜负计数派生，撤销后续任务属于按位置截断（PRD 明令禁止）。
     */
    private fun proposalFindings(
        events: List<MatchEvent>,
        target: TeamProposed,
        replacement: MatchEvent
    ): Findings {
        val after = replacement as? TeamProposed
            ?: throw IllegalArgumentException(
                "提案编辑的替换事件必须是 TeamProposed，实际 ${replacement.type.name}"
            )

        // ① 内容没有任何变化（队长 + 队伍）：不存在「失去前提」的记录，也就不该有任何影响
        if (after.leaderSeat == target.leaderSeat &&
            after.teamSeats.sorted() == target.teamSeats.sorted()
        ) {
            return Findings(emptyList(), emptyList())
        }

        val questNo = target.questNo
        val proposalNo = target.proposalNo
        val affected = mutableListOf<AffectedEvent>()
        val manual = mutableListOf<String>()

        // 同提案的投票与决议：它们描述的就是「这支队伍这一次提案」，队伍/队长一变即失去前提
        events.forEach { event ->
            when {
                event is TeamVoteRecorded &&
                    event.questNo == questNo && event.proposalNo == proposalNo -> {
                    affected += AffectedEvent(
                        eventId = event.eventId,
                        questNo = questNo,
                        eventType = event.type,
                        reason = "第 $questNo 任务第 $proposalNo 次提案的队伍/队长已修改，" +
                            "针对旧队伍的投票不再成立",
                        operation = ImpactOperation.RETRACT,
                        proposalNo = proposalNo
                    )
                }

                event is TeamProposalResolved &&
                    event.questNo == questNo && event.proposalNo == proposalNo -> {
                    affected += AffectedEvent(
                        eventId = event.eventId,
                        questNo = questNo,
                        eventType = event.type,
                        reason = "第 $questNo 任务第 $proposalNo 次提案的队伍/队长已修改，" +
                            "基于旧队伍的通过/否决是另一种现场事实",
                        operation = ImpactOperation.RETRACT,
                        proposalNo = proposalNo
                    )
                }
            }
        }

        // ② 任务结果归属于谁：本任务**最后一个 active 的通过决议**就是执行那次任务的人
        val approvingResolution = events
            .filterIsInstance<TeamProposalResolved>()
            .filter { it.questNo == questNo && it.decision == ProposalDecision.APPROVED }
            .maxByOrNull { it.occurredOrder }

        // 被改提案自己的决议（可能是 REJECTED，也可能是 null = 还没录决议）
        val ownResolution = events.firstOrNull {
            it is TeamProposalResolved && it.questNo == questNo && it.proposalNo == proposalNo
        } as? TeamProposalResolved

        events.filterIsInstance<MissionResolved>()
            .filter { it.questNo == questNo }
            .forEach { mission ->
                when {
                    // 有明确的执行者 → 任务结果只归属于它，与本次修改无关就一个字都不说
                    approvingResolution != null ->
                        if (approvingResolution.proposalNo == proposalNo) {
                            affected += AffectedEvent(
                                eventId = mission.eventId,
                                questNo = questNo,
                                eventType = mission.type,
                                reason = "第 $questNo 任务的任务结果来自本次被修改的提案" +
                                    "（第 $proposalNo 次提案当时为通过），队伍已改，结果不再对应这支队",
                                operation = ImpactOperation.RETRACT,
                                proposalNo = proposalNo
                            )
                        }

                    // 本提案自己就被否决过 → 它从未把任何队伍送上任务，不得撤销任务结果
                    ownResolution?.decision == ProposalDecision.REJECTED -> Unit

                    // 本任务没有任何通过决议 → 归属无法证明，只提示、不动手
                    else -> {
                        affected += AffectedEvent(
                            eventId = mission.eventId,
                            questNo = questNo,
                            eventType = mission.type,
                            reason = "第 $questNo 任务既有任务结果，却没有通过决议指明是哪一次提案执行的，" +
                                "无法判定是否依赖本次修改",
                            operation = ImpactOperation.REVIEW_ONLY,
                            proposalNo = proposalNo
                        )
                        manual += "第 $questNo 任务的任务结果依赖关系无法判定（该任务没有通过决议）：" +
                            "请先补录/核对任务结果出自哪一次提案，再进行会撤销记录的历史修改"
                    }
                }
            }

        return Findings(affected, manual)
    }

    /**
     * 改投票：**只修订投票**，绝不自动改决议（B35 / B37）。
     *
     * 判定方式是「用规则引擎重放替换后的票数」，而不是在这里另写一份投票口径：
     * `AvalonReducer` 已经会在票数与已记录决议不一致时产出
     * `DECISION_VOTE_CONFLICT` / `VOTE_DECISION_UNDECIDABLE`，本方法只负责把这些信号翻译成
     * `REVIEW_ONLY` 条目 + 可读理由。规则永远只有一份。
     */
    private fun voteFindings(
        bundle: MatchBundle,
        target: TeamVoteRecorded,
        replacement: MatchEvent
    ): Findings {
        val questNo = target.questNo
        val proposalNo = target.proposalNo
        val resolution = bundle.events.firstOrNull {
            it is TeamProposalResolved && it.questNo == questNo && it.proposalNo == proposalNo
        } as? TeamProposalResolved
            ?: return Findings(emptyList(), emptyList())

        val simulated = bundle.toReconstructedMatchWith(target, replacement)
        val replayed = try {
            ReplayEngine(reducer).replay(simulated, upTo = resolution.occurredOrder)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return Findings(
                affected = listOf(
                    AffectedEvent(
                        eventId = resolution.eventId,
                        questNo = questNo,
                        eventType = resolution.type,
                        reason = "无法重算修改后的票数，同轮决议需要人工核对",
                        operation = ImpactOperation.REVIEW_ONLY,
                        proposalNo = proposalNo
                    )
                ),
                manualReviewReasons = listOf(
                    "无法用替换后的票数重算第 $questNo 任务第 $proposalNo 次提案的决议" +
                        "（${failure::class.java.simpleName}），请人工核对"
                )
            )
        }

        val conflicts = replayed.issues.filter { issue ->
            issue.eventId == resolution.eventId &&
                (issue.code == IssueCode.DECISION_VOTE_CONFLICT ||
                    issue.code == IssueCode.VOTE_DECISION_UNDECIDABLE)
        }
        if (conflicts.isEmpty()) return Findings(emptyList(), emptyList())

        val reasons = conflicts.map { issue ->
            when (issue.code) {
                IssueCode.DECISION_VOTE_CONFLICT ->
                    "第 $questNo 任务第 $proposalNo 次提案：记录为" +
                        "${issue.args["recorded"] ?: resolution.decision.name}，" +
                        "按新票数应为 ${issue.args["computed"] ?: "无法判定"}；" +
                        "决议是不可编辑的现场事实，只能提示复核"

                else ->
                    "第 $questNo 任务第 $proposalNo 次提案：新票数不足以判定通过与否，" +
                        "已记录的${resolution.decision.name}无法核对"
            }
        }

        return Findings(
            affected = listOf(
                AffectedEvent(
                    eventId = resolution.eventId,
                    questNo = questNo,
                    eventType = resolution.type,
                    reason = reasons.first(),
                    operation = ImpactOperation.REVIEW_ONLY,
                    proposalNo = proposalNo
                )
            ),
            manualReviewReasons = reasons
        )
    }

    /**
     * 改任务结果：比较「修改前/修改后重放到该位置」的状态。
     *
     * **胜负计数变化本身不撤销任何事件**：计数是派生投影，重放会自动重算。
     * 只有当修改让对局在该结果处就进入终局（第三次失败 / 三次成功后进入刺杀 / 弃局），
     * 其后的任务性事件才真的**不再可能发生** —— 那才是失去前提。
     */
    private fun missionFindings(
        bundle: MatchBundle,
        target: MissionResolved,
        replacement: MatchEvent
    ): Findings {
        val position = target.occurredOrder
        val before = ReplayEngine(reducer).replay(bundle.toReconstructedMatch(), upTo = position)
        val after = ReplayEngine(reducer).replay(
            bundle.toReconstructedMatchWith(target, replacement),
            upTo = position
        )

        val beforeTerminal = isQuestTerminal(before.state.status)
        val afterTerminal = isQuestTerminal(after.state.status)

        if (!beforeTerminal && afterTerminal) {
            val winner = after.state.outcome?.winner?.name ?: after.state.status.name
            val affected = bundle.events
                .filter { it.occurredOrder > position && it.type in QUEST_STRUCTURAL_TYPES }
                .map { event ->
                    AffectedEvent(
                        eventId = event.eventId,
                        questNo = event.questNo,
                        eventType = event.type,
                        reason = "修改第 ${target.questNo} 任务结果后，对局在该结果处已终局（$winner），" +
                            "这条记录不再可能发生",
                        operation = ImpactOperation.RETRACT,
                        proposalNo = proposalNoOf(event)
                    )
                }
            return Findings(affected, emptyList())
        }

        if (beforeTerminal && !afterTerminal) {
            return Findings(
                affected = emptyList(),
                manualReviewReasons = listOf(
                    "修改第 ${target.questNo} 任务结果后，对局不再是终局状态，" +
                        "其后的记录会重新参与判定（本次不会撤销任何记录，请复核后续任务）"
                )
            )
        }

        // 终局性没变：胜负计数会由重放自动修正，无需重置任何记录
        return Findings(emptyList(), emptyList())
    }

    // ————————————————————————————————————————————————————————
    // 内部工具
    // ————————————————————————————————————————————————————————

    /** 一次计算的结果（内部结构，对外只暴露 [EditImpactPlan]）。 */
    private data class Findings(
        val affected: List<AffectedEvent>,
        val manualReviewReasons: List<String>
    )

    /** 令牌集合必须与快照**逐条对齐**：少一条、多一条、位置不符都说明预览已过期。 */
    private fun verifySnapshot(
        events: List<MatchEvent>,
        tokens: List<ActiveEventToken>
    ): String? {
        if (tokens.size != events.size) {
            return "快照令牌数量与有效事件不一致：令牌 ${tokens.size} 条，事件 ${events.size} 条"
        }
        val byId = tokens.groupBy { it.eventId }
        byId.entries.firstOrNull { it.value.size > 1 }?.let {
            return "快照令牌出现重复事件 id：${it.key.raw}"
        }
        events.forEach { event ->
            val token = byId[event.eventId]?.firstOrNull()
                ?: return "快照令牌缺少事件：${event.eventId.raw}"
            if (token.logicalPosition != event.occurredOrder) {
                return "快照令牌逻辑位置不符：${event.eventId.raw} " +
                    "令牌 ${token.logicalPosition} / 事件 ${event.occurredOrder}"
            }
            if (token.revisionSeq < 0) {
                return "快照令牌修订序号非法：${event.eventId.raw} revisionSeq=${token.revisionSeq}"
            }
        }
        return null
    }

    private fun MatchBundle.toReconstructedMatchWith(
        target: MatchEvent,
        replacement: MatchEvent
    ): ReconstructedMatch {
        val simulated = events.map { event ->
            if (event.eventId == target.eventId) {
                replacement.relocatedTo(target.occurredOrder)
            } else {
                event
            }
        }
        return ReconstructedMatch(
            matchId = summary.matchId,
            config = config,
            participants = participants,
            roleAssignments = roleAssignments,
            events = simulated,
            revealedAt = revealedAt
        )
    }

    /**
     * 把替换事件放到被替代事件的逻辑位置上。
     *
     * 领域事件的 `occurredOrder` 由仓储盖章（工厂一律填 0），重放前必须借用目标事件的位置，
     * 否则替换事件会被排到时间线最前面 —— 那会得出完全错误的影响结论。
     */
    private fun MatchEvent.relocatedTo(order: Int): MatchEvent = when (this) {
        is com.roundtrace.app.core.model.QuestStarted -> copy(occurredOrder = order)
        is TeamProposed -> copy(occurredOrder = order)
        is TeamVoteRecorded -> copy(occurredOrder = order)
        is TeamProposalResolved -> copy(occurredOrder = order)
        is MissionResolved -> copy(occurredOrder = order)
        is com.roundtrace.app.core.model.PlayerClaimed -> copy(occurredOrder = order)
        is com.roundtrace.app.core.model.PrivateNoteAdded -> copy(occurredOrder = order)
        is com.roundtrace.app.core.model.AssassinationRecorded -> copy(occurredOrder = order)
        is com.roundtrace.app.core.model.MatchAbandoned -> copy(occurredOrder = order)
        is com.roundtrace.app.core.model.MatchFinalized -> copy(occurredOrder = order)
    }

    private fun proposalNoOf(event: MatchEvent): Int? = when (event) {
        is TeamProposed -> event.proposalNo
        is TeamVoteRecorded -> event.proposalNo
        is TeamProposalResolved -> event.proposalNo
        is com.roundtrace.app.core.model.PlayerClaimed -> event.proposalNo
        else -> null
    }

    /** 对局在该位置是否已经「不会再开新任务」（终局或待刺杀）。 */
    private fun isQuestTerminal(status: MatchStatus): Boolean = when (status) {
        MatchStatus.FINISHED_GOOD,
        MatchStatus.FINISHED_EVIL,
        MatchStatus.ABANDONED,
        MatchStatus.AWAITING_ASSASSINATION -> true

        MatchStatus.DRAFT,
        MatchStatus.IN_PROGRESS -> false
    }

    companion object {

        /**
         * 可编辑事件白名单（B35 / B37：只有 3 类）。
         *
         * 定义放在领域层而不是 feature 层：**仓储也要用它做准入校验**，
         * 若只写在 UI 状态层，仓储就不得不复制一份，两份白名单迟早会漂移。
         * `feature/record/RecordUiState.kt` 的 `EDITABLE_EVENT_TYPES` 直接委托这里，
         * 因此「编辑入口判据」在全局仍然只有一处。
         */
        val EDITABLE_TYPES: Set<EventType> = setOf(
            EventType.TEAM_PROPOSED,
            EventType.TEAM_VOTE_RECORDED,
            EventType.MISSION_RESOLVED
        )

        private const val EDITABLE_TYPES_TEXT = "TEAM_PROPOSED / TEAM_VOTE_RECORDED / MISSION_RESOLVED"

        /**
         * 「任务性事件」：它们的存在以「对局还没结束」为前提。
         *
         * 刻意**不含** `PLAYER_CLAIMED` / `PRIVATE_NOTE_ADDED`（发言与笔记是已经发生的现场信息，
         * 终局后依然成立）、也不含刺杀与终局核对（它们恰恰发生在终局附近）。
         */
        private val QUEST_STRUCTURAL_TYPES: Set<EventType> = setOf(
            EventType.QUEST_STARTED,
            EventType.TEAM_PROPOSED,
            EventType.TEAM_VOTE_RECORDED,
            EventType.TEAM_PROPOSAL_RESOLVED,
            EventType.MISSION_RESOLVED
        )
    }
}

/**
 * 一条 active 事件的快照令牌（NX-06 并发校验的基本单位）。
 *
 * **必须从 Room 实体行读取**：领域 `MatchEvent` 没有 `logicalPosition` / `revisionSeq`，
 * 在 UI 层凭空构造令牌等于自欺——比对时两边都是假值，永远「相等」。
 *
 * @param logicalPosition 事件在时间线上的逻辑位置（修订不变）
 * @param revisionSeq 该位置被修订的次数（0 = 原始版本）
 */
data class ActiveEventToken(
    val eventId: EventId,
    val logicalPosition: Int,
    val revisionSeq: Int
)

/** 受影响事件的处理方式。 */
enum class ImpactOperation {

    /** 确实失去前提，确认后标记 `retracted = 1`（**不是**物理删除）。 */
    RETRACT,

    /** 只是提示人工复核：既不撤销也不改写。 */
    REVIEW_ONLY
}

/**
 * 一条受影响记录。
 *
 * @param questNo 所属任务（用于按任务分组展示）
 * @param proposalNo 所属提案（有则给出，便于按「任务 · 提案」分组）
 * @param reason 可读原因（直接展示给用户，不做二次拼装）
 */
data class AffectedEvent(
    val eventId: EventId,
    val questNo: Int?,
    val eventType: EventType,
    val reason: String,
    val operation: ImpactOperation,
    val proposalNo: Int? = null
)

/**
 * 一次历史修改的完整影响计划（UI 预览与仓储确认共用的**唯一**数据结构）。
 *
 * @param expectedActive 预览时所依据的全部 active 事件令牌；确认时必须与库内现状严格相等，
 *        否则说明「用户看到的不是现在的历史」——重新预览，零写入
 */
data class EditImpactPlan(
    val matchId: MatchId,
    val targetId: EventId,
    val replacement: MatchEvent,
    val expectedActive: List<ActiveEventToken>,
    val affected: List<AffectedEvent>,
    val manualReviewReasons: List<String>
) {

    /** 确认后会被标记撤销的事件（顺序稳定，按时间线位置）。 */
    val retractIds: List<EventId>
        get() = affected.filter { it.operation == ImpactOperation.RETRACT }.map { it.eventId }

    /** 是否具有破坏性（存在撤销项）。 */
    val isDestructive: Boolean
        get() = retractIds.isNotEmpty()

    /** 是否需要人工复核（存在无法自动判定的原因）。 */
    val requiresManualReview: Boolean
        get() = manualReviewReasons.isNotEmpty()

    /** 预计被重置的条数（UI 按钮文案用）。 */
    val retractCount: Int
        get() = retractIds.size

    /** 只提示、不撤销的条数。 */
    val reviewOnlyCount: Int
        get() = affected.count { it.operation == ImpactOperation.REVIEW_ONLY }
}

/** 计划计算结果：要么给出计划，要么给出**可读的**拒绝原因。 */
sealed interface EditPlanResult {

    data class Ready(val plan: EditImpactPlan) : EditPlanResult

    /** @param detail 直接可展示的中文说明（不做二次映射） */
    data class Rejected(val reason: EditPlanRejection, val detail: String) : EditPlanResult
}

/** 计划被拒绝的原因（稳定枚举，供 UI 分支与测试断言）。 */
enum class EditPlanRejection {

    /** 目标事件不在本局有效事件里（不存在 / 已被撤销 / 属于别的对局）。 */
    TARGET_NOT_FOUND,

    /** 类型不在白名单（B35/B37）。 */
    TARGET_NOT_EDITABLE,

    /** 修订策略不允许（类型/任务号/提案号不一致、自我替代、supersedes 冲突）。 */
    REVISION_NOT_ALLOWED,

    /** 快照令牌与当前有效事件不一致（预览已过期）。 */
    INCONSISTENT_SNAPSHOT,

    /** 替换事件的 id 与本局已有事件重复。 */
    DUPLICATE_EVENT_ID,

    /**
     * 业务字段一字未改（P2：审计链防空转）。
     *
     * 「打开编辑 → 什么都不改 → 直接保存」在 UI 上可达：`openEdit` 用原值预填，
     * 若不做业务等价比较，仓储会照常走 `reviseWithSlot` —— 旧版本被 retract、`revisionSeq + 1`、
     * `updatedAt` 变化，历史链平白多出一跳内容完全相同的新版本。
     *
     * 与 [TARGET_NOT_FOUND] 等「错误」不同，这是**正常的用户操作**：UI 应提示「内容未修改」
     * 并保持零写入，而不是报错。
     */
    NO_BUSINESS_CHANGE,

    /** 读取快照/计算过程中出现异常（仓储侧兜底，**不会**被当成「无影响」）。 */
    PLAN_FAILED
}
