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
    private val revealedAt: Long? = null,
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

    override fun observeSummaries(filter: MatchStatusFilter): Flow<List<MatchSummary>> =
        eventsState.map { listOf(buildSummary(it)) }

    override fun observeBundle(matchId: MatchId): Flow<MatchBundle?> =
        eventsState.map { buildBundle(it) }

    override suspend fun getBundle(matchId: MatchId): MatchBundle? =
        buildBundle(eventsState.value)

    override suspend fun createMatch(draft: MatchCreationDraft): MatchId = matchId

    override suspend fun appendEvent(matchId: MatchId, event: MatchEvent): AppendResult {
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
        eventsState.value = eventsState.value.map {
            if (it.eventId == targetEventId) retract(it) else it
        }
        return appendEvent(matchId, replacement)
    }

    override suspend fun retractEvent(matchId: MatchId, eventId: EventId): AppendResult {
        val target = eventsState.value.firstOrNull { it.eventId == eventId }
            ?: return AppendResult(matchId, null, null, error = null)
        eventsState.value = eventsState.value.map {
            if (it.eventId == eventId) retract(it) else it
        }
        return AppendResult(matchId, eventId, target.occurredOrder)
    }

    override suspend fun saveRoleAssignments(
        matchId: MatchId,
        assignments: List<RoleAssignment>
    ) {
        assignments.forEach { roleAssignments[it.seat] = it }
    }

    override suspend fun revealAllRoles(matchId: MatchId): AppendResult =
        AppendResult(matchId, null, null)

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
        drafts[screenKey] = json
        savedDrafts += json
    }

    override suspend fun loadDraft(matchId: MatchId, screenKey: String): String? =
        drafts[screenKey]

    override suspend fun clearDraft(matchId: MatchId, screenKey: String) {
        drafts.remove(screenKey)
    }

    override suspend fun importBundle(bundle: ImportBundle): MatchId = matchId

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
