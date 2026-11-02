package com.roundtrace.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.roundtrace.app.core.database.entity.MatchEventEntity
import kotlinx.coroutines.flow.Flow

/** 事务内分配好的写入槽位（RT-003 Step 1）。 */
data class EventSlot(
    /** 写入序号：第几条被插入；只用于审计与分配下一个序号 */
    val occurredOrder: Int,
    /** 逻辑位置：同一事件的所有修订版本共享 */
    val logicalPosition: Int,
    /** 该位置被修订的次数（0 = 原始版本） */
    val revisionSeq: Int
)

/** 被修订事件的定位种子（@Query 投影）。 */
data class RevisionSeed(
    val logicalPosition: Int,
    val revisionSeq: Int
)

/**
 * `match_events` 表 DAO（ARCHITECTURE §5.4）。
 *
 * 两个复合写方法（`appendWithOrder` / `reviseSupersede`）都是 `@Transaction`，
 * 内部完成「取 order → insert → 更新 matches.updatedAt」，保证不会出现
 * 「事件写进去了但 order 重复」或「对局时间没刷新」的中间态。
 *
 * 说明：`touchMatch` 直接写 `matches` 表而不跨 DAO 调用，是为了让事务边界留在一个 DAO 内
 * （跨 DAO 调用需要 Repository 层的 `withTransaction` 包裹，见 `RoomMatchRepository`）。
 */
@Dao
abstract class MatchEventDao {

    @Insert
    abstract suspend fun insert(entity: MatchEventEntity)

    @Insert
    abstract suspend fun insertAll(items: List<MatchEventEntity>)

    /**
     * 读路径（RT-003 Step 3）：排序键是**逻辑位置**，不是写入序号。
     *
     * 让数据层先给出正确顺序，内存里的 `.sortedBy` 退化为幂等的防御——
     * 正确性不该依赖「未来不会有人删掉那行重排」（RT-003 当初就是这么来的）。
     */
    @Query(
        "SELECT * FROM match_events WHERE matchId = :matchId AND retracted = 0 " +
            "ORDER BY logicalPosition ASC, revisionSeq ASC"
    )
    abstract fun observeActive(matchId: String): Flow<List<MatchEventEntity>>

    @Query(
        "SELECT * FROM match_events WHERE matchId = :matchId AND retracted = 0 " +
            "ORDER BY logicalPosition ASC, revisionSeq ASC"
    )
    abstract suspend fun listActive(matchId: String): List<MatchEventEntity>

    /** 审计视图：按「位置 + 版本」读，才能看出同一位置有几个版本。 */
    @Query(
        "SELECT * FROM match_events WHERE matchId = :matchId " +
            "ORDER BY logicalPosition ASC, revisionSeq ASC"
    )
    abstract suspend fun listAll(matchId: String): List<MatchEventEntity>

    /**
     * 取某个**逻辑位置**之前（含）的 active 事件。
     *
     * 参数名刻意从 `order` 改成 `logicalPosition`：如果这里还叫 `order`，
     * 调用方极可能传写入序号，而 SQL 比的是逻辑位置 —— 那是**静默返回错误子集且不报错**，
     * 正是 RT-003 的原病灶（一个字段两种语义）。改名让误用变成编译期可见的语义冲突。
     */
    @Query(
        "SELECT * FROM match_events WHERE matchId = :matchId AND logicalPosition <= :logicalPosition " +
            "AND retracted = 0 ORDER BY logicalPosition ASC, revisionSeq ASC"
    )
    abstract suspend fun listActiveUpTo(
        matchId: String,
        logicalPosition: Int
    ): List<MatchEventEntity>

    @Query("SELECT * FROM match_events WHERE id = :id")
    abstract suspend fun findById(id: String): MatchEventEntity?

    @Query("SELECT COALESCE(MAX(occurredOrder), 0) FROM match_events WHERE matchId = :matchId")
    abstract suspend fun maxOccurredOrder(matchId: String): Int

    @Query(
        "SELECT COALESCE(MAX(occurredOrder), 0) + 1 FROM match_events WHERE matchId = :matchId"
    )
    abstract suspend fun nextOccurredOrder(matchId: String): Int

    /**
     * 下一个逻辑位置。
     *
     * **必须包含 retracted 行**（不加 `retracted = 0`）：否则撤销某位置后，
     * 下一个新事件会复用这个已废弃的位置，破坏「位置无空洞、不复用」的不变量。
     */
    @Query("SELECT COALESCE(MAX(logicalPosition), 0) + 1 FROM match_events WHERE matchId = :matchId")
    abstract suspend fun nextLogicalPosition(matchId: String): Int

