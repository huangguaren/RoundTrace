package com.roundtrace.app.feature.replay

import com.roundtrace.app.core.model.EvidenceFinding
import com.roundtrace.app.core.model.GamePosition
import com.roundtrace.app.core.model.KnowledgeSnapshot
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.replay.TimelineEntry

/**
 * 复盘 UiState（TASKS.md T57）。
 *
 * @param at 当前回放位置（唯一权威：只应用 `occurredOrder <= at` 的事件）
 * @param godViewBlocked 用户试图进入上帝视角但被拒绝（UI 需提示「对局结束后可查看」）
 */
data class ReplayUiState(
    val isLoading: Boolean = true,
    val matchTitle: String = "",
    val playerCount: Int = 0,
    val at: GamePosition = GamePosition.start(),
    val latestOrder: Int = 0,
    val mode: Perspective = Perspective.PUBLIC,
    val observerSeat: Int? = null,
    val snapshot: KnowledgeSnapshot? = null,
    val findings: List<EvidenceFinding> = emptyList(),
    val timeline: List<TimelineEntry> = emptyList(),
    val canOpenGodView: Boolean = false,
    /** 用户点了「上帝」但被拒绝 —— UI 弹提示后应清除 */
    val godViewBlocked: Boolean = false,
    val errorMessage: String? = null
) {

    /** 是否没有任何可用分析（T58⑤ 空态）。 */
    val hasNoFindings: Boolean
        get() = !isLoading && errorMessage == null && findings.isEmpty()

    /** 该视角下观察者的角色（用于「当前视角：1 号梅林」那行常驻说明）。 */
    val observerRoleName: String?
        get() = snapshot?.observerRole?.name
}

/** 复盘页动作。 */
sealed interface ReplayAction {

    /** 跳到某个事件的 order（`null` = 最新）。 */
    data class SelectPosition(val occurredOrder: Int?) : ReplayAction

    /** 跳到某个任务的最后一个事件。 */
    data class SelectQuest(val questNo: Int) : ReplayAction

    data class SelectPerspective(val mode: Perspective) : ReplayAction

    /** UI 展示完「上帝视角被拒绝」的提示后清除标志。 */
    data object ClearGodViewBlocked : ReplayAction
}
