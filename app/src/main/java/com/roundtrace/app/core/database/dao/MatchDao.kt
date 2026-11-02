package com.roundtrace.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.roundtrace.app.core.database.entity.MatchEntity
import kotlinx.coroutines.flow.Flow

/**
 * `matches` 表 DAO（ARCHITECTURE §5.1）。
 *
 * 返回值约定：一次性读取用 `suspend`，持续观察用 `Flow`，写入一律 `suspend`。
 * **不允许主线程同步 IO。**
 */
@Dao
interface MatchDao {

    @Insert
    suspend fun insert(entity: MatchEntity): Long

    @Update
    suspend fun update(entity: MatchEntity)

    @Query("SELECT * FROM matches WHERE id = :id")
    suspend fun findById(id: String): MatchEntity?

    @Query("SELECT * FROM matches WHERE id = :id")
    fun observeById(id: String): Flow<MatchEntity?>

    @Query("SELECT * FROM matches WHERE status IN (:statuses) ORDER BY updatedAt DESC")
    fun observeByStatus(statuses: List<String>): Flow<List<MatchEntity>>

    @Query(
        "UPDATE matches SET title = :title, configJson = :configJson, updatedAt = :now " +
            "WHERE id = :id"
    )
    suspend fun rename(id: String, title: String, configJson: String, now: Long)

    @Query(
        "UPDATE matches SET status = :status, revealedAt = :revealedAt, updatedAt = :now " +
            "WHERE id = :id"
    )
    suspend fun updateStatus(id: String, status: String, revealedAt: Long?, now: Long)

    /** 只更新 status 和 updatedAt，保留已有 revealedAt。 */
    @Query(
        "UPDATE matches SET status = :status, updatedAt = :now WHERE id = :id"
    )
    suspend fun updateStatusOnly(id: String, status: String, now: Long)

    /**
     * 只更新 `status`，**连 `updatedAt` 都不碰**。
     *
     * 专供 RT-003 Step 3 的一次性投影刷新：那次刷新是「后台把老数据按新顺序重算一遍」，
     * 若走 [updateStatusOnly] 会把所有老对局的「最近活动时间」刷成现在、打乱首页排序 ——
     * 用户会看到一堆几个月前的对局突然跳到列表顶部。
     */
    @Query("UPDATE matches SET status = :status WHERE id = :id")
    suspend fun updateStatusKeepTimestamp(id: String, status: String)

    /** 全部对局 id（一次性投影刷新用；按 updatedAt 升序，先刷老数据）。 */
    @Query("SELECT id FROM matches ORDER BY updatedAt ASC")
    suspend fun listAllIds(): List<String>

    @Query("UPDATE matches SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    @Query("DELETE FROM matches WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * 首页列表投影：附带已开始任务数（避免为每张卡再查事件表）。
     *
     * `questsStarted` 只统计 `retracted = 0` 的 `QUEST_STARTED`。
     */
    @Query(
        """
        SELECT m.id AS matchId, m.title AS title, m.playerCount AS playerCount,
               m.status AS status, m.rulesetId AS rulesetId, m.updatedAt AS updatedAt,
               m.mySeat AS mySeat,
               (SELECT COUNT(*) FROM match_events e
                 WHERE e.matchId = m.id AND e.eventType = 'QUEST_STARTED' AND e.retracted = 0)
                 AS questsStarted
        FROM matches m
        WHERE m.status IN (:statuses)
        ORDER BY m.updatedAt DESC
        """
    )
    fun observeSummaries(statuses: List<String>): Flow<List<MatchSummaryRow>>
}