    /**
     * 取修订种子。
     *
     * **必须带 `retracted = 0`**：目标若已被撤销（例如另一个并发修订抢先），
     * 就应该查不到种子 → `reviseWithSlot` 直接抛异常回滚，而不是继续插入一个新版本。
     */
    @Query(
        "SELECT logicalPosition, revisionSeq FROM match_events " +
            "WHERE matchId = :matchId AND id = :id AND retracted = 0"
    )
    abstract suspend fun findRevisionSeed(matchId: String, id: String): RevisionSeed?

    /** 事务内复核目标是否**仍然有效**（存在、属于本局、未撤销）。 */
    @Query(
        "SELECT * FROM match_events WHERE matchId = :matchId AND id = :id AND retracted = 0"
    )
    abstract suspend fun findActiveById(matchId: String, id: String): MatchEventEntity?

    /**
     * 撤销事件，**按对局限定**（RT-002）。
     *
     * 只按 `id` 撤销会让「拿 A 局的 matchId 去撤销 B 局的事件」静默生效——
     * 事件 id 是 UUID 时几乎不可能撞上，但一旦撞上就是跨对局数据损坏，
     * 而且 SQL 本身没有任何东西阻止它。
     *
     * 条件是 `id + matchId + retracted = 0` **三者同时满足**，这是并发修订的最终防线：
     * 两个请求同时修订同一目标时，撤销语句最多命中一行，另一个拿到 0 行 → 抛异常回滚。
     *
     * @return 受影响行数：1 = 成功，0 = 不属于该局 / 不存在 / **已被撤销**
     */
    @Query(
        "UPDATE match_events SET retracted = 1 " +
            "WHERE id = :id AND matchId = :matchId AND retracted = 0"
    )
    abstract suspend fun markRetracted(matchId: String, id: String): Int

    /** 回滚按逻辑位置截断，与写入序号无关；只改变本局的有效版本。 */
    @Query(
        "UPDATE match_events SET retracted = 1 " +
            "WHERE matchId = :matchId AND logicalPosition > :logicalPosition AND retracted = 0"
    )
    abstract suspend fun retractAfterOrder(matchId: String, logicalPosition: Int): Int

    @Query(
        "SELECT COUNT(*) FROM match_events WHERE matchId = :matchId " +
            "AND eventType = :type AND retracted = 0"
    )
    abstract suspend fun countByType(matchId: String, type: String): Int

    @Query("DELETE FROM match_events WHERE matchId = :matchId")
    abstract suspend fun deleteByMatch(matchId: String)

    @Query("UPDATE matches SET updatedAt = :now WHERE id = :matchId")
    abstract suspend fun touchMatch(matchId: String, now: Long)

    /**
     * 追加事件（单一事务）：分配写入槽位 + 写事件 + touch 对局。
     *
     * 槽位只能由这里分配——领域层造的 event 一律是 `0`。
     *
     * @param entityProvider 接收分配好的槽位，返回要落库的实体
     */
    @Transaction
    open suspend fun appendWithSlot(
        matchId: String,
        entityProvider: (EventSlot) -> MatchEventEntity,
        now: Long
    ): EventSlot {
        val slot = EventSlot(
            occurredOrder = nextOccurredOrder(matchId),
            logicalPosition = nextLogicalPosition(matchId),
            revisionSeq = 0
        )
        insert(entityProvider(slot))
        touchMatch(matchId, now)
        return slot
    }

    /**
     * 修订事件（单一事务）：写新版本 + 把旧版本 `retracted = 1` + touch 对局。
     *
     * 新版本**复用旧版本的逻辑位置**，`revisionSeq = 旧值 + 1`（0 基：0 = 原始版本）。
     * 读路径只按 `retracted` 过滤；`supersedesId` 仅作审计链。
     */
    @Transaction
    open suspend fun reviseWithSlot(
        matchId: String,
        oldEventId: String,
        entityProvider: (EventSlot) -> MatchEventEntity,
        now: Long
    ): EventSlot {
        val seed = checkNotNull(findRevisionSeed(matchId, oldEventId)) {
            "修订目标不属于本对局或已失效：matchId=$matchId, oldEventId=$oldEventId"
        }
        val slot = EventSlot(
            occurredOrder = nextOccurredOrder(matchId),
            logicalPosition = seed.logicalPosition,
            revisionSeq = seed.revisionSeq + 1
        )
        // 顺序很重要：**先撤销、后插入**。
        // 撤销不成功（事件不属于本局/已撤销）就直接抛，@Transaction 回滚，
        // 从而不会留下「替代事件已写入、旧事件仍有效」的重复事实（RT-002 收口，不得退化）。
        val affected = markRetracted(matchId, oldEventId)
        check(affected == 1) {
            "修订目标不属于本对局或已失效：matchId=$matchId, oldEventId=$oldEventId, affected=$affected"
        }
        insert(entityProvider(slot))
        touchMatch(matchId, now)
        return slot
    }
}
