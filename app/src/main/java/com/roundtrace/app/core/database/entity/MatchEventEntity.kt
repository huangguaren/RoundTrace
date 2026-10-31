package com.roundtrace.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `match_events` 表（ARCHITECTURE §4.5 / TASKS.md T29；RT-003 Step 1）。
 *
 * **CRITICAL**：`occurredOrder` / `recordedAt` / `visibility` / `supersedesId` / `retracted`
 * **以列为准**；`payloadJson` 只保存**事件专有字段**（如 `TeamProposed` 只存
 * `questNo/proposalNo/leaderSeat/teamSeats`），避免双写漂移。
 * `EventCodec` 是唯一编解码出口。
 *
 * ## 顺序语义（RT-003）
 *
 * - [occurredOrder]：历史**写入序号**（第几条被插入）。**只写不读**，仅供迁移与审计。
 * - [logicalPosition]：事件在时间线上的**逻辑位置**；同一事件的所有修订版本共享。**不可变**。
 * - [revisionSeq]：该位置被修订的次数（`0` = 原始版本）。**不可变**。
 *
 * Step 1 只「开始写正确的值」：读路径仍按 [occurredOrder]，因此用户可观察行为与改造前一致。
 *
 * 索引：
 * - `(matchId, logicalPosition, revisionSeq)`：未来读路径的唯一排序键（Step 1 只建不用）；
 * - `(matchId, occurredOrder)`：写入序号分配 `MAX(occurredOrder)`、迁移、审计仍用；
 * - `(matchId, eventType)`、`(supersedesId)` 保留。
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
        Index(value = ["matchId", "logicalPosition", "revisionSeq"]),
        Index(value = ["matchId", "occurredOrder"]),
        Index(value = ["matchId", "eventType"]),
        Index(value = ["supersedesId"])
    ]
)
data class MatchEventEntity(
    @PrimaryKey
    val id: String,
    val matchId: String,
    /** 历史**写入序号**（第几条被插入）。**只写不读**，仅供迁移与审计。 */
    val occurredOrder: Int,
    /**
     * 事件在时间线上的**逻辑位置**；同一事件的所有修订版本共享。**不可变**。
     *
     * `@ColumnInfo(defaultValue = "0")` 的字符串必须逐字为 `"0"`：
     * Room 迁移后会比对 `TableInfo.defaultValue`，与 `ALTER TABLE` 的 DEFAULT 不一致
     * 会直接抛 `Migration didn't properly handle match_events`。
     *
     * 刻意**不给 Kotlin 默认值**：有默认值等于允许漏传，而漏传正是 `logicalPosition = 0`
     * 脏行的来源（见 migration-plan §15.6-D2）。
     */
    @ColumnInfo(defaultValue = "0")
    val logicalPosition: Int,
    /** 该位置被修订的次数（`0` = 原始版本）。**不可变**。 */
    @ColumnInfo(defaultValue = "0")
    val revisionSeq: Int,
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
