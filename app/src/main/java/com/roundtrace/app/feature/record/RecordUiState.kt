package com.roundtrace.app.feature.record

import com.roundtrace.app.core.model.EventType
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
    val observedOutcome: MissionOutcome? = null,
    /**
     * 编辑会话的目标事件 id（RT-003 C-2）。
     *
     * `null` = 新增草稿；非空 = 编辑草稿。两者**分开持久化**（不同 screenKey），
     * 避免「编辑第一轮时的输入」被当成「当前轮的草稿」恢复出来。
     */
    val editTargetEventId: com.roundtrace.app.core.model.EventId? = null
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
 * 可编辑的事件类型**白名单**（主理人 2026-11-02 裁决：3 类）。
 *
 * | 事件 | 可编辑 | 理由 |
 * |---|---|---|
 * | `TEAM_PROPOSED` | ✅ | 组队提案，队伍成员录错是高频场景 |
 * | `TEAM_VOTE_RECORDED` | ✅ | 组队投票，票数录错 |
 * | `MISSION_RESOLVED` | ✅ | 任务结果（失败牌数 / 观察结果） |
 * | `TEAM_PROPOSAL_RESOLVED` | ❌ | 它是**现场通过/否决的既成事实**，允许直接改会让
 * 「改票数导致决议矛盾」与「直接改决议」两条路径打架；要改走显式「撤销再重录」 |
 * | `QUEST_STARTED` / `PLAYER_CLAIMED` / `PRIVATE_NOTE_ADDED` | ❌ | 轮次锚点 / 语义事实 / 私人内容 |
 *
 * 白名单放在状态层而不是散在 UI：**编辑入口的唯一判据**只能有一处，
 * 否则「时间线能点开、但保存时报类型不支持」这类不一致迟早出现。
 */
val EDITABLE_EVENT_TYPES: Set<EventType> = setOf(
    EventType.TEAM_PROPOSED,
    EventType.TEAM_VOTE_RECORDED,
    EventType.MISSION_RESOLVED
)

/**
 * 编辑会话（C-2）。
 *
 * `questNo` / `proposalNo` **取自被编辑的事件本身**，不是当前轮 ——
 * 这是「编辑第一轮不会写到当前轮」的关键：保存时用这里的值构造替换事件。
 */
data class EditSession(
    val targetEventId: com.roundtrace.app.core.model.EventId,
    val eventType: EventType,
    val questNo: Int,
    val proposalNo: Int?
)

/** 一次提交（新增或编辑）的结果，供 UI 决定「关弹窗」还是「保留输入 + 重试」。 */
sealed interface RecordCommitResult {

    /** 写库成功。UI 收到后应关闭弹窗、清草稿，并调用 [RecordAction.AcknowledgeCommit]。 */
    data object Succeeded : RecordCommitResult

    /**
     * 写库失败。UI **不得**关闭弹窗：保留用户输入、保留旧的有效事件，并给出重试入口。
     *
     * @param reason 稳定 key（`NOT_FOUND` / `INVALID_TARGET` / `WRITE_FAILED`），文案由 UI 映射
     */
    data class Failed(val reason: String) : RecordCommitResult
}

/**
 * 记录页 UiState（TDD §7.3 / TASKS.md T44①）。
 *
 * **全部字段都由 `observeBundle → ReplayEngine.replay` 派生**（T44④）：
 * ViewModel 不重复计算票数、门槛、成败——那些是 `AvalonRules` 的唯一职责。
 */
