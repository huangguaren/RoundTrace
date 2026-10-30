package com.roundtrace.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * `match_drafts` 表（ARCHITECTURE §4.7 / TASKS.md T29）。
 *
 * 防丢失草稿：复合主键 `(matchId, screenKey)`。
 * 草稿**不是事实**：只有用户确认后才写入 `match_events`。
 */
@Entity(
    tableName = "match_drafts",
    primaryKeys = ["matchId", "screenKey"],
    foreignKeys = [
        ForeignKey(
            entity = MatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["matchId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MatchDraftEntity(
    val matchId: String,
    /** `RECORD_PROPOSAL` / `RECORD_VOTE` / `RECORD_MISSION` / `CREATE_MATCH` / `CLAIM_ADD` */
    val screenKey: String,
    val draftJson: String,
    val updatedAt: Long
)
