package com.roundtrace.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.roundtrace.app.core.database.entity.RoleAssignmentEntity
import kotlinx.coroutines.flow.Flow

/** `role_assignments` 表 DAO（ARCHITECTURE §5.3）。 */
@Dao
interface RoleAssignmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<RoleAssignmentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RoleAssignmentEntity)

    @Query("SELECT * FROM role_assignments WHERE matchId = :matchId")
    fun observeByMatch(matchId: String): Flow<List<RoleAssignmentEntity>>

    @Query("SELECT * FROM role_assignments WHERE matchId = :matchId")
    suspend fun listByMatch(matchId: String): List<RoleAssignmentEntity>

    @Query("SELECT * FROM role_assignments WHERE matchId = :matchId AND seatNumber = :seat")
    suspend fun findBySeat(matchId: String, seat: Int): RoleAssignmentEntity?

    @Query(
        "UPDATE role_assignments SET roleId = :roleId, source = :source, revealState = :revealState " +
            "WHERE matchId = :matchId AND seatNumber = :seat"
    )
    suspend fun updateSeat(
        matchId: String,
        seat: Int,
        roleId: String,
        source: String,
        revealState: String
    )

    @Query("UPDATE role_assignments SET revealState = 'REVEALED' WHERE matchId = :matchId")
    suspend fun revealAll(matchId: String)

    @Query("DELETE FROM role_assignments WHERE matchId = :matchId")
    suspend fun deleteByMatch(matchId: String)
}
