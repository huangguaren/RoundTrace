package com.roundtrace.app.feature.record

import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.MatchSummary
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.RecordPhase
import com.roundtrace.app.core.model.RuleIssue
import com.roundtrace.app.core.model.TeamVoteChoice
import com.roundtrace.app.core.replay.TimelineEntry
import com.roundtrace.app.core.ui.component.QuestSlot
import com.roundtrace.app.core.ui.component.SaveStatus

/**
 * 现场录入的草稿（TDD §7.3 的 `ProposalDraft`）。
 *
 * 草稿只存在于 `match_drafts`，**用户确认后才会变成事件**。
 * 所有字段都允许未知：`null` = 未记录，`0` = 已知为零。
 */
data class ProposalDraft(
    val leaderSeat: Int? = null,
    val teamSeats: Set<Int> = emptySet(),
    val votesBySeat: Map<Int, TeamVoteChoice> = emptyMap(),
    /** 汇总票：null = 未知 */
    val approveCount: Int? = null,
    val rejectCount: Int? = null,
    /** 任务失败牌数：null = 未知，0 = 已知零失败牌 */
    val failCount: Int? = null,
    /** 用户直接观察到的任务成败 */
    val observedOutcome: MissionOutcome? = null
) {

    /** 未逐人记录且未填汇总时，票数全为未知。 */
    val hasVoteData: Boolean
        get() = votesBySeat.isNotEmpty() || approveCount != null || rejectCount != null

    /** 未知票数 = 人数 - 赞成 - 反对（不足 0 时取 0）。 */
    fun unknownVoteCount(playerCount: Int): Int? {
        if (!hasVoteData) return null
        val approve = approveCount ?: votesBySeat.values.count { it == TeamVoteChoice.APPROVE }
        val reject = rejectCount ?: votesBySeat.values.count { it == TeamVoteChoice.REJECT }
        return (playerCount - approve - reject).coerceAtLeast(0)
    }
}

/**
 * 当前任务的可展示模型。
 *
 * @param nextProposalNo 下一次提案的序号（1-based）
 * @param nextLeaderSeat 规则顺延后的下一任队长（仅用于提示，实际以录入为准）
 */
data class QuestUiModel(
    val questNo: Int,
    val expectedTeamSize: Int,
    val failThreshold: Int,
    val leaderSeat: Int?,
    val nextProposalNo: Int,
    val phase: RecordPhase,
    val status: MatchStatus,
    val consecutiveRejections: Int,
    val slots: List<QuestSlot>,
    val isFinished: Boolean,
    /** 当前任务是否已有 QUEST_STARTED（决定「开始第 N 任务」按钮是否可点，保证幂等） */
    val questStarted: Boolean = false,
    /** 规则/记录给出的当前队长（用于自动开始任务时的默认值） */
    val currentLeaderSeat: Int? = null
)

/** 撤销操作的反馈（UI 用 Snackbar 展示，文案走 stringResource）。 */
enum class UndoFeedback {
    DONE,
    NOTHING_TO_UNDO,
    FAILED
}

/**
 * 当前（或最近一次）提案的展示模型。
 *
 * 所有字段都来自重放结果，UI 直接渲染即可。
 */
data class ProposalUiModel(
    val questNo: Int,
    val proposalNo: Int,
    val leaderSeat: Int?,
    val teamSeats: List<Int>,
    val approveCount: Int?,
    val rejectCount: Int?,
    val decision: ProposalDecision?,
    val failCount: Int?,
    val missionOutcome: MissionOutcome?,
    val verified: Boolean
)

/**
 * 记录页 UiState（TDD §7.3 / TASKS.md T44①）。
 *
 * **全部字段都由 `observeBundle → ReplayEngine.replay` 派生**（T44④）：
 * ViewModel 不重复计算票数、门槛、成败——那些是 `AvalonRules` 的唯一职责。
 */
data class RecordUiState(
    val isLoading: Boolean = true,
    val match: MatchSummary? = null,
    val quest: QuestUiModel? = null,
    val draft: ProposalDraft = ProposalDraft(),
    val saveStatus: SaveStatus = SaveStatus.SAVED,
    val issues: List<RuleIssue> = emptyList(),
    val timeline: List<TimelineEntry> = emptyList(),
    val playerCount: Int = 0,
    val mySeat: Int? = null,
    /** 当前/最近一次提案（ProposalCard 用） */
    val currentProposal: ProposalUiModel? = null,
    /** 半数票（`playerCount / 2`），由 ViewModel 经规则侧口径给出 */
    val halfCount: Int = 0,
    /** 组队投票的可判定结论（来自 `AvalonRules.decideProposal`，UI 不重算） */
    val voteDecision: ProposalDecision = ProposalDecision.UNKNOWN,
    /** 赞成 + 反对是否超过人数 */
    val voteExceedsPlayers: Boolean = false,
    /** 任务结果的规则推导（来自 `AvalonRules.isMissionFailed`，UI 不重算） */
    val missionDerivedOutcome: MissionOutcome? = null,
    /** 最近一次撤销的结果；UI 消费后通过 [RecordAction.ClearUndoFeedback] 清空 */
    val undoFeedback: UndoFeedback? = null,
    val errorMessage: String? = null
) {

    /** 是否有阻断级问题（UI 应禁用「确认」类按钮）。 */
    val hasBlockingIssue: Boolean
        get() = issues.any { it.isBlocking }

    /** 队伍是否已选满（仅用于 UI 提示，不是规则判定）。 */
    val teamIsFull: Boolean
        get() {
            val expected = quest?.expectedTeamSize ?: return false
            return draft.teamSeats.size == expected
        }
}

/**
 * 记录页动作（TDD §7.3 + 现场录入必需项）。
 *
 * 覆盖 T44② 要求的 `SelectLeader` / `ToggleTeamSeat` / `SetVote` /
 * `SetMissionFailCount` / `SaveDraft` / `ConfirmProposal` / `Undo`，
 * 并补充汇总投票、决议、任务结果、观察成败四个动作。
 */
sealed interface RecordAction {

    data class SelectLeader(val seat: Int) : RecordAction

    data class ToggleTeamSeat(val seat: Int) : RecordAction

    data class SetVote(val seat: Int, val vote: TeamVoteChoice) : RecordAction

    data class SetVoteSummary(val approveCount: Int?, val rejectCount: Int?) : RecordAction

    data class SetMissionFailCount(val count: Int?) : RecordAction

    data class SetMissionObservedOutcome(val outcome: MissionOutcome?) : RecordAction

    /** 把草稿立即写入 `match_drafts`（不产生事件）。 */
    data object SaveDraft : RecordAction

    /** 提交组队提案（写 `TEAM_PROPOSED`）。 */
    data object ConfirmProposal : RecordAction

    /** 提交组队投票（写 `TEAM_VOTE_RECORDED`）。 */
    data object SaveVotes : RecordAction

    /** 提交组队决议（写 `TEAM_PROPOSAL_RESOLVED`）。 */
    data class ConfirmVoteDecision(val decision: ProposalDecision) : RecordAction

    /** 提交任务结果（写 `MISSION_RESOLVED`）。 */
    data object SaveMission : RecordAction

    /** 显式开始第 N 个任务（写 `QUEST_STARTED`）。幂等：已有则不重复写。 */
    data object StartQuest : RecordAction

    /** 撤销最近一条事件。 */
    data object Undo : RecordAction

    /** 清空撤销反馈（UI 展示完 Snackbar 后调用）。 */
    data object ClearUndoFeedback : RecordAction
}
