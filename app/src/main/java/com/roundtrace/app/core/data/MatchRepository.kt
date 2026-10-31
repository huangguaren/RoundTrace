package com.roundtrace.app.core.data

import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MatchSummary
import com.roundtrace.app.core.model.RoleAssignment
import com.roundtrace.app.core.model.RuleIssue
import kotlinx.coroutines.flow.Flow

/**
 * 对局仓储端口（ARCHITECTURE §3.1 / TASKS.md T34①）。
 *
 * **硬约束**：本接口不得出现任何 Room 类型（Entity / DAO / Database）。
 * ViewModel 只依赖本接口，因此「UI 不碰 DAO」这条依赖规则（D2）是编译期成立的。
 *
 * 事务语义：
 * - [createMatch] 必须在**单事务**内写 `matches` + `participants` + `role_assignments`；
 * - [appendEvent] 必须在**单事务**内分配 `occurredOrder` + 写事件 + touch 对局；
 * - [reviseEvent] 必须在**单事务**内写新事件 + 把旧事件 `retracted = 1`。
 */
interface MatchRepository {

    /** 首页列表。`filter` 只影响状态集合，不影响排序（`updatedAt DESC`）。 */
    fun observeSummaries(filter: MatchStatusFilter): Flow<List<MatchSummary>>

    /** 观察完整快照；对局不存在时发射 `null`。 */
    fun observeBundle(matchId: MatchId): Flow<MatchBundle?>

    /** 一次性读取。 */
    suspend fun getBundle(matchId: MatchId): MatchBundle?

    /** 创建对局（单事务），返回新 `MatchId`。 */
    suspend fun createMatch(draft: MatchCreationDraft): MatchId

    /**
     * 追加事件：分配 `occurredOrder`、更新 `matches.updatedAt`、事务提交。
     *
     * 返回的 [AppendResult.issues] 是 `validateEvent` 的结果；
     * **BLOCKING 时事件仍然保存**（现场事实优先），只是被标记为未核实。
     */
    suspend fun appendEvent(matchId: MatchId, event: MatchEvent): AppendResult

    /** 修订：新事件 `supersedesId = 旧 id`，同事务把旧事件 `retracted = 1`。 */
    suspend fun reviseEvent(
        matchId: MatchId,
        targetEventId: EventId,
        replacement: MatchEvent
    ): AppendResult

    /** 撤销：把事件标记为 `retracted = 1`。 */
    suspend fun retractEvent(matchId: MatchId, eventId: EventId): AppendResult

    suspend fun saveRoleAssignments(matchId: MatchId, assignments: List<RoleAssignment>)

    /** 揭晓全部角色：`revealState = REVEALED` + 写 `revealedAt`。 */
    suspend fun revealAllRoles(matchId: MatchId): AppendResult

    suspend fun setStatus(matchId: MatchId, status: MatchStatus, revealedAt: Long?)

    suspend fun renameMatch(matchId: MatchId, title: String)

    /** 对局进行中更新座位名/头像/坐标（不改人数）。 */
    suspend fun updateParticipants(matchId: MatchId, participants: List<com.roundtrace.app.core.model.Participant>)

    suspend fun deleteMatch(matchId: MatchId)

    suspend fun saveDraft(matchId: MatchId, screenKey: String, json: String)

    suspend fun loadDraft(matchId: MatchId, screenKey: String): String?

    suspend fun clearDraft(matchId: MatchId, screenKey: String)

    /** 供导入使用：整场事务写入，**绝不覆盖已有对局**（总是新建 `MatchId`）。 */
    suspend fun importBundle(bundle: ImportBundle): MatchId
}

/** 追加/修订/撤销的结果。 */
data class AppendResult(
    val matchId: MatchId,
    val eventId: EventId?,
    val occurredOrder: Int?,
    /** `validateEvent` 的结果；BLOCKING 时事件仍保存，但标记未核实 */
    val issues: List<RuleIssue> = emptyList(),
    val error: RepositoryError? = null
) {
    /** 是否成功写入。 */
    val isSuccess: Boolean
        get() = error == null

    /** 是否存在阻断级问题（UI 应提示但**不**回滚）。 */
    val hasBlockingIssue: Boolean
        get() = issues.any { it.isBlocking }
}

/** 仓储错误（不抛异常，用返回值表达）。 */
enum class RepositoryError {
    NOT_FOUND,
    WRITE_FAILED,
    INVALID_SUPERSEDE_TARGET,
    DUPLICATE_EVENT
}

/** 首页筛选（端口层，与 UI 的 `HomeFilter` 解耦）。 */
enum class MatchStatusFilter {
    ALL,
    IN_PROGRESS,
    FINISHED;

    /** 映射到 `matches.status` 的查询集合。 */
    fun statuses(): List<String> = when (this) {
        ALL -> MatchStatus.entries.map { it.name }
        IN_PROGRESS -> listOf(
            MatchStatus.DRAFT.name,
            MatchStatus.IN_PROGRESS.name,
            MatchStatus.AWAITING_ASSASSINATION.name
        )

        FINISHED -> listOf(
            MatchStatus.FINISHED_GOOD.name,
            MatchStatus.FINISHED_EVIL.name,
            MatchStatus.ABANDONED.name
        )
    }
}
