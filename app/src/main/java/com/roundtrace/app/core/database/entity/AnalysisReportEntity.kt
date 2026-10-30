package com.roundtrace.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `analysis_reports` 表（ARCHITECTURE §4.6 / TASKS.md T29）。
 *
 * P0 **不使用**（复盘实时计算，简单优先）。表结构与唯一索引提前建好，避免 P1 改 schema。
 * **该表永远不是事实源**，可随时清空重算。
 */
@Entity(
    tableName = "analysis_reports",
    foreignKeys = [
        ForeignKey(
            entity = MatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["matchId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["matchId", "eventRevision", "perspectiveKey"], unique = true)
    ]
)
data class AnalysisReportEntity(
    @PrimaryKey
    val id: String,
    val matchId: String,
    /** = 事件条数或最新 occurredOrder */
    val eventRevision: Int,
    /** `PUBLIC` / `OBSERVER:1` / `GOD` */
    val perspectiveKey: String,
    val contentJson: String,
    val createdAt: Long
)
