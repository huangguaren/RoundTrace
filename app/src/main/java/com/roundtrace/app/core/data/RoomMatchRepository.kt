package com.roundtrace.app.core.data

import androidx.room.withTransaction
import com.roundtrace.app.core.database.EventCodec
import com.roundtrace.app.core.database.Mapping
import com.roundtrace.app.core.database.RoundTraceDatabase
import com.roundtrace.app.core.database.entity.MatchDraftEntity
import com.roundtrace.app.core.database.entity.MatchEntity
import com.roundtrace.app.core.database.entity.MatchEventEntity
import com.roundtrace.app.core.database.entity.ParticipantEntity
import com.roundtrace.app.core.database.entity.RoleAssignmentEntity
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventType
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.MatchSummary
import com.roundtrace.app.core.model.Participant
import com.roundtrace.app.core.model.RevealState
import com.roundtrace.app.core.model.RoleAssignment
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.RoleSource
import com.roundtrace.app.core.model.SeatId
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.replay.ReplayEngine
import com.roundtrace.app.core.rules.GameReducer
import com.roundtrace.app.core.rules.GameRulesRegistry
import com.roundtrace.app.core.util.IdGenerator
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Room 实现的 [MatchRepository]（ARCHITECTURE §8.8 / TASKS.md T35）。
 *
 * 六个关键行为（逐条对应验收标准）：
 * 1. `createMatch` 在**单事务**内写 `matches` + `participants` + `role_assignments`；
 * 2. `appendEvent` 分配 order、写事件、touch 对局，并返回 `validateEvent` 的 issues；
 * 3. `appendEvent` 在 `TeamProposed` 且该任务无 `QUEST_STARTED` 时**同事务自动补建**
 *    （ARCHITECTURE §9.3-18）；
 * 4. `reviseEvent` 先用 [com.roundtrace.app.core.data.EventRevisionPolicy] 校验，再双写；
 * 5. `deleteMatch` 依赖 FK CASCADE，一条 DELETE 完成；
 * 6. 全程 `suspend`，**无 `runBlocking`、无主线程 IO**。
 *
 * 关于 `occurredOrder`：领域层造的事件恒为 `0`，本类在事务内用
 * `COALESCE(MAX(occurredOrder),0)+1` 取值后**通过 [EventCodec.encode] 的 `occurredOrder` 参数落列**，
 * 从而保证「事件的游戏内顺序」只由仓储盖章，不会被领域层误填。
 */
