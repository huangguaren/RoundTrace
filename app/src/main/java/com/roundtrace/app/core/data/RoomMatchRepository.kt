package com.roundtrace.app.core.data

import androidx.room.withTransaction
import com.roundtrace.app.BuildConfig
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
/**
 * 修订冲突（内部用）。
 *
 * 继承 [IllegalStateException] 而不是自定义基类：它表达的是「当前状态不允许这次操作」，
 * 与 `reviseWithSlot` 里 `check(...)` 抛出的异常同族，便于统一捕获。
 * **刻意不捕获 `CancellationException`**（它不是 IllegalStateException），
 * 协程取消必须能正常传播。
 */
private class RevisionConflictException(message: String) : IllegalStateException(message)

/** 与 `RoundTraceDatabase` 的自检/备份共用一个 tag，方便抓一次完整启动日志。 */
private const val DB_LOG_TAG = "RoundTraceDb"

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
            // §9.3-18：首次提交某任务的提案时自动补建 QUEST_STARTED（同事务）
            if (event is TeamProposed && !hasQuestStarted(bundle.events, event.questNo)) {
                val auto = com.roundtrace.app.core.model.QuestStarted(
                    eventId = EventId(idGenerator.newId()),
                    occurredOrder = 0,
                    recordedAt = committedAt,
                    questNo = event.questNo,
                    leaderSeat = event.leaderSeat
                )
                eventDao.appendWithSlot(
                    matchId = matchId.raw,
                    entityProvider = { slot ->
                        codec.encode(auto, matchId, slot.occurredOrder, slot.logicalPosition, slot.revisionSeq)
                    },
                    now = committedAt
                )
            }

            val slot = eventDao.appendWithSlot(
                matchId = matchId.raw,
                entityProvider = { s ->
                    codec.encode(event, matchId, s.occurredOrder, s.logicalPosition, s.revisionSeq)
                },
                now = committedAt
            )

            // 重放全部事件（含刚写入的），把派生状态回写到 matches.status（RT-004）
            recomputeProjectionLocked(matchId)

            AppendResult(
                matchId = matchId,
                eventId = event.eventId,
                occurredOrder = slot.occurredOrder,
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
        // P0 RT-002: 校验事件属于指定对局
        if (targetEntity.matchId != matchId.raw) {
            return failed(matchId, RepositoryError.INVALID_SUPERSEDE_TARGET)
        }
        // C-1：目标必须**仍然有效**（未被撤销）。这里只是快速失败，真正的防线在事务内。
        if (targetEntity.retracted) {
            return failed(matchId, RepositoryError.INVALID_SUPERSEDE_TARGET)
        }

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
        return try {
            database.withTransaction {
                // —— 事务内复核（C-1 的核心）——
                // 上面的检查都在事务外：两个请求可能同时通过，都认为目标有效。
                // 进入事务后再确认一次「目标仍存在、属于本局、未撤销、类型未变」，
                // 任何一条不满足就抛异常 → 事务回滚 → **一行都不会写入**。
                val fresh = eventDao.findActiveById(matchId.raw, targetEventId.raw)
                    ?: throw RevisionConflictException(
                        "修订目标已失效（可能被并发修订先行撤销）：eventId=${targetEventId.raw}"
                    )
                if (fresh.eventType != targetEntity.eventType) {
                    throw RevisionConflictException(
                        "修订目标的事件类型已变更：期望 ${targetEntity.eventType}，实际 ${fresh.eventType}"
                    )
                }

                val slot = eventDao.reviseWithSlot(
                    matchId = matchId.raw,
                    oldEventId = targetEventId.raw,
                    entityProvider = { s ->
                        // 修订链：新事件的 supersedesId 指向被替代事件（列以本处为准）
                        codec.encode(
                            replacement,
                            matchId,
                            s.occurredOrder,
                            s.logicalPosition,
                            s.revisionSeq
                        ).copy(supersedesId = targetEventId.raw)
                    },
                    now = committedAt
                )
                // 修订会改变历史事实，状态必须跟着重算（RT-004）
                recomputeProjectionLocked(matchId)
                AppendResult(
                    matchId = matchId,
                    eventId = replacement.eventId,
                    occurredOrder = slot.occurredOrder,
                    issues = issues
                )
            }
        } catch (conflict: RevisionConflictException) {
            // 并发冲突要变成**可理解的错误返回值**，不要把异常抛到 UI
            android.util.Log.w(DB_LOG_TAG, "修订冲突：${conflict.message}")
            failed(matchId, RepositoryError.INVALID_SUPERSEDE_TARGET)
        } catch (rollback: IllegalStateException) {
            // reviseWithSlot 内部的 check 失败（撤销命中 0 行）也会走到这里
            android.util.Log.w(DB_LOG_TAG, "修订被回滚：${rollback.message}")
            failed(matchId, RepositoryError.INVALID_SUPERSEDE_TARGET)
        }
    }

    override suspend fun retractEvent(matchId: MatchId, eventId: EventId): AppendResult {
        val existing = eventDao.findById(eventId.raw)
            ?: return failed(matchId, RepositoryError.NOT_FOUND)
        // P0 RT-002: 校验事件属于指定对局
        if (existing.matchId != matchId.raw) {
            return failed(matchId, RepositoryError.NOT_FOUND)
        }

        val committedAt = now
        val affected = database.withTransaction {
            val rows = eventDao.markRetracted(matchId.raw, eventId.raw)
            if (rows == 1) {
                eventDao.touchMatch(matchId.raw, committedAt)
                // 撤销会改变历史事实，状态必须跟着重算（RT-004）
                recomputeProjectionLocked(matchId)
            }
            rows
        }
        if (affected != 1) {
            // DAO 层的对局归属校验没通过（理论上前置校验已拦住，这里是第二道闸门）
            return failed(matchId, RepositoryError.WRITE_FAILED)
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
            // 导入不能停在 DRAFT：状态必须由重放结果决定（RT-004）
            recomputeProjectionLocked(newMatchId)
        }
        return newMatchId
    }

    // ————————————————————————————————————————————————————————
    // 一次性投影刷新（RT-003 Step 3）
    // ————————————————————————————————————————————————————————

    /**
     * 逐局重算 `matches.status`，复用 [recomputeProjectionLocked] 的重放逻辑。
     *
     * ## 失败处理（重要取舍）
     *
     * 刻意**不分事务包住所有对局**：单局失败不应让整批回滚，否则永远刷不完。
     * 但**失败必须被上报**：调用方只有在 [ProjectionRefreshResult.isComplete] 时才写版本标记，
     * 有失败就下次启动重扫。
     *
     * 代价（有意接受）：**只要存在一局永久损坏的数据，标记就永远写不上，每次启动都会全量重扫。**
     * 这是「宁可重复扫描，不可静默错误」的选择——重扫是纯派生计算、幂等、不改 `updatedAt`，
     * 对局数在几十级别时成本可忽略；而静默停在旧结论是用户永远发现不了的错误。
     */
    override suspend fun recomputeAllProjections(): ProjectionRefreshResult {
        val ids = matchDao.listAllIds()
        var processed = 0
        val failed = mutableListOf<MatchId>()

        ids.forEach { rawId ->
            val matchId = MatchId(rawId)
            runCatching { recomputeProjectionLocked(matchId, touchUpdatedAt = false) }
                .onSuccess { processed++ }
                .onFailure { throwable ->
                    // 记录到具体对局 + 异常类型，便于定位「哪一局的数据坏了」
                    failed += matchId
                    android.util.Log.w(
                        DB_LOG_TAG,
                        "投影刷新失败：matchId=$rawId, 异常=${throwable::class.java.simpleName}",
                        throwable
                    )
                }
        }

        if (failed.isNotEmpty()) {
            android.util.Log.w(
                DB_LOG_TAG,
                "投影刷新未完成：成功 $processed / 失败 ${failed.size}；" +
                    "不写版本标记，下次启动重试。失败对局=${failed.map { it.raw }}"
            )
        }
        return ProjectionRefreshResult(processed = processed, failed = failed)
    }

    // ————————————————————————————————————————————————————————
    // 内部工具
    // ————————————————————————————————————————————————————————

    /**
     * 把 `matches.status` 重新对齐到「事件重放派生状态」（RT-004）。
     *
     * 为什么必须统一：状态有四个写入点（追加 / 修订 / 撤销 / 导入），
     * 任何一个漏掉都会让 `matches.status` 与事件事实脱节——
     * 首页筛选、上帝视角开关、导出状态全部读这一列，脱节即表现为
     * 「撤销了失败任务但首页仍显示已结束」。
     *
     * 硬约束：
     * - **必须在事务内调用**，与触发它的写操作同生共死；
     * - 必须用 `updateStatusOnly`，**不得**写 `revealedAt`
     *   （否则会把已揭晓的对局打回未揭晓 —— RT-001 的老问题）。
     */
    private suspend fun recomputeProjectionLocked(
        matchId: MatchId,
        touchUpdatedAt: Boolean = true
    ) {
        val bundle = getBundle(matchId) ?: return
        val derived = ReplayEngine(reducer).replay(bundle.toReconstructedMatch()).state.status
        if (touchUpdatedAt) {
            matchDao.updateStatusOnly(matchId.raw, derived.name, now)
        } else {
            // 一次性刷新：只改结论，**不碰 updatedAt**，否则老对局会全部跳到首页顶部
            matchDao.updateStatusKeepTimestamp(matchId.raw, derived.name)
        }
    }

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
        // RT-003 Step 3：同位置多版本先定胜负，再按逻辑位置排序（契约见 MatchBundle KDoc）。
        //
        // 为什么去重必须在 decode **之前**：领域 `MatchEvent` 没有 `revisionSeq` 字段
        // （Step 1 刻意不加，保持 8 个公共字段不变），所以胜负只能在实体层判。
        //
        // 排序键四段是刻意写全的：`revisionSeq` 相同时用 `recordedAt`、再退化为 `id`，
        // 保证**同一份数据在任何设备上得到同一结果**（不能依赖 SQLite 的隐式行序）。
        val sortedForDedup = eventEntities.sortedWith(
            compareBy<MatchEventEntity> { it.logicalPosition }
                .thenByDescending { it.revisionSeq }
                .thenBy { it.recordedAt }
                .thenBy { it.id }
        )
        val deduped = sortedForDedup.distinctBy { it.logicalPosition }
        if (deduped.size != sortedForDedup.size) {
            // 不变量本该由 reviseWithSlot 保证；走到这里说明数据异常（B3 自检也会报同样的位置）。
            // 这里**不删不改**违规行，只是日志留证，然后按确定规则继续读。
            if (BuildConfig.DEBUG) {
                android.util.Log.w(
                    "RoundTraceDb",
                    "读取时发现同位置多条 active 事件：matchId=${matchEntity.id}，" +
                        "丢弃 ${sortedForDedup.size - deduped.size} 条（保留 revisionSeq 最大的版本）"
                )
            }
        }

        val events = deduped
            .map { codec.decode(it) }
            // 幂等兜底：decode 已让领域 occurredOrder == logicalPosition，这里的 sortedBy 是 no-op，
            // 但保留它可防止「未来有人改 decode 或删掉上面的排序」时顺序悄悄错乱。
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
