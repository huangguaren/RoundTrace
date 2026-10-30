package com.roundtrace.app.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `matches` 表（ARCHITECTURE §4.2 / TASKS.md T28）。
 *
 * 列名与类型逐列对齐设计文档；**不要**在这里加领域概念字段（如 `isFinished`），
 * 派生信息一律由 `ReplayEngine` 从事件重算。
 *
 * @param configJson `GameConfig` 的完整 JSON（含 roleSlots / title / initialLeaderSeat）
 * @param title 冗余列，供列表排序/搜索；写时与 `configJson` 保持同步
 * @param revealedAt 身份揭晓完成时刻；**非空才允许上帝视角**
 * @param schemaVersion 导出 schema 版本快照
 */
@Entity(
    tableName = "matches",
    indices = [
        Index(value = ["status", "updatedAt"], name = "index_matches_status_updatedAt")
    ]
)
data class MatchEntity(
    @PrimaryKey
    val id: String,
    val gameType: String,
    val rulesetId: String,
    val rulesetVersion: Int,
    val presetId: String,
    val playerCount: Int,
    val configJson: String,
    val status: String,
    val title: String,
    val mySeat: Int?,
    val initialLeaderSeat: Int?,
    val createdAt: Long,
    val updatedAt: Long,
    val revealedAt: Long?,
    val schemaVersion: Int = 1
)