class RoomMatchRepository(
    private val database: RoundTraceDatabase,
    private val gameRulesRegistry: GameRulesRegistry,
    private val reducer: GameReducer,
    private val codec: EventCodec,
    private val idGenerator: IdGenerator,
    private val clock: Clock
) : MatchRepository {

    private val matchDao = database.matchDao()
    private val participantDao = database.participantDao()
    private val roleDao = database.roleAssignmentDao()
    private val eventDao = database.matchEventDao()
    private val draftDao = database.matchDraftDao()

    private val now: Long
        get() = clock.millis()

    // ————————————————————————————————————————————————————————
    // 读
    // ————————————————————————————————————————————————————————

    override fun observeSummaries(filter: MatchStatusFilter): Flow<List<MatchSummary>> =
        matchDao.observeSummaries(filter.statuses())
            .map { rows -> rows.map(Mapping::toSummary) }

    override fun observeBundle(matchId: MatchId): Flow<MatchBundle?> = combine(
        matchDao.observeById(matchId.raw),
        participantDao.observeByMatch(matchId.raw),
        roleDao.observeByMatch(matchId.raw),
        eventDao.observeActive(matchId.raw)
    ) { matchEntity, participants, roles, eventEntities ->
        if (matchEntity == null) {
            null
        } else {
            buildBundle(matchEntity, participants, roles, eventEntities)
        }
    }

    override suspend fun getBundle(matchId: MatchId): MatchBundle? {
        val matchEntity = matchDao.findById(matchId.raw) ?: return null
        return buildBundle(
            matchEntity = matchEntity,
            participants = participantDao.listByMatch(matchId.raw),
            roles = roleDao.listByMatch(matchId.raw),
            eventEntities = eventDao.listActive(matchId.raw)
        )
    }

    // ————————————————————————————————————————————————————————
    // 创建
    // ————————————————————————————————————————————————————————

    override suspend fun createMatch(draft: MatchCreationDraft): MatchId {
        val matchId = MatchId(idGenerator.newId())
        val createdAt = now
        val config = draft.toGameConfig(createdAtEpochMs = createdAt)
        val matchEntity = Mapping.toMatchEntity(matchId = matchId, config = config, now = createdAt)

        // 参与者：草稿没给座位就按人数补齐；座位必须 1..playerCount
        val participants = draft.participants
            .filter { it.seat in 1..draft.playerCount }
            .ifEmpty { MatchCreationDraft.defaultParticipants(draft.playerCount) }

        val participantEntities = participants.map {
            Mapping.toParticipantEntity(
                participantId = idGenerator.newId(),
                matchId = matchId,
                participant = it
            )
        }

        // 每个座位先建一条「角色未知」的真实身份记录，供后续揭晓时原地更新
        val roleEntities = (1..draft.playerCount).map { seat ->
            Mapping.toRoleEntity(
                assignmentId = idGenerator.newId(),
                matchId = matchId,
                assignment = RoleAssignment(
                    seat = seat,
                    roleId = RoleId.UNKNOWN,
                    source = RoleSource.PRESET_MANUAL,
                    revealState = RevealState.HIDDEN
                )
            )
        }

        database.withTransaction {
            matchDao.insert(matchEntity)
            if (participantEntities.isNotEmpty()) participantDao.upsertAll(participantEntities)
            if (roleEntities.isNotEmpty()) roleDao.upsertAll(roleEntities)
        }
        return matchId
    }

    // ————————————————————————————————————————————————————————
    // 写事件
    // ————————————————————————————————————————————————————————

    override suspend fun appendEvent(matchId: MatchId, event: MatchEvent): AppendResult {
        val bundle = getBundle(matchId)
            ?: return failed(matchId, RepositoryError.NOT_FOUND)

        val rules = gameRulesRegistry.rulesFor(bundle.config)
        val state = ReplayEngine(reducer).replay(bundle.toReconstructedMatch()).state
        val issues = rules.validateEvent(state, event)

        val committedAt = now
        return database.withTransaction {
            var order: Int

            // §9.3-18：首次提交某任务的提案时自动补建 QUEST_STARTED（同事务）
            if (event is TeamProposed && !hasQuestStarted(bundle.events, event.questNo)) {
                val auto = com.roundtrace.app.core.model.QuestStarted(
                    eventId = EventId(idGenerator.newId()),
                    occurredOrder = 0,
                    recordedAt = committedAt,
                    questNo = event.questNo,
                    leaderSeat = event.leaderSeat
                )
                eventDao.appendWithOrder(
                    matchId = matchId.raw,
                    entityProvider = { assigned ->
                        codec.encode(auto, matchId, assigned)
                    },
                    now = committedAt
                )
            }

            order = eventDao.appendWithOrder(
                matchId = matchId.raw,
                entityProvider = { assigned -> codec.encode(event, matchId, assigned) },
                now = committedAt
            )

            // 重放全部事件（含刚写入的），把终局状态回写到 matches.status
            val newBundle = getBundle(matchId)
            if (newBundle != null) {
                val newState = ReplayEngine(reducer).replay(newBundle.toReconstructedMatch()).state
                val currentStatus = matchDao.findById(matchId.raw)?.status
                val newStatusName = newState.status.name
                if (currentStatus != newStatusName) {
                    matchDao.updateStatus(matchId.raw, newStatusName, null, committedAt)
                }
            }

            AppendResult(
                matchId = matchId,
                eventId = event.eventId,
                occurredOrder = order,
                issues = issues
            )
        }
    }

    override suspend fun reviseEvent(
        matchId: MatchId,
        targetEventId: EventId,
        replacement: MatchEvent
    ): AppendResult {
        val bundle = getBundle(matchId)
            ?: return failed(matchId, RepositoryError.NOT_FOUND)

        val targetEntity = eventDao.findById(targetEventId.raw)
            ?: return failed(matchId, RepositoryError.INVALID_SUPERSEDE_TARGET)

        val target = try {
            codec.decode(targetEntity)
        } catch (e: IllegalStateException) {
            return failed(matchId, RepositoryError.WRITE_FAILED)
        }

        val check = EventRevisionPolicy.check(target, replacement, sameMatch = true)
        if (!check.allowed) {
            return failed(matchId, RepositoryError.INVALID_SUPERSEDE_TARGET)
        }

        val rules = gameRulesRegistry.rulesFor(bundle.config)
        val state = ReplayEngine(reducer).replay(bundle.toReconstructedMatch()).state
        val issues = rules.validateEvent(state, replacement)

        val committedAt = now
        return database.withTransaction {
            val order = eventDao.reviseSupersede(
                matchId = matchId.raw,
                newEntityProvider = { assigned ->
                    // 修订链：新事件的 supersedesId 指向被替代事件（列以本处为准）
                    codec.encode(replacement, matchId, assigned).copy(
                        supersedesId = targetEventId.raw
                    )
                },
                oldEventId = targetEventId.raw,
                now = committedAt
            )
            AppendResult(
                matchId = matchId,
                eventId = replacement.eventId,
                occurredOrder = order,
                issues = issues
            )
        }
    }

    override suspend fun retractEvent(matchId: MatchId, eventId: EventId): AppendResult {
        val existing = eventDao.findById(eventId.raw)
            ?: return failed(matchId, RepositoryError.NOT_FOUND)

        val committedAt = now
        database.withTransaction {
            eventDao.markRetracted(eventId.raw)
            eventDao.touchMatch(matchId.raw, committedAt)
        }
        return AppendResult(
            matchId = matchId,
            eventId = eventId,
            occurredOrder = existing.occurredOrder,
            issues = emptyList()
        )
    }

    // ————————————————————————————————————————————————————————
    // 角色 / 状态
    // ————————————————————————————————————————————————————————

    override suspend fun saveRoleAssignments(
        matchId: MatchId,
        assignments: List<RoleAssignment>
    ) {
        if (assignments.isEmpty()) return
        val entities = assignments.map { assignment ->
            val existing = roleDao.findBySeat(matchId.raw, assignment.seat)
            Mapping.toRoleEntity(
                assignmentId = existing?.id ?: idGenerator.newId(),
                matchId = matchId,
                assignment = assignment
            )
        }
        database.withTransaction {
            roleDao.upsertAll(entities)
            matchDao.touch(matchId.raw, now)
        }
    }

    override suspend fun revealAllRoles(matchId: MatchId): AppendResult {
        val entity = matchDao.findById(matchId.raw)
            ?: return failed(matchId, RepositoryError.NOT_FOUND)
        val committedAt = now
        database.withTransaction {
            roleDao.revealAll(matchId.raw)
            matchDao.updateStatus(entity.id, entity.status, committedAt, committedAt)
        }
        return AppendResult(matchId = matchId, eventId = null, occurredOrder = null)
    }

    override suspend fun setStatus(matchId: MatchId, status: MatchStatus, revealedAt: Long?) {
        val committedAt = now
        database.withTransaction {
            matchDao.updateStatus(matchId.raw, status.name, revealedAt, committedAt)
        }
    }

    override suspend fun renameMatch(matchId: MatchId, title: String) {
        val entity = matchDao.findById(matchId.raw) ?: return
        val config = Mapping.toConfig(entity).copy(title = title)
        database.withTransaction {
            matchDao.rename(
                id = matchId.raw,
                title = title,
                configJson = Mapping.encodeConfig(config),
                now = now
            )
        }
    }

    override suspend fun updateParticipants(
        matchId: MatchId,
        participants: List<com.roundtrace.app.core.model.Participant>
    ) {
        val existing = participantDao.listByMatch(matchId.raw)
        val bySeat = existing.associateBy { it.seatNumber }
        val updated = participants.map { p ->
            val old = bySeat[p.seat]
            Mapping.toParticipantEntity(
                participantId = old?.id ?: idGenerator.newId(),
                matchId = matchId,
                participant = p
            )
        }
        database.withTransaction {
            participantDao.upsertAll(updated)
            matchDao.touch(matchId.raw, now)
        }
    }

    override suspend fun deleteMatch(matchId: MatchId) {
        // 子表靠 FK CASCADE 清理，一条 DELETE 完成（T35⑤）
        database.withTransaction {
            matchDao.deleteById(matchId.raw)
        }
    }

    // ————————————————————————————————————————————————————————
    // 草稿
    // ————————————————————————————————————————————————————————

    override suspend fun saveDraft(matchId: MatchId, screenKey: String, json: String) {
        draftDao.upsert(
            MatchDraftEntity(
                matchId = matchId.raw,
                screenKey = screenKey,
                draftJson = json,
                updatedAt = now
            )
        )
    }

    override suspend fun loadDraft(matchId: MatchId, screenKey: String): String? =
        draftDao.load(matchId.raw, screenKey)

    override suspend fun clearDraft(matchId: MatchId, screenKey: String) {
        draftDao.clear(matchId.raw, screenKey)
    }

    // ————————————————————————————————————————————————————————
    // 导入
    // ————————————————————————————————————————————————————————

    override suspend fun importBundle(bundle: ImportBundle): MatchId {
        val newMatchId = MatchId(idGenerator.newId())
        val committedAt = now
        val config = bundle.config.copy(createdAtEpochMs = committedAt)
        val matchEntity = Mapping.toMatchEntity(
            matchId = newMatchId,
            config = config,
            now = committedAt,
            revealedAt = bundle.revealedAt
        )

        val participantEntities = bundle.participants.map {
            Mapping.toParticipantEntity(idGenerator.newId(), newMatchId, it)
        }
        val roleEntities = bundle.roleAssignments.map {
            Mapping.toRoleEntity(idGenerator.newId(), newMatchId, it)
        }

        // 事件 id / supersedesId / occurredOrder 全部重映射，绝不覆盖已有对局
        val oldToNewEventId = mutableMapOf<EventId, EventId>()
        bundle.events.sortedBy { it.occurredOrder }.forEach { event ->
            oldToNewEventId[event.eventId] = EventId(idGenerator.newId())
        }
        val eventEntities: List<MatchEventEntity> = bundle.events
            .sortedBy { it.occurredOrder }
            .mapIndexed { index, event ->
                val newId = oldToNewEventId.getValue(event.eventId)
                val newOrder = index + 1
                codec.encode(event, newMatchId, newOrder).copy(
                    id = newId.raw,
                    supersedesId = event.supersedesId?.let { oldToNewEventId[it]?.raw }
                )
            }

        database.withTransaction {
            matchDao.insert(matchEntity)
            if (participantEntities.isNotEmpty()) participantDao.upsertAll(participantEntities)
            if (roleEntities.isNotEmpty()) roleDao.upsertAll(roleEntities)
            if (eventEntities.isNotEmpty()) eventDao.insertAll(eventEntities)
        }
        return newMatchId
    }

    // ————————————————————————————————————————————————————————
    // 内部工具
    // ————————————————————————————————————————————————————————

    private fun failed(matchId: MatchId, error: RepositoryError): AppendResult =
        AppendResult(matchId = matchId, eventId = null, occurredOrder = null, error = error)

    private fun hasQuestStarted(events: List<MatchEvent>, questNo: Int): Boolean =
        events.any { it.type == EventType.QUEST_STARTED && it.questNo == questNo && !it.retracted }

    private fun buildBundle(
        matchEntity: MatchEntity,
        participants: List<ParticipantEntity>,
        roles: List<RoleAssignmentEntity>,
        eventEntities: List<MatchEventEntity>
    ): MatchBundle {
        val events = eventEntities
            .map { codec.decode(it) }
            .sortedBy { it.occurredOrder }

        val summary = MatchSummary(
            matchId = MatchId(matchEntity.id),
            title = matchEntity.title,
            playerCount = matchEntity.playerCount,
            status = Mapping.matchStatusOf(matchEntity.status),
            rulesetId = matchEntity.rulesetId,
            updatedAtEpochMs = matchEntity.updatedAt,
            questsStarted = events.count { it.type == EventType.QUEST_STARTED },
            mySeat = matchEntity.mySeat
        )

        return MatchBundle(
            summary = summary,
            config = Mapping.toConfig(matchEntity),
            participants = participants.map(Mapping::toParticipant),
            roleAssignments = roles.associate { it.seatNumber to Mapping.toRoleAssignment(it) },
            events = events,
            revealedAt = matchEntity.revealedAt
        )
    }
}