/** NX-06：修改早期事件后，后续受影响记录的提示 */
data class ImpactWarning(
    val title: String,
    val affectedItems: List<String>
)

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
    /** 座位号 → 自定义名字（从创建对局时带入）。 */
    val seatLabels: Map<Int, String?> = emptyMap(),
    /** 座位头像 URI。 */
    val seatAvatars: Map<Int, String?> = emptyMap(),
    /** 完整 participants（用于编辑座位名）。 */
    val participants: List<com.roundtrace.app.core.model.Participant> = emptyList(),
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
    /** 当前编辑会话；`null` = 新增模式（C-2） */
    val editSession: EditSession? = null,
    /**
     * 录入弹层当前作用的轮次（C-2）。
     *
     * 编辑态取**被编辑事件所在轮**，新增态取当前轮。
     * 弹层的期望队伍人数、失败牌门槛都必须按这个轮次算 ——
     * 否则「编辑第 1 轮」会拿当前轮（如第 3 轮）的队伍人数去校验，用户永远提交不了。
     */
    val sheetQuestNo: Int = 0,
    /** [sheetQuestNo] 对应的期望队伍人数（由 `AvalonRules` 计算，UI 不重算） */
    val sheetExpectedTeamSize: Int = 0,
    /** [sheetQuestNo] 对应的任务失败牌门槛（由 `AvalonRules` 计算，UI 不重算） */
    val sheetFailThreshold: Int = 1,
    /** 最近一次提交结果；UI 消费后通过 [RecordAction.AcknowledgeCommit] 清空 */
    val commitResult: RecordCommitResult? = null,
    /**
     * 恢复编辑草稿时发现「目标事件已失效」（C-2）。
     *
     * 此时**保留用户输入**（不静默丢弃），只是不进入编辑态，由 UI 提示「原记录已变更，请重新加载」。
     */
    val editTargetInvalidated: Boolean = false,
    /**
     * 有一次**事件提交**正在写库（F1）。
     *
     * 与 [saveStatus] 分开：`saveStatus == SAVING` 里还包含「草稿自动保存」，
     * 拿它禁用提交按钮会让用户在草稿保存的那几百毫秒里点不动确认。
     */
    val commitInFlight: Boolean = false,
    /** NX-06：正在编辑第 N 任务（导航式，不删除数据）；null = 正常记录模式 */
    val editingQuest: Int? = null,
    /** NX-06：修改早期事件后，后续受影响记录的提示；用户确认后才关闭 */
    val impactWarning: ImpactWarning? = null,
    val errorMessage: String? = null
) {

    /** 是否有阻断级问题（UI 应禁用「确认」类按钮）。 */
    val hasBlockingIssue: Boolean
        get() = issues.any { it.isBlocking }

    /** 是否处于编辑模式。 */
    val isEditing: Boolean
        get() = editSession != null

    /**
     * 保存中：UI 必须禁用提交按钮（防重复提交的第一道闸）。
     *
     * 只看 [commitInFlight]，**不看** `saveStatus`：后者包含草稿自动保存，
     * 用它禁用按钮会让「选完队员马上点确认」点不动（F1）。
     */
    val isSaving: Boolean
        get() = commitInFlight

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

    data class SelectLeader(val seat: Int?) : RecordAction

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

    /** 删除指定事件（时间线长按/点击删除）。 */
    data class DeleteEvent(val eventId: com.roundtrace.app.core.model.EventId) : RecordAction

    /** 更新座位名/头像（对局进行中编辑）。 */
    data class UpdateParticipants(val participants: List<com.roundtrace.app.core.model.Participant>) : RecordAction

    /** 回滚到指定任务：删除该任务之后的所有事件。 */
    data class RollbackToQuest(val questNo: Int) : RecordAction

    /** 清空撤销反馈（UI 展示完 Snackbar 后调用）。 */
    data object ClearUndoFeedback : RecordAction

    /**
     * 打开某条历史记录的编辑态（C-2）。
     *
     * **按 `eventId` 定位**，不再按「事件类型 + 当前轮」——后者在编辑历史轮次时必然写错轮。
     */
    data class OpenEdit(val eventId: com.roundtrace.app.core.model.EventId) : RecordAction

    /** 退出编辑态，丢弃编辑草稿（不写库）。 */
    data object CancelEdit : RecordAction

    /** UI 消费完 [RecordUiState.commitResult] 后清空（关弹窗/保留弹窗的分支也在 UI）。 */
    data object AcknowledgeCommit : RecordAction

    /** NX-06：进入/退出"编辑第 N 任务"模式（不删除数据，只导航） */
    data class SetEditingQuest(val questNo: Int?) : RecordAction

    /** NX-06：确认影响提示，退出编辑模式 */
    data object AcknowledgeImpactWarning : RecordAction
}
