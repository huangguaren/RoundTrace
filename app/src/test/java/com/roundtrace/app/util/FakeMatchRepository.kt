package com.roundtrace.app.util

import com.roundtrace.app.core.data.AppendResult
import com.roundtrace.app.core.data.ImportBundle
import com.roundtrace.app.core.data.MatchBundle
import com.roundtrace.app.core.data.MatchRepository
import com.roundtrace.app.core.data.MatchStatusFilter
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.GameConfig
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.MatchSummary
import com.roundtrace.app.core.model.Participant
import com.roundtrace.app.core.model.ReconstructedMatch
import com.roundtrace.app.core.model.RoleAssignment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * 内存版 [MatchRepository]（测试专用）。
 *
 * 刻意**模拟真实仓储的关键语义**：
 * 1. `appendEvent` 分配 `occurredOrder = max + 1`（与 SQL 的 `COALESCE(MAX,0)+1` 一致）；
 * 2. `retractEvent` 把事件标记为 `retracted`，读路径过滤掉；
 * 3. `observeBundle` 通过 `Flow` 发射，ViewModel 的派生链与生产一致。
 *
 * 它**不**模拟 Room 事务/外键——那部分由 `androidTest` 的 T43 覆盖。
 */
class FakeMatchRepository(
    private val config: GameConfig,
    initialEvents: List<MatchEvent> = emptyList(),
    private var revealedAt: Long? = null,
    initialRoles: Map<Int, RoleAssignment> = emptyMap()
) : MatchRepository {

    private val matchId = MatchId("fake-match")
    private val eventsState = MutableStateFlow(initialEvents.sortedBy { it.occurredOrder })
    private val roleAssignments = LinkedHashMap<Int, RoleAssignment>().apply {
        putAll(initialRoles)
    }
    private val drafts = concurrentMap<String, String>()

    /** 座位名的内存态（`updateParticipants` 写入，`buildBundle` 读出）。 */
    private val participantsBySeat = LinkedHashMap<Int, Participant>()
    private var title: String = config.title
    private var status: MatchStatus = MatchStatus.DRAFT

    /** 记录 `appendEvent` 分配过的 order，供测试断言「1..N 严格递增」。 */
    val assignedOrders: MutableList<Int> = mutableListOf()

    /** 保存草稿的调用记录。 */
    val savedDrafts: MutableList<String> = mutableListOf()

    /**
     * 直接塞一条草稿，**不**记入 [savedDrafts]。
     *
     * 用途：模拟「上一次会话留下的草稿」。若用 `saveDraft()` 播种，
     * `savedDrafts` 会凭空多一条，导致「恢复是否触发了保存」无法断言。
     */
    fun seedDraft(screenKey: String, json: String) {
        drafts[screenKey] = json
    }

    /**
     * 可选闸门：非空时 [loadDraft] 会挂起直到它完成。
     *
     * 用途：制造「读取草稿还没返回，用户已经开始输入」的竞态窗口，
     * 用来验证恢复**不会**覆盖用户输入。
     */
    var loadDraftGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** 置 true 后下一次 append/revise 返回 WRITE_FAILED（模拟写库失败，用于「失败保留输入」用例）。 */
    var failNextWrite: Boolean = false

    /**
     * 置 true 后下一次写入**返回** `AppendResult(error = WRITE_FAILED)`，**不抛异常**。
     *
     * 为什么需要它：真实仓储的错误模型是「返回错误值」而不是「抛异常」——
     * `RoomMatchRepository.retractEvent` 在 `markRetracted` 命中 0 行时 `return failed(...)`。
     * 旧 fake 只会抛异常，于是 `result.isSuccess` 永远等价于 `AppendResult.isSuccess`，
     * **掩盖了 D3（把仓库业务错误报成「已保存」）这类缺陷**。这里补齐真实语义。
     */
    var failNextWriteWithError: Boolean = false

    /**
     * 可选闸门：非空时 [saveDraft] 会挂起直到它完成。
     *
     * 用途：把「草稿自动保存占住 SAVING 窗口」这个瞬间**固定住**，
     * 用来验证此时点提交不会被静默吞掉（F1）。
     */
    var saveDraftGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override fun observeSummaries(filter: MatchStatusFilter): Flow<List<MatchSummary>> =
        eventsState.map { listOf(buildSummary(it)) }

    override fun observeBundle(matchId: MatchId): Flow<MatchBundle?> =
        eventsState.map { buildBundle(it) }

    override suspend fun getBundle(matchId: MatchId): MatchBundle? =
        buildBundle(eventsState.value)

    override suspend fun createMatch(draft: MatchCreationDraft): MatchId = matchId

    override suspend fun appendEvent(matchId: MatchId, event: MatchEvent): AppendResult {
        if (failNextWriteWithError) {
            failNextWriteWithError = false
            return AppendResult(
                matchId = matchId,
                eventId = event.eventId,
                occurredOrder = null,
                error = com.roundtrace.app.core.data.RepositoryError.WRITE_FAILED
            )
        }
        if (failNextWrite) {
            failNextWrite = false
            return AppendResult(
                matchId = matchId,
                eventId = event.eventId,
                occurredOrder = null,
                error = com.roundtrace.app.core.data.RepositoryError.WRITE_FAILED
            )
        }
        // 与 RoomMatchRepository 的行为对齐：首次提交某任务提案时自动补建 QUEST_STARTED
        // （ARCHITECTURE §9.3-18）。真实事务语义由 androidTest 覆盖。
        if (event is com.roundtrace.app.core.model.TeamProposed &&
            eventsState.value.none {
                it.type == com.roundtrace.app.core.model.EventType.QUEST_STARTED &&
                    it.questNo == event.questNo &&
                    !it.retracted
            }
        ) {
            appendInternal(
                com.roundtrace.app.core.model.QuestStarted(
                    eventId = EventId("auto-qs-${event.questNo}"),
                    occurredOrder = 0,
                    recordedAt = event.recordedAt,
                    questNo = event.questNo,
                    leaderSeat = event.leaderSeat
                )
            )
        }

        val order = appendInternal(event)
        return AppendResult(
            matchId = matchId,
            eventId = event.eventId,
            occurredOrder = order
        )
    }

    private fun appendInternal(event: MatchEvent): Int {
        val nextOrder = (eventsState.value.maxOfOrNull { it.occurredOrder } ?: 0) + 1
        assignedOrders += nextOrder
        val reissued = reorder(event, nextOrder)
        eventsState.value = (eventsState.value + reissued).sortedBy { it.occurredOrder }
        status = if (status == MatchStatus.DRAFT) MatchStatus.IN_PROGRESS else status
        return nextOrder
    }

    override suspend fun reviseEvent(
        matchId: MatchId,
        targetEventId: EventId,
        replacement: MatchEvent
    ): AppendResult {
        if (failNextWrite) {
            failNextWrite = false
            return AppendResult(
                matchId = matchId,
                eventId = replacement.eventId,
                occurredOrder = null,
                error = com.roundtrace.app.core.data.RepositoryError.WRITE_FAILED
            )
        }
        eventsState.value = eventsState.value.map {
            if (it.eventId == targetEventId) retract(it) else it
        }
        return appendEvent(matchId, replacement)
    }

    override suspend fun retractEvent(matchId: MatchId, eventId: EventId): AppendResult {
        // 真实仓储在 `markRetracted` 命中 0 行时**返回** failed(WRITE_FAILED)，不抛异常（D3 的病根）
        if (failNextWriteWithError) {
            failNextWriteWithError = false
            return AppendResult(
                matchId = matchId,
                eventId = eventId,
                occurredOrder = null,
                error = com.roundtrace.app.core.data.RepositoryError.WRITE_FAILED
            )
        }
        val target = eventsState.value.firstOrNull { it.eventId == eventId }
            // 与 RoomMatchRepository 对齐：目标不存在时返回 NOT_FOUND（而不是「空错误 = 成功」）
            ?: return AppendResult(
                matchId = matchId,
                eventId = eventId,
                occurredOrder = null,
                error = com.roundtrace.app.core.data.RepositoryError.NOT_FOUND
            )
        eventsState.value = eventsState.value.map {
            if (it.eventId == eventId) retract(it) else it
        }
        return AppendResult(matchId, eventId, target.occurredOrder)
    }

    override suspend fun retractAfterOrder(matchId: MatchId, logicalPosition: Int): AppendResult {
        if (failNextWrite) {
            failNextWrite = false
            return AppendResult(matchId, null, null, error = com.roundtrace.app.core.data.RepositoryError.WRITE_FAILED)
        }
        eventsState.value = eventsState.value.map {
            if (it.occurredOrder > logicalPosition) retract(it) else it
        }
        return AppendResult(matchId, null, null)
    }

    // ————————————————————————————————————————————————————————
    // NX-06：历史安全编辑（内存语义，与 RoomMatchRepository 对齐）
    // ————————————————————————————————————————————————————————

    /** 预览次数（断言「进入编辑态只算计划、不写库」）。 */
    var planCalls: Int = 0
        private set

    /** 确认次数（断言重复点击只执行一次）。 */
    var applyCalls: Int = 0
        private set

    /** 覆盖下一次确认的业务返回值（用于 STALE_PREVIEW / CONFLICT 分支）。 */
    var nextApplyResult: com.roundtrace.app.core.data.EditApplyResult? = null

    /** 可选闸门：非空时 [applyConfirmedEdit] 会挂起，用于复现「确认在飞时再点一次」。 */
    var applyGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    private val editPlanner = com.roundtrace.app.core.data.edit.EditImpactPlanner(
        com.roundtrace.app.core.rules.DefaultGameRulesRegistry().avalonReducer
    )

    override suspend fun planHistoricalEdit(
        matchId: MatchId,
        targetEventId: EventId,
        replacement: MatchEvent
    ): com.roundtrace.app.core.data.edit.EditPlanResult {
        planCalls += 1
        val bundle = buildBundle(eventsState.value)
        return editPlanner.plan(bundle, tokensOf(bundle), targetEventId, replacement)
    }

    override suspend fun applyConfirmedEdit(
        matchId: MatchId,
        targetEventId: EventId,
        replacement: MatchEvent,
        confirmedRetractIds: List<EventId>,
        expectedTokens: List<com.roundtrace.app.core.data.edit.ActiveEventToken>
    ): com.roundtrace.app.core.data.EditApplyResult {
        applyCalls += 1
        applyGate?.await()
        nextApplyResult?.let { override ->
            nextApplyResult = null
            return override
        }
        if (failNextWrite) {
            failNextWrite = false
            return com.roundtrace.app.core.data.EditApplyResult.Failed(
                com.roundtrace.app.core.data.RepositoryError.WRITE_FAILED
            )
        }

        val bundle = buildBundle(eventsState.value)
        val tokens = tokensOf(bundle)
        val planned = editPlanner.plan(bundle, tokens, targetEventId, replacement)
        val plan = (planned as? com.roundtrace.app.core.data.edit.EditPlanResult.Ready)?.plan
            ?: return com.roundtrace.app.core.data.EditApplyResult.Failed(
                com.roundtrace.app.core.data.RepositoryError.INVALID_SUPERSEDE_TARGET
            )

        if (tokens != expectedTokens) {
            return com.roundtrace.app.core.data.EditApplyResult.StalePreview(plan)
        }
        if (plan.retractIds.toSet() != confirmedRetractIds.toSet()) {
            return com.roundtrace.app.core.data.EditApplyResult.StalePreview(plan)
        }
        if (plan.requiresManualReview && plan.isDestructive) {
            return com.roundtrace.app.core.data.EditApplyResult.Conflict(plan.manualReviewReasons)
        }

        val target = bundle.events.firstOrNull { it.eventId == targetEventId }
            ?: return com.roundtrace.app.core.data.EditApplyResult.Failed(
                com.roundtrace.app.core.data.RepositoryError.INVALID_SUPERSEDE_TARGET
            )

        // 与 reviseWithSlot 对齐：旧版本 retracted = 1，新版本**继承原逻辑位置**（这里用 occurredOrder 表示）
        val retractSet = plan.retractIds.toSet()
        val next = eventsState.value
            .map { event ->
                when {
                    event.eventId == target.eventId -> retract(event)
                    event.eventId in retractSet -> retract(event)
                    else -> event
                }
            }
            .plus(reorder(replacement, target.occurredOrder))
            .sortedBy { it.occurredOrder }
        eventsState.value = next
        status = if (status == MatchStatus.DRAFT) MatchStatus.IN_PROGRESS else status
        return com.roundtrace.app.core.data.EditApplyResult.Success(replacement.eventId)
    }

    /** 内存版令牌：`logicalPosition` 用 `occurredOrder` 表示，修订序号恒为 0（fake 不建修订链）。 */
    private fun tokensOf(bundle: MatchBundle): List<com.roundtrace.app.core.data.edit.ActiveEventToken> =
        bundle.events.map {
            com.roundtrace.app.core.data.edit.ActiveEventToken(
                eventId = it.eventId,
                logicalPosition = it.occurredOrder,
                revisionSeq = 0
            )
        }

    override suspend fun saveRoleAssignments(
        matchId: MatchId,
        assignments: List<RoleAssignment>
    ) {
        assignments.forEach { roleAssignments[it.seat] = it }
    }

    override suspend fun revealAllRoles(matchId: MatchId): AppendResult = revealMatch(matchId, emptyList())

    override suspend fun revealMatch(matchId: MatchId, assignments: List<RoleAssignment>): AppendResult {
        if (failNextWrite) {
            failNextWrite = false
            return AppendResult(matchId, null, null, error = com.roundtrace.app.core.data.RepositoryError.WRITE_FAILED)
        }
        assignments.forEach { roleAssignments[it.seat] = it }
        roleAssignments.replaceAll { _, role -> role.copy(revealState = com.roundtrace.app.core.model.RevealState.REVEALED) }
        if (revealedAt == null) revealedAt = 1_700_000_000_000L
        return AppendResult(matchId, null, null)
    }

    override suspend fun setStatus(matchId: MatchId, status: MatchStatus, revealedAt: Long?) {
        this.status = status
    }

    override suspend fun renameMatch(matchId: MatchId, title: String) {
        this.title = title
    }

    override suspend fun deleteMatch(matchId: MatchId) {
        eventsState.value = emptyList()
    }

    /** 对局进行中更新座位名/头像/坐标（内存语义，与 RoomMatchRepository.updateParticipants 对齐）。 */
    override suspend fun updateParticipants(
        matchId: MatchId,
        participants: List<Participant>
    ) {
        participants.forEach { participantsBySeat[it.seat] = it }
    }

    override suspend fun saveDraft(matchId: MatchId, screenKey: String, json: String) {
        saveDraftGate?.await()
        drafts[screenKey] = json
        savedDrafts += json
    }

    override suspend fun loadDraft(matchId: MatchId, screenKey: String): String? {
        loadDraftGate?.await()
        return drafts[screenKey]
    }

    override suspend fun clearDraft(matchId: MatchId, screenKey: String) {
        drafts.remove(screenKey)
    }

    override suspend fun importBundle(bundle: ImportBundle): MatchId = matchId

    /** 单局假仓储：按当前事件重算一次 status（对齐生产的 recomputeAllProjections 语义）。 */
    override suspend fun recomputeAllProjections(): com.roundtrace.app.core.data.ProjectionRefreshResult {
        val derived = com.roundtrace.app.core.replay.ReplayEngine(
            com.roundtrace.app.core.rules.DefaultGameRulesRegistry().avalonReducer
        ).replay(buildBundle(eventsState.value).toReconstructedMatch()).state.status
        status = derived
        return com.roundtrace.app.core.data.ProjectionRefreshResult(
            processed = 1,
            failed = emptyList()
        )
    }

    // ————————————————————————————————————————————————————————

    private fun buildBundle(events: List<MatchEvent>): MatchBundle {
        val active = events.filter { !it.retracted }.sortedBy { it.occurredOrder }
        return MatchBundle(
            summary = buildSummary(events),
            config = config,
            participants = (1..config.playerCount).map {
                participantsBySeat[it] ?: Participant(seat = it)
            },
            roleAssignments = roleAssignments.toMap(),
            events = active,
            revealedAt = revealedAt
        )
    }

    private fun buildSummary(events: List<MatchEvent>): MatchSummary = MatchSummary(
        matchId = matchId,
        title = title,
        playerCount = config.playerCount,
        status = status,
        rulesetId = config.rulesetId,
        updatedAtEpochMs = 0L,
        questsStarted = events.count {
            !it.retracted && it.type == com.roundtrace.app.core.model.EventType.QUEST_STARTED
        },
        mySeat = config.mySeat
    )

    private fun reorder(event: MatchEvent, order: Int): MatchEvent = when (event) {
        is com.roundtrace.app.core.model.QuestStarted -> event.copy(occurredOrder = order)
        is com.roundtrace.app.core.model.TeamProposed -> event.copy(occurredOrder = order)
        is com.roundtrace.app.core.model.TeamVoteRecorded -> event.copy(occurredOrder = order)
        is com.roundtrace.app.core.model.TeamProposalResolved -> event.copy(occurredOrder = order)
        is com.roundtrace.app.core.model.MissionResolved -> event.copy(occurredOrder = order)
        is com.roundtrace.app.core.model.PlayerClaimed -> event.copy(occurredOrder = order)
        is com.roundtrace.app.core.model.PrivateNoteAdded -> event.copy(occurredOrder = order)
        is com.roundtrace.app.core.model.AssassinationRecorded -> event.copy(occurredOrder = order)
        is com.roundtrace.app.core.model.MatchAbandoned -> event.copy(occurredOrder = order)
        is com.roundtrace.app.core.model.MatchFinalized -> event.copy(occurredOrder = order)
    }

    private fun retract(event: MatchEvent): MatchEvent = when (event) {
        is com.roundtrace.app.core.model.QuestStarted -> event.copy(retracted = true)
        is com.roundtrace.app.core.model.TeamProposed -> event.copy(retracted = true)
        is com.roundtrace.app.core.model.TeamVoteRecorded -> event.copy(retracted = true)
        is com.roundtrace.app.core.model.TeamProposalResolved -> event.copy(retracted = true)
        is com.roundtrace.app.core.model.MissionResolved -> event.copy(retracted = true)
        is com.roundtrace.app.core.model.PlayerClaimed -> event.copy(retracted = true)
        is com.roundtrace.app.core.model.PrivateNoteAdded -> event.copy(retracted = true)
        is com.roundtrace.app.core.model.AssassinationRecorded -> event.copy(retracted = true)
        is com.roundtrace.app.core.model.MatchAbandoned -> event.copy(retracted = true)
        is com.roundtrace.app.core.model.MatchFinalized -> event.copy(retracted = true)
    }

    private fun <K, V> concurrentMap(): MutableMap<K, V> = LinkedHashMap()

    /** 便捷：当前快照（供断言）。 */
    fun bundle(): MatchBundle? = buildBundle(eventsState.value)

    /** 便捷：重建为领域输入。 */
    fun reconstructed(): ReconstructedMatch? = bundle()?.toReconstructedMatch()

    /** 便捷：空 Flow（某些测试只需要一个不发射的实现）。 */
    fun emptyFlow(): Flow<Unit> = flowOf(Unit)
}
