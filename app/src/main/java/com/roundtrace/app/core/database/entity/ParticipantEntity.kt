package com.roundtrace.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `participants` 表（ARCHITECTURE §4.3 / TASKS.md T28）。
 *
 * 唯一约束 `(matchId, seatNumber)`：一个座位只能有一条参与人记录。
 * 级联删除**只用于删除整局**（ARCHITECTURE §4.1）。
 */
@Entity(
    tableName = "participants",
    foreignKeys = [
        ForeignKey(
            entity = MatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["matchId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["matchId", "seatNumber"], unique = true)
    ]
)
data class ParticipantEntity(
    @PrimaryKey
    val id: String,
    val matchId: String,
    val seatNumber: Int,
    val displayName: String?,
    val colorKey: String?,
    val layoutX: Float = -1f,
    val layoutY: Float = -1f,
    val avatarUri: String? = null
)
