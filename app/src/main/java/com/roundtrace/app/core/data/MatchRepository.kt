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

    /**
     * 单事务撤销本局中逻辑位置大于 [logicalPosition] 的全部有效事件并重算状态。
     * 保留历史行与已有揭晓时间；边界包含在保留范围内，0 表示撤销全部事件。
     */
    suspend fun retractAfterOrder(matchId: MatchId, logicalPosition: Int): AppendResult

    suspend fun saveRoleAssignments(matchId: MatchId, assignments: List<RoleAssignment>)

    /** 揭晓全部角色：`revealState = REVEALED` + 写 `revealedAt`。 */
    suspend fun revealAllRoles(matchId: MatchId): AppendResult

    /**
     * 在同一事务内保存角色、揭晓全部身份、写首次揭晓时间并重算状态。
     * 重复调用保留首次揭晓时间；未知身份仍可保存，任何步骤失败则整体回滚。
     */
    suspend fun revealMatch(matchId: MatchId, assignments: List<RoleAssignment>): AppendResult

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

    /**
     * 一次性把所有对局的 `status` 按**当前顺序模型**重算（RT-003 Step 3）。
     *
     * 为什么需要：`matches.status` 是写入时算好并冻结的列。读路径切到逻辑位置后，
     * 同一局重放可能得出不同结论；老对局若不再被写，首页卡（读列）与复盘页（读 replay）
     * 会长期显示互相矛盾的结论。
     *
     * 硬约束：
     * - **不得改动 `updatedAt`**（否则老对局会全部跳到首页顶部）；
     * - 逐局复用与写入路径相同的重放逻辑，不另写一套；
     * - 幂等：重复执行结果相同。
     *
     * @return 成功/失败明细，见 [ProjectionRefreshResult]
     */
    suspend fun recomputeAllProjections(): ProjectionRefreshResult
}

/**
 * 追加/修订/撤销的结果。
 *
 * ## [occurredOrder] 的语义（RT-003 Step 3 裁定为**写入序号**，不是逻辑位置）
 *
 * 它是「这次写入落库时被分配的写入序号」，也就是 `match_events.occurredOrder` 列的值；
 * 修订事件时它是**新的写入序号**（比旧版本大），不是该事件的逻辑位置。
 *
 * 为什么不让它改成逻辑位置：
 * 1. 字段名来自列名，改语义等于给同名字段两套含义 —— 这正是 RT-003 的病根；
 * 2. `AppendResult` 是**写结果** DTO（同结构里还有 `error`），调用方关心的是「写进去了没有、写在第几条」；
 * 3. 需要逻辑位置时应当从重放结果或事件本身取，那里语义明确。
 *
 * 当前**没有任何生产消费方**读取该字段（仅测试断言写入序号递增），因此这次裁定的成本为零。
 */
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

/**
 * 一次性投影刷新的结果。
 *
 * 为什么要返回明细而不是一个 `Int`：上一版「逐局 runCatching，全部跑完就写标记」
 * 等于**把失败当成成功** —— 某局重放抛异常时标记照样写成 1，该局永久停在旧结论且无任何信号。
 * 调用方必须能区分「全部成功」与「有失败」，否则「失败下次重试」这条就不成立。
 *
 * @param processed 成功刷新的局数
 * @param failed 刷新失败的对局 id（按发现顺序）；非空即 [isComplete] 为 false
 */
data class ProjectionRefreshResult(
    val processed: Int,
    val failed: List<MatchId>
) {

    /** 是否**全部**成功。只有为 true 时调用方才允许写版本标记。 */
    val isComplete: Boolean
        get() = failed.isEmpty()
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
