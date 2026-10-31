package com.roundtrace.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.roundtrace.app.core.database.entity.MatchEventEntity
import kotlinx.coroutines.flow.Flow

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

    @Query(
        "SELECT * FROM match_events WHERE matchId = :matchId AND retracted = 0 " +
            "ORDER BY occurredOrder ASC"
    )
    abstract fun observeActive(matchId: String): Flow<List<MatchEventEntity>>

    @Query(
        "SELECT * FROM match_events WHERE matchId = :matchId AND retracted = 0 " +
            "ORDER BY occurredOrder ASC"
    )
    abstract suspend fun listActive(matchId: String): List<MatchEventEntity>

    @Query("SELECT * FROM match_events WHERE matchId = :matchId ORDER BY occurredOrder ASC")
    abstract suspend fun listAll(matchId: String): List<MatchEventEntity>

    @Query(
        "SELECT * FROM match_events WHERE matchId = :matchId AND occurredOrder <= :order " +
            "AND retracted = 0 ORDER BY occurredOrder ASC"
    )
    abstract suspend fun listActiveUpTo(matchId: String, order: Int): List<MatchEventEntity>

    @Query("SELECT * FROM match_events WHERE id = :id")
    abstract suspend fun findById(id: String): MatchEventEntity?

    @Query("SELECT COALESCE(MAX(occurredOrder), 0) FROM match_events WHERE matchId = :matchId")
    abstract suspend fun maxOccurredOrder(matchId: String): Int

    @Query(
        "SELECT COALESCE(MAX(occurredOrder), 0) + 1 FROM match_events WHERE matchId = :matchId"
    )
    abstract suspend fun nextOccurredOrder(matchId: String): Int

    /**
     * 撤销事件，**按对局限定**（RT-002）。
     *
     * 只按 `id` 撤销会让「拿 A 局的 matchId 去撤销 B 局的事件」静默生效——
     * 事件 id 是 UUID 时几乎不可能撞上，但一旦撞上就是跨对局数据损坏，
     * 而且 SQL 本身没有任何东西阻止它。
     *
     * @return 受影响行数：1 = 成功，0 = 事件不属于该对局或不存在
     */
    @Query("UPDATE match_events SET retracted = 1 WHERE id = :id AND matchId = :matchId")
    abstract suspend fun markRetracted(matchId: String, id: String): Int

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
     * 追加事件（单一事务）：分配 `occurredOrder` + 写事件 + touch 对局。
     *
     * `occurredOrder` 只能由这里分配——领域层造的 event 一律是 `0`。
     *
     * @param entityProvider 接收分配好的 order，返回要落库的实体
     * @return 实际分配的 `occurredOrder`
     */
    @Transaction
    open suspend fun appendWithOrder(
        matchId: String,
        entityProvider: (Int) -> MatchEventEntity,
        now: Long
    ): Int {
        val order = nextOccurredOrder(matchId)
        insert(entityProvider(order))
        touchMatch(matchId, now)
        return order
    }

    /**
     * 修订事件（单一事务）：写新事件 + 把旧事件 `retracted = 1` + touch 对局。
     *
     * 读路径只按 `retracted` 过滤；`supersedesId` 仅作审计链。
     */
    @Transaction
    open suspend fun reviseSupersede(
        matchId: String,
        newEntityProvider: (Int) -> MatchEventEntity,
        oldEventId: String,
        now: Long
    ): Int {
        // 顺序很重要：**先撤销、后插入**。
        // 撤销不成功（事件不属于本局/已撤销）就直接抛，@Transaction 回滚，
        // 从而不会留下「替代事件已写入、旧事件仍有效」的重复事实（RT-002）。
        val affected = markRetracted(matchId, oldEventId)
        check(affected == 1) {
            "修订目标不属于本对局或已失效：matchId=$matchId, oldEventId=$oldEventId, affected=$affected"
        }
        val order = nextOccurredOrder(matchId)
        insert(newEntityProvider(order))
        touchMatch(matchId, now)
        return order
    }
}
