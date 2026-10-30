package com.roundtrace.app.core.database.dao

/**
 * 首页列表投影 DTO（ARCHITECTURE §5.1 / TASKS.md T31）。
 *
 * 字段名与 `MatchDao.observeSummaries` 的 SQL 别名**逐字一致**，Room 靠列名匹配构造。
 * `questsStarted` 由子查询算出，避免为每张卡片再查一次事件表。
 */
data class MatchSummaryRow(
    val matchId: String,
    val title: String,
    val playerCount: Int,
    val status: String,
    val rulesetId: String,
    val updatedAt: Long,
    val mySeat: Int?,
    val questsStarted: Int
)
