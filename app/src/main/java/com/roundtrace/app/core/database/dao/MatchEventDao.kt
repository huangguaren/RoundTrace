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

    @Query("UPDATE match_events SET retracted = 1 WHERE id = :id")
    abstract suspend fun markRetracted(id: String)

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
        val order = nextOccurredOrder(matchId)
        insert(newEntityProvider(order))
        markRetracted(oldEventId)
        touchMatch(matchId, now)
        return order
    }
}
