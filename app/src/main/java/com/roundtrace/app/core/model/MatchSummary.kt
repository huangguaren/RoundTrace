package com.roundtrace.app.core.model

/**
 * 首页对局摘要（ARCHITECTURE §2.2）。
 *
 * @param questsStarted 已开始的 `QUEST_STARTED` 数量（首页卡片「第 N 任务」）
 */
data class MatchSummary(
    val matchId: MatchId,
    val title: String,
    val playerCount: Int,
    val status: MatchStatus,
    val rulesetId: String,
    val updatedAtEpochMs: Long,
    val questsStarted: Int,
    val mySeat: SeatId?
) {
    /** 是否已结束（含弃局）。 */
    val isFinished: Boolean
        get() = status == MatchStatus.FINISHED_GOOD ||
            status == MatchStatus.FINISHED_EVIL ||
            status == MatchStatus.ABANDONED
}
