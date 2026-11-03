package com.roundtrace.app.core.data

import androidx.room.invalidationTrackerFlow
import androidx.room.withTransaction
import com.roundtrace.app.BuildConfig
import com.roundtrace.app.core.data.edit.ActiveEventToken
import com.roundtrace.app.core.data.edit.EditImpactPlanner
import com.roundtrace.app.core.data.edit.EditPlanRejection
import com.roundtrace.app.core.data.edit.EditPlanResult
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.CancellationException

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

    /** NX-06：历史编辑的影响范围计算（纯函数，只依赖 reducer）。 */
    private val editPlanner = EditImpactPlanner(reducer)

    private val now: Long
        get() = clock.millis()

    // ————————————————————————————————————————————————————————
    // 读
    // ————————————————————————————————————————————————————————

    override fun observeSummaries(filter: MatchStatusFilter): Flow<List<MatchSummary>> =
        matchDao.observeSummaries(filter.statuses())
            .map { rows -> rows.map(Mapping::toSummary) }

    /**
     * NX-02 / RT-NX02-SNAP：**一次发射 = 一个事务内读完四张表**。
     *
     * ## 为什么不再用四路 `combine`
     *
     * 旧实现把 `matches` / `participants` / `role_assignments` / `match_events` 四个 DAO Flow
     * 直接 `combine`。一次写事务提交后，Room 的 `InvalidationTracker` 会通知**四路各自重查**，
     * 但四路是**互相独立地异步重查**的：`combine` 在任意一路拿到新值时就会合并发射，
     * 此时其余三路仍持有旧值 —— 于是「角色已 `REVEALED` 而 `matches.revealedAt` 仍是 `null`」
     * 「事件已出现而 `matches.status` 还停在 `DRAFT`」这类**混搭快照**会被真实发给 UI。
     * `distinctUntilChanged()` 只做相邻去重，**不是**一致性机制，救不了这个问题。
     *
     * ## 现在的一致性来源
     *
     * 1. `invalidationTrackerFlow` 只把「哪些表变了」当作**失效信号**（信号本身不带数据，
     *    因此不存在「信号里的数据是旧版本」的可能）；
     * 2. 每收到一个信号，就在**一个** `withTransaction` 里把四张表读完 —— 一次发射的全部字段
     *    来自同一个数据库版本，跨表不变量（角色揭晓 ⟺ `revealedAt`、`status` ⟺ 事件重放结果等）
     *    天然成立；
     * 3. `mapLatest`：新信号到达时**取消在途读取**。若用 `map`，一个慢读的结果可能在更新的读
     *    之后才返回，把旧版本覆盖到新版本之上（顺序错乱）；`mapLatest` 保证「最新信号胜出」，
     *    中间被跳过的快照本来也不需要展示。
     * 4. `emitInitialState = true`：订阅瞬间先发一次 `matches` 等表的表名集合，于是
     *    **不用写一次库**就能拿到当前快照（UI 不会先空白再刷新）。
     *
     * ## 硬约束
     *
     * - 不得用全局 `Mutex` 或结果缓存「掩盖」版本问题：这里的正确性只来自事务边界；
     * - 对局被删除时 `readBundleLocked` 返回 `null`，本 Flow 必须发射 `null`（而不是保留上一份快照）；
     * - 四张表的表名必须与实体声明一致，漏一张就会「改了却不刷新」。
     *
     * `@OptIn(ExperimentalCoroutinesApi::class)` 只为 `mapLatest` 而加
     * （它至今仍被 coroutines 标为实验性，但语义正是本处所需的「取消在途读取」）；
     * Room 2.6.1 的 `invalidationTrackerFlow` 本身**不需要** opt-in，已用 `javap` 核实。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeBundle(matchId: MatchId): Flow<MatchBundle?> =
        database.invalidationTrackerFlow(
            "matches",
            "participants",
            "role_assignments",
            "match_events",
            emitInitialState = true
        )
            .mapLatest { database.withTransaction { readBundleLocked(matchId) } }
            .distinctUntilChanged()

    override suspend fun getBundle(matchId: MatchId): MatchBundle? {
        // NX-02：四表读收进同一事务，避免撕裂读
        return database.withTransaction { readBundleLocked(matchId) }
    }

    /**
     * NX-02：在事务内读完整 bundle。
     *
     * 必须在 `database.withTransaction { ... }` 块内调用——四表读在同一事务内，
     * 避免「matches 读到新版本、events 读到旧版本」的撕裂读。
     */
    private suspend fun readBundleLocked(matchId: MatchId): MatchBundle? {
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
        val committedAt = now
        return database.withTransaction {
            // NX-02：事务内读快照，关闭「读 bundle→校验→写」窗口的并发竞态
            val bundle = readBundleLocked(matchId)
                ?: return@withTransaction failed(matchId, RepositoryError.NOT_FOUND)

            val rules = gameRulesRegistry.rulesFor(bundle.config)
            val state = ReplayEngine(reducer).replay(bundle.toReconstructedMatch()).state
            val issues = rules.validateEvent(state, event)

            // §9.3-18：首次提交某任务的提案时自动补建 QUEST_STARTED
            // NX-02：在事务内复核（而非用事务外 bundle），关闭并发双补建
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

    override suspend fun retractAfterOrder(matchId: MatchId, logicalPosition: Int): AppendResult {
        if (logicalPosition < 0) return failed(matchId, RepositoryError.WRITE_FAILED)
        return try {
            database.withTransaction {
                if (matchDao.findById(matchId.raw) == null) {
                    return@withTransaction failed(matchId, RepositoryError.NOT_FOUND)
                }
                val affected = eventDao.retractAfterOrder(matchId.raw, logicalPosition)
                if (affected > 0) {
                    eventDao.touchMatch(matchId.raw, now)
                    recomputeProjectionLocked(matchId)
                }
                AppendResult(matchId, eventId = null, occurredOrder = null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w(DB_LOG_TAG, "回滚失败，事务已撤销：matchId=${matchId.raw}", failure)
            failed(matchId, RepositoryError.WRITE_FAILED)
        }
    }

    // ————————————————————————————————————————————————————————
    // 历史安全编辑（NX-06）
    // ————————————————————————————————————————————————————————

    /**
     * 影响预览：**只读**。
     *
     * 快照与令牌在同一个事务内读取，保证「用户看到的清单」= 「确认时要校验的现状」。
     * 这里刻意不做任何写入，也不刷新 `updatedAt` —— 浏览历史必须零写库（A01）。
     */
    override suspend fun planHistoricalEdit(
        matchId: MatchId,
        targetEventId: EventId,
        replacement: MatchEvent
    ): EditPlanResult = try {
        database.withTransaction {
            val bundle = readBundleLocked(matchId)
                ?: return@withTransaction EditPlanResult.Rejected(
                    EditPlanRejection.TARGET_NOT_FOUND,
                    "对局不存在或已被删除"
                )
            editPlanner.plan(
                bundle = bundle,
                expectedActive = activeTokensLocked(matchId, bundle),
                targetEventId = targetEventId,
                replacement = replacement
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        android.util.Log.w(DB_LOG_TAG, "历史编辑预览失败：matchId=${matchId.raw}", failure)
        EditPlanResult.Rejected(
            EditPlanRejection.PLAN_FAILED,
            "无法读取当前历史快照，请重试"
        )
    }

    /**
     * 确认执行：**修订 + 精确撤销 + 投影重算**在同一事务内完成。
     *
     * 与 [reviseEvent] 的区别不只是「多撤几条」：
     * - [reviseEvent] 信任调用方给出的目标，事务内只复核该目标是否仍有效；
     * - 本方法**不信任 UI 传来的任何集合**：事务内用新鲜数据重算计划，
     *   令牌与撤销集合都要严格相等，否则返回 [EditApplyResult.StalePreview] 且零写入。
     *
     * 异常策略：
     * - `CancellationException` 原样抛出（协程取消必须传播）；
     * - 其它异常 → 记日志 + [EditApplyResult.Failed]，事务已由 `withTransaction` 整体回滚。
     */
    override suspend fun applyConfirmedEdit(
        matchId: MatchId,
        targetEventId: EventId,
        replacement: MatchEvent,
        confirmedRetractIds: List<EventId>,
        expectedTokens: List<ActiveEventToken>
    ): EditApplyResult {
        val committedAt = now
        return try {
            database.withTransaction {
                val bundle = readBundleLocked(matchId)
                    ?: return@withTransaction EditApplyResult.Failed(RepositoryError.NOT_FOUND)

                // ① 新鲜令牌 + 新鲜计划：所有判定都基于事务内的现状，不信任 UI 的快照
                val tokens = activeTokensLocked(matchId, bundle)
                val planned = editPlanner.plan(
                    bundle = bundle,
                    expectedActive = tokens,
                    targetEventId = targetEventId,
                    replacement = replacement
                )
                val plan = when (planned) {
                    is EditPlanResult.Ready -> planned.plan
                    is EditPlanResult.Rejected -> {
                        android.util.Log.w(
                            DB_LOG_TAG,
                            "确认历史修改被拒绝：${planned.reason.name} ${planned.detail}"
                        )
                        // 业务字段等价不是错误：零写入，让 UI 提示「内容未修改」而不是「保存失败」
                        if (planned.reason == EditPlanRejection.NO_BUSINESS_CHANGE) {
                            return@withTransaction EditApplyResult.NoChange
                        }
                        return@withTransaction EditApplyResult.Failed(
                            RepositoryError.INVALID_SUPERSEDE_TARGET
                        )
                    }
                }

                // ② 快照令牌必须逐条相等：位置或修订序号变化都说明「预览不是现在的历史」
                if (!sameTokens(tokens, expectedTokens)) {
                    return@withTransaction EditApplyResult.StalePreview(plan)
                }

                // ③ 用户确认的撤销集合必须与规则判定完全一致（多一条少一条都不签）
                if (plan.retractIds.toSet() != confirmedRetractIds.toSet()) {
                    return@withTransaction EditApplyResult.StalePreview(plan)
                }

                // ④ 需要人工复核且会删记录 → 拒绝，让用户明确修复路径（US-06）
                if (plan.requiresManualReview && plan.isDestructive) {
                    return@withTransaction EditApplyResult.Conflict(plan.manualReviewReasons)
                }

                val target = bundle.events.firstOrNull { it.eventId == targetEventId }
                    ?: return@withTransaction EditApplyResult.Failed(
                        RepositoryError.INVALID_SUPERSEDE_TARGET
                    )

                // ⑤ 修订：策略校验已在 planner 内用同一份 EventRevisionPolicy 完成；
                //    reviseWithSlot 负责「继承 logicalPosition、revisionSeq + 1、旧版本 retracted = 1」，
                //    撤销命中 0 行会抛异常 → 整个事务回滚。
                val slot = eventDao.reviseWithSlot(
                    matchId = matchId.raw,
                    oldEventId = target.eventId.raw,
                    entityProvider = { s ->
                        codec.encode(
                            replacement,
                            matchId,
                            s.occurredOrder,
                            s.logicalPosition,
                            s.revisionSeq
                        ).copy(supersedesId = target.eventId.raw)
                    },
                    now = committedAt
                )
                check(slot.logicalPosition > 0) {
                    "修订槽位非法：logicalPosition=${slot.logicalPosition}"
                }

                // ⑥ 精确撤销：每条必须恰好命中 1 行（0 行 = 已被并发撤销 / 不属于本局）
                plan.retractIds.forEach { id ->
                    val affected = eventDao.markRetracted(matchId.raw, id.raw)
                    check(affected == 1) {
                        "撤销受影响事件失败：eventId=${id.raw}, affected=$affected"
                    }
                }

                // ⑦ 事务内只重算一次投影（同时刷新 updatedAt）
                recomputeProjectionLocked(matchId)

                EditApplyResult.Success(replacement.eventId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w(DB_LOG_TAG, "确认历史修改失败，事务已撤销：matchId=${matchId.raw}", failure)
            EditApplyResult.Failed(RepositoryError.WRITE_FAILED)
        }
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

    override suspend fun revealAllRoles(matchId: MatchId): AppendResult =
        revealMatch(matchId, emptyList())

    override suspend fun revealMatch(
        matchId: MatchId,
        assignments: List<RoleAssignment>
    ): AppendResult = try {
        database.withTransaction {
            val entity = matchDao.findById(matchId.raw)
                ?: return@withTransaction failed(matchId, RepositoryError.NOT_FOUND)
            if (assignments.map { it.seat }.distinct().size != assignments.size ||
                assignments.any { it.seat !in 1..entity.playerCount }
            ) {
                return@withTransaction failed(matchId, RepositoryError.WRITE_FAILED)
            }
            val committedAt = now
            val existingBySeat = roleDao.listByMatch(matchId.raw).associateBy { it.seatNumber }
            val roles = assignments.map { assignment ->
                Mapping.toRoleEntity(
                    assignmentId = existingBySeat[assignment.seat]?.id ?: idGenerator.newId(),
                    matchId = matchId,
                    assignment = assignment.copy(revealState = RevealState.REVEALED)
                )
            }
            if (roles.isNotEmpty()) roleDao.upsertAll(roles)
            roleDao.revealAll(matchId.raw)
            matchDao.updateStatus(
                entity.id,
                entity.status,
                entity.revealedAt ?: committedAt,
                committedAt
            )
            // 新角色可能让既有刺杀事件变得可判定；重放也属于此次事务。
            recomputeProjectionLocked(matchId)
            AppendResult(matchId, eventId = null, occurredOrder = null)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        android.util.Log.w(DB_LOG_TAG, "揭晓失败，事务已撤销：matchId=${matchId.raw}", failure)
        failed(matchId, RepositoryError.WRITE_FAILED)
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

    /**
     * 读取本局全部 active 事件的快照令牌。
     *
     * **必须从实体行取** `logicalPosition` / `revisionSeq`：领域 `MatchEvent` 没有这两个字段
     * （RT-003 Step 1 刻意没加）。用 `occurredOrder` 冒充 `logicalPosition` 在正常数据上恰好相等，
     * 但修订链一多就会「看起来对、实际没校验到」，这是最难查的一类 bug。
     *
     * 顺序按 [MatchBundle.events]（逻辑位置升序），并逐条对齐：实体行缺失时返回的列表会比
     * `bundle.events` 短，planner 的令牌数量校验会直接判为过期。
     */
    private suspend fun activeTokensLocked(
        matchId: MatchId,
        bundle: MatchBundle
    ): List<ActiveEventToken> {
        val rowsById = eventDao.listActive(matchId.raw).associateBy { it.id }
        return bundle.events.mapNotNull { event ->
            val row = rowsById[event.eventId.raw] ?: return@mapNotNull null
            ActiveEventToken(
                eventId = event.eventId,
                logicalPosition = row.logicalPosition,
                revisionSeq = row.revisionSeq
            )
        }
    }

    /** 令牌集合严格相等（与顺序无关，但每条的位置/修订序号都必须一致）。 */
    private fun sameTokens(
        fresh: List<ActiveEventToken>,
        expected: List<ActiveEventToken>
    ): Boolean {
        if (fresh.size != expected.size) return false
        val normalize = { tokens: List<ActiveEventToken> ->
            tokens.sortedWith(compareBy({ it.logicalPosition }, { it.eventId.raw }))
        }
        val left = normalize(fresh)
        val right = normalize(expected)
        return left.indices.all { index ->
            left[index].eventId == right[index].eventId &&
                left[index].logicalPosition == right[index].logicalPosition &&
                left[index].revisionSeq == right[index].revisionSeq
        }
    }

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
