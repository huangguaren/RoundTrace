package com.roundtrace.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.roundtrace.app.core.database.entity.AnalysisReportEntity

/**
 * `analysis_reports` 表 DAO（ARCHITECTURE §5.6）。
 *
 * P0 不使用该表（复盘实时计算）；存在只为 P1 缓存预留，**不得成为事实源**。
 */
@Dao
interface AnalysisReportDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AnalysisReportEntity)

    @Query(
        "SELECT * FROM analysis_reports WHERE matchId = :matchId " +
            "AND eventRevision = :revision AND perspectiveKey = :key"
    )
    suspend fun find(matchId: String, revision: Int, key: String): AnalysisReportEntity?

    @Query("DELETE FROM analysis_reports WHERE matchId = :matchId")
    suspend fun clearForMatch(matchId: String)
}
