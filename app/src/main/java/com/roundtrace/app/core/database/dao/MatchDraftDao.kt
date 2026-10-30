package com.roundtrace.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.roundtrace.app.core.database.entity.MatchDraftEntity
import kotlinx.coroutines.flow.Flow

/** `match_drafts` 表 DAO（ARCHITECTURE §5.5）。草稿不是事实，可随时覆盖。 */
@Dao
interface MatchDraftDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MatchDraftEntity)

    @Query("SELECT draftJson FROM match_drafts WHERE matchId = :matchId AND screenKey = :screenKey")
    suspend fun load(matchId: String, screenKey: String): String?

    @Query("SELECT draftJson FROM match_drafts WHERE matchId = :matchId AND screenKey = :screenKey")
    fun observe(matchId: String, screenKey: String): Flow<String?>

    @Query("DELETE FROM match_drafts WHERE matchId = :matchId AND screenKey = :screenKey")
    suspend fun clear(matchId: String, screenKey: String)

    @Query("DELETE FROM match_drafts WHERE matchId = :matchId")
    suspend fun clearForMatch(matchId: String)
}
