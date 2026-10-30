package com.roundtrace.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `match_events` 表（ARCHITECTURE §4.5 / TASKS.md T29）。
 *
 * **CRITICAL**：`occurredOrder` / `recordedAt` / `visibility` / `supersedesId` / `retracted`
 * **以列为准**；`payloadJson` 只保存**事件专有字段**（如 `TeamProposed` 只存
 * `questNo/proposalNo/leaderSeat/teamSeats`），避免双写漂移。
 * `EventCodec` 是唯一编解码出口。
 *
 * 三索引：`(matchId, occurredOrder)`、`(matchId, eventType)`、`supersedesId`。
 */
@Entity(
    tableName = "match_events",
    foreignKeys = [
        ForeignKey(
            entity = MatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["matchId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["matchId", "occurredOrder"]),
        Index(value = ["matchId", "eventType"]),
        Index(value = ["supersedesId"])
    ]
)
data class MatchEventEntity(
    @PrimaryKey
    val id: String,
    val matchId: String,
    /** 游戏内顺序，**唯一排序键**；由 DAO 在事务内分配 */
    val occurredOrder: Int,
    /** `SETUP` / `QUEST_n` / `ASSASSINATION` / `END` */
    val phaseKey: String,
    /** `EventType.name` */
    val eventType: String,
    val payloadVersion: Int = 1,
    /** **仅事件专有字段**的 JSON */
    val payloadJson: String,
    /** `EventVisibility.name` */
    val visibility: String,
    val recordedAt: Long,
    /** 审计链 */
    val supersedesId: String?,
    /** 0/1 */
    val retracted: Boolean = false
)
