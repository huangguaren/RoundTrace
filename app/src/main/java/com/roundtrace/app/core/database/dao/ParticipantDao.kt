package com.roundtrace.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.roundtrace.app.core.database.entity.ParticipantEntity
import kotlinx.coroutines.flow.Flow

/** `participants` 表 DAO（ARCHITECTURE §5.2）。 */
@Dao
interface ParticipantDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ParticipantEntity>)

    @Query("SELECT * FROM participants WHERE matchId = :matchId ORDER BY seatNumber ASC")
    fun observeByMatch(matchId: String): Flow<List<ParticipantEntity>>

    @Query("SELECT * FROM participants WHERE matchId = :matchId ORDER BY seatNumber ASC")
    suspend fun listByMatch(matchId: String): List<ParticipantEntity>

    @Query(
        "UPDATE participants SET displayName = :name " +
            "WHERE matchId = :matchId AND seatNumber = :seat"
    )
    suspend fun renameSeat(matchId: String, seat: Int, name: String?)

    @Query("DELETE FROM participants WHERE matchId = :matchId")
    suspend fun deleteByMatch(matchId: String)
}
