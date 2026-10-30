package com.roundtrace.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `role_assignments` 表（ARCHITECTURE §4.4 / TASKS.md T28）。
 *
 * **重要**：这里存的是「真实身份」，不等于「某玩家当时知道的身份」。
 * `roleId` 允许为 `UNKNOWN`，绝不用人数规则反推填补。
 *
 * 唯一约束 `(matchId, seatNumber)`：一个座位只能一条真实身份。
 */
@Entity(
    tableName = "role_assignments",
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
data class RoleAssignmentEntity(
    @PrimaryKey
    val id: String,
    val matchId: String,
    val seatNumber: Int,
    /** `RoleId.name`，含 `UNKNOWN` */
    val roleId: String,
    /** `RoleSource.name` */
    val source: String,
    /** `RevealState.name` */
    val revealState: String,
    /** 来源事件 id / 备注 */
    val assignedFrom: String?
)
