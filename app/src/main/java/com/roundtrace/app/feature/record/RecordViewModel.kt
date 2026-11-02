package com.roundtrace.app.feature.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundtrace.app.core.data.MatchBundle
import com.roundtrace.app.core.data.MatchRepository
import com.roundtrace.app.core.model.EventFactory
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventType
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteRecorded
import kotlinx.coroutines.CancellationException
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.RecordPhase
import com.roundtrace.app.core.model.RuleIssue
import com.roundtrace.app.core.model.TeamVoteChoice
import com.roundtrace.app.core.replay.ReplayEngine
import com.roundtrace.app.core.replay.TimelineBuilder
import com.roundtrace.app.core.rules.GameReducer
import com.roundtrace.app.core.rules.GameRulesRegistry
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.rules.avalon.AvalonRules
import com.roundtrace.app.core.ui.component.QuestSlot
import com.roundtrace.app.core.ui.component.SaveStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 现场记录 ViewModel（TASKS.md T44）。
 *
 * 四条硬约束：
 * 1. 状态**全部**由 `observeBundle → ReplayEngine.replay` 派生，`onAction` 是唯一入口（T44③④）；
 * 2. 本类**不重复计算**票数、门槛、成败——判定一律问 `AvalonRules`（投票/判定单一入口）；
 * 3. 草稿先落 `match_drafts`（300ms 防抖），用户确认后才写 `match_events`（T49①）；
 * 4. 写入失败必须让 `SaveStatus` 变成 `FAILED`，不能假装已保存；
 * 5. 页面重建时必须把未提交的草稿捡回来（RT-006），但**不得覆盖用户已输入的内容**。
 */
class RecordViewModel(
    private val matchId: MatchId,
    private val repository: MatchRepository,
    private val gameRulesRegistry: GameRulesRegistry,
    private val reducer: GameReducer,
    private val rules: AvalonRules,
    private val eventFactory: EventFactory
) : ViewModel() {

    private val draftFlow = MutableStateFlow(ProposalDraft())
    private val saveStatusFlow = MutableStateFlow(SaveStatus.SAVED)
    private val undoFeedbackFlow = MutableStateFlow<UndoFeedback?>(null)

    /** 当前编辑会话；`null` = 新增模式（C-2）。 */
    private val editSessionFlow = MutableStateFlow<EditSession?>(null)

    /** 最近一次提交结果；UI 消费后清空。 */
    private val commitResultFlow = MutableStateFlow<RecordCommitResult?>(null)

    /** 恢复编辑草稿时发现目标已失效。 */
    private val editInvalidatedFlow = MutableStateFlow(false)

    /**
     * 「一次提交正在写库」。
     *
     * **不能拿 `saveStatus == SAVING` 兼任并发闸门**（F1）：草稿自动保存（`persistDraftNow`）
     * 与事件提交共用同一个 `saveStatus`，于是「选完队员马上点确认」时，
     * 提交会被草稿保存的 SAVING 窗口静默吞掉——用户以为保存成功，事件其实没写进库。
     * 现在两者彻底分开：`saveStatus` 只用于展示，本标记才是提交的闸门。
     */
    private var isCommitting = false

    /** [isCommitting] 的 UI 投影：只用于禁用提交按钮，不参与任何并发判断。 */
    private val commitInFlightFlow = MutableStateFlow(false)

    /** NX-06：正在编辑第 N 任务（导航式，不删除数据） */
    private val editingQuestFlow = MutableStateFlow<Int?>(null)

    /** NX-06：修改早期事件后的影响提示 */
    private val impactWarningFlow = MutableStateFlow<ImpactWarning?>(null)

    /** NX-06：待确认的编辑（先预览，确认后才写库） */
    private val pendingEditFlow = MutableStateFlow<PendingEdit?>(null)

    private var draftSaveJob: Job? = null

    /**
     * 用户是否**动过**表单（RT-006 A-1）。
     *
     * 不能用「`draftFlow.value == ProposalDraft()`」来判断「用户没输入」：
     * 用户选错人 → 全部取消 → 此刻草稿恰好是空的，但那是一次**有效操作**，
     * 旧草稿若此刻覆盖进来，等于把「我刚清空」这件事抹掉。
     *
     * 只有 `updateDraft {}`（唯一被 `onAction` 调用的入口）会置 true。
     */
    private var draftTouchedByUser = false

    val uiState: StateFlow<RecordUiState> =
        combine(
            repository.observeBundle(matchId),
            draftFlow,
            saveStatusFlow,
            undoFeedbackFlow,
            editSessionFlow,
            commitResultFlow,
            editInvalidatedFlow,
            commitInFlightFlow,
            editingQuestFlow,
            impactWarningFlow
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            buildUiState(
                bundle = values[0] as MatchBundle?,
                draft = values[1] as ProposalDraft,
                saveStatus = values[2] as SaveStatus,
                undoFeedback = values[3] as UndoFeedback?,
                editSession = values[4] as EditSession?,
                commitResult = values[5] as RecordCommitResult?,
                editTargetInvalidated = values[6] as Boolean,
                commitInFlight = values[7] as Boolean,
                editingQuest = values[8] as Int?,
                impactWarning = values[9] as ImpactWarning?
            )
        }
            .catch { throwable ->
                emit(
                    RecordUiState(
                        isLoading = false,
                        errorMessage = throwable.message ?: "加载对局失败"
                    )
                )
            }
            .stateIn(
                scope = viewModelScope,
                // Eagerly：单场对局的派生成本极低，且避免「没有订阅者时状态不前进」的陷阱
                started = SharingStarted.Eagerly,
                initialValue = RecordUiState(isLoading = true)
            )

    init {
        // RT-006：页面重建（进程被杀 / 切后台被回收 / 旋屏）后把草稿捡回来
        restoreDraft()
        // C-2：编辑草稿单独恢复，并且**先核实目标事件仍然有效**
        restoreEditDraft()
    }

    // ————————————————————————————————————————————————————————
    // 草稿恢复（RT-006）
    // ————————————————————————————————————————————————————————

    /**
     * 从 `match_drafts` 恢复上次未提交的草稿。
     *
     * 四条硬约束（都是「不做就会出事」的）：
     *
     * 1. **失败当作没有草稿**：`loadDraft` 抛异常、JSON 损坏、字段缺失，
     *    一律静默回落到空草稿。草稿是「便利功能」，不能因为它让记录页打不开。
     * 2. **只在用户尚未动过表单时写入**：`loadDraft` 是挂起调用，返回时用户可能已经在选人。
     *    若此时无条件覆盖，用户刚点的三个座位会被「上次的草稿」擦掉——
     *    这是比「草稿没恢复」严重得多的 bug。
     *    判据是 [draftTouchedByUser] 标记，**不是**「草稿是否为空」：
     *    用户「选中又全部取消」同样是一次有效操作，不能被旧草稿覆盖。
     * 3. **恢复不得触发保存**：这里刻意**不**调 `scheduleDraftSave()`。
     *    否则刚读出来的内容会被原样写回，刷新 `updatedAt`，
     *    并且让「恢复没有副作用」这件事变得无法断言。
     * 4. **空草稿不写入**：仓库里存着 `"{}"`（上次清空后留下的）时，
     *    恢复成空草稿没有任何意义，还会白白让 `draft` 走一次发射。
     */
    private fun restoreDraft() {
        viewModelScope.launch {
            val json = runCatching { repository.loadDraft(matchId, DRAFT_KEY) }.getOrNull()
            // decode 自身对 null / 空串 / 损坏 JSON 都返回空草稿，不会抛
            val restored = runCatching { ProposalDraftCodec.decode(json) }.getOrNull()
                ?: return@launch
            if (restored == ProposalDraft()) return@launch

            // 用户已经在异步窗口内动过手 → 以用户输入为准。
            // 注意判据是「标记」而不是「草稿是否为空」：用户可能已经输入又清空。
            if (draftTouchedByUser) return@launch

            // 两条恢复是并发跑的：编辑草稿更「具体」，已经进入编辑态时不能让新增草稿覆盖它
            if (editSessionFlow.value != null) return@launch

            draftFlow.value = restored
        }
    }

    // ————————————————————————————————————————————————————————
    // 唯一入口
    // ————————————————————————————————————————————————————————

    fun onAction(action: RecordAction) {
        when (action) {
            is RecordAction.SelectLeader -> updateDraft { it.copy(leaderSeat = action.seat) }

            is RecordAction.ToggleTeamSeat -> updateDraft { draft ->
                val seats = if (draft.teamSeats.contains(action.seat)) {
                    draft.teamSeats - action.seat
                } else {
                    draft.teamSeats + action.seat
                }
                draft.copy(teamSeats = seats)
            }

            is RecordAction.SetVote -> updateDraft { draft ->
                draft.copy(votesBySeat = draft.votesBySeat + (action.seat to action.vote))
            }

            is RecordAction.SetVoteSummary -> updateDraft {
                it.copy(approveCount = action.approveCount, rejectCount = action.rejectCount)
            }

            is RecordAction.SetMissionFailCount -> updateDraft {
                it.copy(failCount = action.count)
            }

            is RecordAction.SetMissionObservedOutcome -> updateDraft {
                it.copy(observedOutcome = action.outcome)
            }

            RecordAction.SaveDraft -> persistDraftNow()

            RecordAction.StartQuest -> startQuest()

            RecordAction.ConfirmProposal -> confirmProposal()

            RecordAction.SaveVotes -> saveVotes()

            is RecordAction.ConfirmVoteDecision -> confirmVoteDecision(action.decision)

            RecordAction.SaveMission -> saveMission()

            RecordAction.Undo -> undo()

            is RecordAction.DeleteEvent -> deleteEvent(action.eventId)

            is RecordAction.UpdateParticipants -> updateParticipants(action.participants)

            is RecordAction.RollbackToQuest -> rollbackToQuest(action.questNo)
            is RecordAction.SetEditingQuest -> editingQuestFlow.value = action.questNo
            is RecordAction.AcknowledgeImpactWarning -> {
                impactWarningFlow.value = null
                editingQuestFlow.value = null
            }
            is RecordAction.ConfirmPendingEdit -> {
                val pending = pendingEditFlow.value ?: return
                pendingEditFlow.value = null
                impactWarningFlow.value = null
                // 真正写库
                viewModelScope.launch {
                    saveStatusFlow.value = SaveStatus.SAVING
                    val result = runCatchingCancellable {
                        repository.reviseEvent(matchId, pending.targetEventId, pending.replacement)
                    }
                    if (result.isSuccess) {
                        saveStatusFlow.value = SaveStatus.SAVED
                        editSessionFlow.value = null
                        commitResultFlow.value = RecordCommitResult.Succeeded
                    } else {
                        saveStatusFlow.value = SaveStatus.FAILED
                    }
                }
            }
            is RecordAction.CancelPendingEdit -> {
                pendingEditFlow.value = null
                impactWarningFlow.value = null
            }

            RecordAction.ClearUndoFeedback -> undoFeedbackFlow.value = null

            is RecordAction.OpenEdit -> openEdit(action.eventId)

            RecordAction.CancelEdit -> cancelEdit()

            RecordAction.AcknowledgeCommit -> commitResultFlow.value = null
        }
    }

    // ————————————————————————————————————————————————————————
    // 动作实现
    // ————————————————————————————————————————————————————————

    private fun updateDraft(transform: (ProposalDraft) -> ProposalDraft) {
        draftTouchedByUser = true
        draftFlow.value = transform(draftFlow.value)
        scheduleDraftSave()
    }

    private fun scheduleDraftSave() {
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(DRAFT_DEBOUNCE_MS)
            persistDraftNow()
        }
    }

    private fun persistDraftNow() {
        val draft = draftFlow.value
        val json = ProposalDraftCodec.encode(draft)
        // 编辑草稿与新增草稿分开落库（见 DRAFT_KEY_EDIT 的说明）
        val key = if (draft.editTargetEventId != null) DRAFT_KEY_EDIT else DRAFT_KEY
        viewModelScope.launch {
            saveStatusFlow.value = SaveStatus.SAVING
            val result = runCatchingCancellable { repository.saveDraft(matchId, key, json) }
            // 提交在飞时，草稿保存的收尾**不得**覆盖提交的展示状态：
            // 草稿写完就显示「已保存」，正是 F1 里「用户以为保存成功」的一半原因。
            if (!isCommitting) {
                saveStatusFlow.value = if (result.isSuccess) SaveStatus.SAVED else SaveStatus.FAILED
            }
        }
    }

    /**
     * 显式开始当前任务。
     *
     * 幂等保证（主理人 M4 要求）：`QT_STARTED` 已存在时直接返回，
     * 因此「先点开始任务、再提交提案」不会产生两条 `QUEST_STARTED`
     * （提案侧的自动补建也会因为已存在而跳过）。
     */
    private fun startQuest() {
        val state = uiState.value
        val quest = state.quest ?: return
        if (quest.questStarted) return
        val leader = state.draft.leaderSeat ?: quest.currentLeaderSeat ?: quest.leaderSeat ?: 1
        commit(eventFactory.questStarted(questNo = quest.questNo, leaderSeat = leader), null)
    }

    private fun confirmProposal() {
        val state = uiState.value
        val leader = state.draft.leaderSeat ?: return
        val team = state.draft.teamSeats.sorted()
        if (team.isEmpty()) return

        // 编辑态：轮次与提案号取自**被编辑的事件**，不是当前轮
        val edit = state.editSession?.takeIf { it.eventType == EventType.TEAM_PROPOSED }
        val questNo: Int
        val proposalNo: Int
        if (edit != null) {
            questNo = edit.questNo
            proposalNo = edit.proposalNo ?: return
        } else {
            val quest = state.quest ?: return
            questNo = quest.questNo
            proposalNo = quest.nextProposalNo
        }

        commit(
            event = eventFactory.teamProposed(
                questNo = questNo,
                proposalNo = proposalNo,
                leaderSeat = leader,
                teamSeats = team
            ),
            editTargetEventId = edit?.targetEventId
        )
    }

    private fun saveVotes() {
        val state = uiState.value
        val draft = state.draft
        if (!draft.hasVoteData) return

        val edit = state.editSession?.takeIf { it.eventType == EventType.TEAM_VOTE_RECORDED }
        val questNo: Int
        val proposalNo: Int
        if (edit != null) {
            questNo = edit.questNo
            proposalNo = edit.proposalNo ?: return
        } else {
            val quest = state.quest ?: return
            questNo = quest.questNo
            proposalNo = quest.nextProposalNo - 1
        }

        // 注意：改票**不碰**同轮的 TeamProposalResolved。
        // 若新票数与既有决议矛盾，交给 reducer 的 DECISION_VOTE_CONFLICT 浮出来 + UI 提示复核，
        // 而不是静默改写现场事实（主理人 C-2 裁决）。
        commit(
            event = eventFactory.teamVoteRecorded(
                questNo = questNo,
                proposalNo = proposalNo,
                playerCountAtVote = state.playerCount,
                votesBySeat = draft.votesBySeat.ifEmpty { null },
                approveCount = draft.approveCount,
                rejectCount = draft.rejectCount,
                unknownVoteCount = draft.unknownVoteCount(state.playerCount)
            ),
            editTargetEventId = edit?.targetEventId
        )
    }

    private fun confirmVoteDecision(decision: ProposalDecision) {
        val state = uiState.value
        val quest = state.quest ?: return
        val draft = state.draft
        val event = eventFactory.teamProposalResolved(
            questNo = quest.questNo,
            proposalNo = quest.nextProposalNo - 1,
            decision = decision,
            playerCountAtVote = state.playerCount,
            approveCount = draft.approveCount
                ?: draft.votesBySeat.values.count { it == TeamVoteChoice.APPROVE }
                    .takeIf { draft.votesBySeat.isNotEmpty() },
            rejectCount = draft.rejectCount
                ?: draft.votesBySeat.values.count { it == TeamVoteChoice.REJECT }
                    .takeIf { draft.votesBySeat.isNotEmpty() }
        )
        // 决议事件（TEAM_PROPOSAL_RESOLVED）**不可编辑**，只能新增；要改走「撤销再重录」
        commit(event, null)
    }

    private fun saveMission() {
        val state = uiState.value
        val draft = state.draft

        val edit = state.editSession?.takeIf { it.eventType == EventType.MISSION_RESOLVED }
        val questNo: Int
        val expectedTeamSize: Int
        if (edit != null) {
            questNo = edit.questNo
            expectedTeamSize = rules.expectedTeamSizeFor(state.playerCount, questNo)
        } else {
            val quest = state.quest ?: return
            questNo = quest.questNo
            expectedTeamSize = quest.expectedTeamSize
        }

        commit(
            event = eventFactory.missionResolved(
                questNo = questNo,
                teamSizeExpected = expectedTeamSize,
                teamSeats = draft.teamSeats.takeIf { it.isNotEmpty() }?.sorted(),
                failCount = draft.failCount,
                observedOutcome = draft.observedOutcome
            ),
            editTargetEventId = edit?.targetEventId
        )
    }

    /**
     * 撤销最近一条事件。
     *
     * 撤销是「事实修正」，不是删除：事件被标记 `retracted = 1`，重放时自动跳过，
     * 审计链仍完整（ARCHITECTURE §9.3-12）。
     */
    private fun undo() {
        viewModelScope.launch {
            val bundle = repository.getBundle(matchId)
            val target = bundle?.events?.lastOrNull()
            if (target == null) {
                undoFeedbackFlow.value = UndoFeedback.NOTHING_TO_UNDO
                return@launch
            }
            saveStatusFlow.value = SaveStatus.SAVING
            val result = runCatching { repository.retractEvent(matchId, target.eventId) }
            val success = result.isSuccess
            saveStatusFlow.value = if (success) SaveStatus.SAVED else SaveStatus.FAILED
            undoFeedbackFlow.value = if (success) UndoFeedback.DONE else UndoFeedback.FAILED
        }
    }

    /** 删除指定历史事件（时间线点删除按钮）。 */
    private fun deleteEvent(eventId: com.roundtrace.app.core.model.EventId) {
        viewModelScope.launch {
            saveStatusFlow.value = SaveStatus.SAVING
            val result = runCatching { repository.retractEvent(matchId, eventId) }
            saveStatusFlow.value = if (result.isSuccess) SaveStatus.SAVED else SaveStatus.FAILED
        }
    }

    /** 更新座位名/头像。 */
    private fun updateParticipants(participants: List<com.roundtrace.app.core.model.Participant>) {
        viewModelScope.launch {
            runCatching { repository.updateParticipants(matchId, participants) }
        }
    }

    /** 回滚到指定任务：事务化撤销该任务最后逻辑位置之后的所有事件。 */
    private fun rollbackToQuest(questNo: Int) {
        if (isCommitting) return
        isCommitting = true
        commitInFlightFlow.value = true
        viewModelScope.launch {
            try {
                saveStatusFlow.value = SaveStatus.SAVING
                val bundle = runCatchingCancellable { repository.getBundle(matchId) }.getOrNull()
                if (bundle == null) {
                    saveStatusFlow.value = SaveStatus.FAILED
                    undoFeedbackFlow.value = UndoFeedback.FAILED
                    return@launch
                }
                val cutoffOrder = bundle.events.filter { event ->
                    when (event) {
                        is com.roundtrace.app.core.model.QuestStarted -> event.questNo == questNo
                        is com.roundtrace.app.core.model.TeamProposed -> event.questNo == questNo
                        is com.roundtrace.app.core.model.TeamVoteRecorded -> event.questNo == questNo
                        is com.roundtrace.app.core.model.TeamProposalResolved -> event.questNo == questNo
                        is com.roundtrace.app.core.model.MissionResolved -> event.questNo == questNo
                        else -> false
                    }
                }.maxOfOrNull { it.occurredOrder }
                // 找不到目标不能回退为 0，否则会把整局撤空。
                if (cutoffOrder == null) {
                    saveStatusFlow.value = SaveStatus.FAILED
                    undoFeedbackFlow.value = UndoFeedback.FAILED
                    return@launch
                }
                val result = runCatchingCancellable { repository.retractAfterOrder(matchId, cutoffOrder) }
                val success = result.isSuccess && result.getOrNull()?.isSuccess == true
                saveStatusFlow.value = if (success) SaveStatus.SAVED else SaveStatus.FAILED
                undoFeedbackFlow.value = if (success) UndoFeedback.DONE else UndoFeedback.FAILED
            } finally {
                isCommitting = false
                commitInFlightFlow.value = false
            }
        }
    }

    /**
     * 提交一次写入：**编辑态走 `reviseEvent`，新增走 `appendEvent`**（C-2）。
     *
     * 三条硬约束：
     * 1. **保存中不再接受新的提交**（防重复提交的第一道闸；仓储侧还有并发保护做最终防线）；
     * 2. 失败时**保留用户输入与旧的有效事件**，只把 `SaveStatus` 置 FAILED 并给出重试入口 ——
     *    绝不假装已保存，也绝不静默丢弃输入；
     * 3. 成功后才清草稿、退出编辑态、发 `Succeeded`（UI 据此关弹窗）。
     */
    private fun commit(event: MatchEvent, editTargetEventId: EventId?) {
        // NX-06：历史浏览模式下禁止新增事件（只能编辑现有条目）
        if (editingQuestFlow.value != null && editTargetEventId == null) {
            return
        }
        // 防重复提交：判据是「有一次提交在飞」，**不是** saveStatus。
        // 若看 saveStatus，草稿自动保存的 SAVING 窗口会把这次提交直接吞掉（F1）。
        if (isCommitting) return
        isCommitting = true
        commitInFlightFlow.value = true
        viewModelScope.launch {
            try {
                saveStatusFlow.value = SaveStatus.SAVING
                val result = runCatchingCancellable {
                    if (editTargetEventId != null) {
                        repository.reviseEvent(matchId, editTargetEventId, event)
                    } else {
                        repository.appendEvent(matchId, event)
                    }
                }
                val appendResult = result.getOrNull()
                val success = result.isSuccess && appendResult?.isSuccess == true

                if (!success) {
                    saveStatusFlow.value = SaveStatus.FAILED
                    commitResultFlow.value = RecordCommitResult.Failed(
                        reason = appendResult?.error?.name ?: "WRITE_FAILED"
                    )
                    return@launch
                }

                saveStatusFlow.value = SaveStatus.SAVED
                editSessionFlow.value = null
                editInvalidatedFlow.value = false

                // NX-06：编辑模式下修改早期事件后，提示后续受影响的记录
                if (editingQuestFlow.value != null && editTargetEventId != null) {
                    val warning = when (event) {
                        is com.roundtrace.app.core.model.MissionResolved -> ImpactWarning(
                            title = "修改任务结果的影响",
                            affectedItems = listOf(
                                "后续所有任务的胜负计数已自动重算",
                                "如需调整后续记录，请继续在时间线中编辑"
                            )
                        )
                        is com.roundtrace.app.core.model.TeamProposed -> ImpactWarning(
                            title = "修改提案的影响",
                            affectedItems = listOf(
                                "该任务的投票结果已自动重算",
                                "如需修改投票或任务结果，请继续在时间线中编辑"
                            )
                        )
                        else -> null
                    }
                    impactWarningFlow.value = warning
                }

                // 事件已落库 → 清空本轮表单内容；任务开始事件不保留队长（新任务重选）
                val keepLeader = event !is com.roundtrace.app.core.model.QuestStarted
                draftFlow.value = ProposalDraft(
                    leaderSeat = if (keepLeader) draftFlow.value.leaderSeat else null
                )
                draftTouchedByUser = false
                runCatching { repository.clearDraft(matchId, DRAFT_KEY) }
                runCatching { repository.clearDraft(matchId, DRAFT_KEY_EDIT) }
                commitResultFlow.value = RecordCommitResult.Succeeded
            } finally {
                // 协程被取消（页面销毁）时也要放闸，否则这一局再也提交不了
                isCommitting = false
                commitInFlightFlow.value = false
            }
        }
    }

    /**
     * `runCatching` 的**可取消版本**。
     *
     * 标准 `runCatching` 会把 `CancellationException` 当成普通失败吞掉，
     * 于是「页面被销毁 → 协程取消」会被误报成「保存失败」，用户看到一条假错误。
     * 这里显式重抛取消异常，保证取消能正常传播。
     */
    private suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }

    // ————————————————————————————————————————————————————————
    // 编辑会话（C-2）
    // ————————————————————————————————————————————————————————

    /**
     * 按 `eventId` 打开编辑态并**预填原值**。
     *
     * 不用「事件类型 + 当前轮」定位：那样编辑历史轮次时必然写到当前轮。
     */
    private fun openEdit(eventId: EventId) {
        viewModelScope.launch {
            val bundle = repository.getBundle(matchId)
            val target = bundle?.events?.firstOrNull { it.eventId == eventId && !it.retracted }

            // 目标不存在 / 已失效 / 类型不可编辑 → 不进编辑态，由 UI 提示「请重新加载」
            if (target == null || target.type !in EDITABLE_EVENT_TYPES) {
                editSessionFlow.value = null
                editInvalidatedFlow.value = true
                return@launch
            }

            val questNo = target.questNo ?: run {
                editInvalidatedFlow.value = true
                return@launch
            }
            editSessionFlow.value = EditSession(
                targetEventId = target.eventId,
                eventType = target.type,
                questNo = questNo,
                proposalNo = proposalNoOf(target)
            )
            draftFlow.value = prefillFrom(target)
            // 预填不算「用户输入」：随后若恢复编辑草稿，仍允许覆盖
            draftTouchedByUser = false
            editInvalidatedFlow.value = false
        }
    }

    /**
     * 取事件的提案号。
     *
     * `MatchEvent` 基接口只有 `questNo`，`proposalNo` 只存在于部分子类型上，
     * 所以必须按类型取而不是直接访问。
     */
    private fun proposalNoOf(event: MatchEvent): Int? = when (event) {
        is TeamProposed -> event.proposalNo
        is TeamVoteRecorded -> event.proposalNo
        is com.roundtrace.app.core.model.TeamProposalResolved -> event.proposalNo
        is com.roundtrace.app.core.model.PlayerClaimed -> event.proposalNo
        else -> null
    }

    /** 原事件 → 预填草稿。三类可编辑事件各有自己的字段。 */
    private fun prefillFrom(event: MatchEvent): ProposalDraft = when (event) {
        is TeamProposed -> ProposalDraft(
            leaderSeat = event.leaderSeat,
            teamSeats = event.teamSeats.toSet(),
            editTargetEventId = event.eventId
        )

        is TeamVoteRecorded -> ProposalDraft(
            votesBySeat = event.votesBySeat ?: emptyMap(),
            approveCount = event.approveCount,
            rejectCount = event.rejectCount,
            editTargetEventId = event.eventId
        )

        is MissionResolved -> ProposalDraft(
            teamSeats = event.teamSeats?.toSet() ?: emptySet(),
            failCount = event.failCount,
            observedOutcome = event.observedOutcome,
            editTargetEventId = event.eventId
        )

        else -> ProposalDraft()
    }

    /** 退出编辑态，丢弃编辑草稿。**不写库**。 */
    private fun cancelEdit() {
        editSessionFlow.value = null
        editInvalidatedFlow.value = false
        draftFlow.value = ProposalDraft()
        draftTouchedByUser = false
        viewModelScope.launch {
            runCatchingCancellable { repository.clearDraft(matchId, DRAFT_KEY_EDIT) }
        }
    }

    /**
     * 恢复**编辑草稿**（与新增草稿分开的 screenKey）。
     *
     * 恢复前必须**核实目标事件仍然有效**：
     * 目标已被撤销/删除时，保留用户输入但**不进入编辑态**，并置 `editTargetInvalidated`
     * 让 UI 提示「原记录已变更，请重新加载」——不静默丢弃用户刚录入的内容。
     */
    private fun restoreEditDraft() {
        viewModelScope.launch {
            val json = runCatchingCancellable {
                repository.loadDraft(matchId, DRAFT_KEY_EDIT)
            }.getOrNull() ?: return@launch

            val draft = runCatchingCancellable { ProposalDraftCodec.decode(json) }.getOrNull()
                ?: return@launch
            val targetEventId = draft.editTargetEventId ?: return@launch

            val bundle = runCatchingCancellable { repository.getBundle(matchId) }.getOrNull()
            val target = bundle?.events?.firstOrNull {
                it.eventId == targetEventId && !it.retracted
            }

            // 用户已经在异步窗口内动过手 → 以用户输入为准（与 RT-006 同一原则）
            if (draftTouchedByUser) return@launch

            draftFlow.value = draft
            if (target == null || target.type !in EDITABLE_EVENT_TYPES) {
                editSessionFlow.value = null
                editInvalidatedFlow.value = true
                return@launch
            }

            val questNo = target.questNo ?: return@launch
            editSessionFlow.value = EditSession(
                targetEventId = target.eventId,
                eventType = target.type,
                questNo = questNo,
                proposalNo = proposalNoOf(target)
            )
            editInvalidatedFlow.value = false
        }
    }

    // ————————————————————————————————————————————————————————
    // 状态派生
    // ————————————————————————————————————————————————————————

    private fun buildUiState(
        bundle: MatchBundle?,
        draft: ProposalDraft,
        saveStatus: SaveStatus,
        undoFeedback: UndoFeedback?,
        editSession: EditSession? = null,
        commitResult: RecordCommitResult? = null,
        editTargetInvalidated: Boolean = false,
        commitInFlight: Boolean = false,
        editingQuest: Int? = null,
        impactWarning: ImpactWarning? = null
    ): RecordUiState {
        if (bundle == null) {
            return RecordUiState(isLoading = false, errorMessage = "对局不存在或已被删除")
        }

        val playerCount = bundle.config.playerCount
        val reduced = ReplayEngine(reducer).replay(bundle.toReconstructedMatch())
        val gameState = reduced.state

        val activeQuestNo = when (gameState.phase) {
            RecordPhase.NOT_STARTED, RecordPhase.QUEST_RESOLVED -> gameState.currentQuestNo + 1
            else -> gameState.currentQuestNo
        }.coerceIn(1, AvalonPreset.QUEST_COUNT)

        // NX-06：浏览历史任务时用历史任务号，真实进度 activeQuestNo 不变
        val viewQuestNo = editingQuest ?: activeQuestNo

        val expectedTeamSize = rules.expectedTeamSizeFor(playerCount, viewQuestNo)
        val failThreshold = if (playerCount in AvalonPreset.MIN_PLAYERS..AvalonPreset.MAX_PLAYERS) {
            rules.failThreshold(playerCount, viewQuestNo)
        } else {
            1
        }

        val currentQuest = gameState.quests.firstOrNull { it.questNo == viewQuestNo }
        val nextProposalNo = if (currentQuest == null) {
            1
        } else {
            currentQuest.latestProposalNo + 1
        }

        val quest = QuestUiModel(
            questNo = viewQuestNo,
            expectedTeamSize = expectedTeamSize,
            failThreshold = failThreshold,
            leaderSeat = draft.leaderSeat,
            nextProposalNo = nextProposalNo,
            phase = gameState.phase,
            status = gameState.status,
            consecutiveRejections = gameState.consecutiveRejections,
            slots = gameState.quests.map { QuestSlot(it.questNo, it.outcome, it.verified) },
            isFinished = gameState.isFinished,
            questStarted = currentQuest != null,
            currentLeaderSeat = gameState.currentLeaderSeat
        )

        // 草稿层校验（不阻断，只提示）+ 重放层问题
        //
        // C-2：编辑态下「队伍人数/门槛」必须按**被编辑的轮次**算。
        // 否则编辑第 1 轮（如 5 人局 2 人队伍）却用当前第 3 轮（3 人队伍）的规则校验，
        // 会凭空报一条「人数不符」，用户也永远点不亮提交按钮。
        val sheetQuestNo = editSession?.questNo ?: viewQuestNo
        val sheetExpectedTeamSize = rules.expectedTeamSizeFor(playerCount, sheetQuestNo)
        val sheetFailThreshold = if (playerCount in AvalonPreset.MIN_PLAYERS..AvalonPreset.MAX_PLAYERS) {
            rules.failThreshold(playerCount, sheetQuestNo)
        } else {
            1
        }

        val draftIssues: List<RuleIssue> = rules.validateTeamDraft(
            teamSeats = draft.teamSeats.sorted(),
            playerCount = playerCount,
            questNo = sheetQuestNo
        )

        val issues = (draftIssues + gameState.issues + reduced.issues)
            .distinctBy { it.code }

        // —— 以下三个派生值一律**调用规则**得到，UI 与 ViewModel 都不重算 ——
        val approveForDecision = draft.approveCount
            ?: draft.votesBySeat.values.count { it == TeamVoteChoice.APPROVE }
                .takeIf { draft.votesBySeat.isNotEmpty() }
        val rejectForDecision = draft.rejectCount
            ?: draft.votesBySeat.values.count { it == TeamVoteChoice.REJECT }
                .takeIf { draft.votesBySeat.isNotEmpty() }
        val voteDecision = rules.decideProposal(
            approveCount = approveForDecision,
            rejectCount = rejectForDecision,
            unknownVoteCount = draft.unknownVoteCount(playerCount),
            playerCount = playerCount
        )
        val voteExceedsPlayers = (approveForDecision ?: 0) + (rejectForDecision ?: 0) > playerCount

        val missionDerivedOutcome: MissionOutcome? = draft.failCount?.let { failCount ->
            if (rules.isMissionFailed(failCount, playerCount, sheetQuestNo)) {
                MissionOutcome.FAILED
            } else {
                MissionOutcome.SUCCESS
            }
        }

        val proposalModel = currentQuest?.proposals?.lastOrNull()?.let { proposal ->
            ProposalUiModel(
                questNo = proposal.questNo,
                proposalNo = proposal.proposalNo,
                leaderSeat = proposal.leaderSeat,
                teamSeats = proposal.teamSeats,
                approveCount = proposal.approveCount,
                rejectCount = proposal.rejectCount,
                decision = proposal.decision,
                failCount = currentQuest.failCount,
                missionOutcome = currentQuest.outcome,
                verified = currentQuest.verified
            )
        }

        return RecordUiState(
            isLoading = false,
            match = bundle.summary,
            quest = quest,
            draft = draft,
            saveStatus = saveStatus,
            issues = issues,
            timeline = TimelineBuilder.build(bundle.events),
            playerCount = playerCount,
            mySeat = bundle.config.mySeat,
            seatLabels = bundle.participants.associate { it.seat to it.displayName },
            seatAvatars = bundle.participants.associate { it.seat to it.avatarUri },
            participants = bundle.participants,
            currentProposal = proposalModel,
            halfCount = playerCount / 2,
            voteDecision = voteDecision,
            voteExceedsPlayers = voteExceedsPlayers,
            missionDerivedOutcome = missionDerivedOutcome,
            undoFeedback = undoFeedback,
            editSession = editSession,
            sheetQuestNo = sheetQuestNo,
            sheetExpectedTeamSize = sheetExpectedTeamSize,
            sheetFailThreshold = sheetFailThreshold,
            commitResult = commitResult,
            editTargetInvalidated = editTargetInvalidated,
            commitInFlight = commitInFlight,
            editingQuest = editingQuest,
            impactWarning = impactWarning,
            errorMessage = null
        )
    }

    companion object {
        /** 草稿防抖间隔（T49①：300ms）。 */
        const val DRAFT_DEBOUNCE_MS: Long = 300L

        /** `match_drafts.screenKey`：提案录入。 */
        const val DRAFT_KEY: String = "RECORD_PROPOSAL"

        /**
         * 编辑草稿的 screenKey。
         *
         * **必须与新增草稿分开**：否则「编辑第一轮时的输入」会被当成「当前轮的草稿」恢复出来，
         * 用户一进页面就看到一份属于别的轮次的队伍。
         */
        const val DRAFT_KEY_EDIT: String = "RECORD_EDIT"
    }
}

/**
 * 草稿的 JSON 编解码（只服务 `match_drafts`，不参与导出格式）。
 *
 * 手写而不是加 `@Serializable` 注解：草稿是**易变的 UI 状态**，
 * 不应该让序列化框架的注解渗透到 feature 层的数据结构上。
 */
internal object ProposalDraftCodec {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true }

    fun encode(draft: ProposalDraft): String {
        val obj = buildJsonObject {
            val leader = draft.leaderSeat
            if (leader == null) put("leaderSeat", JsonNull) else put("leaderSeat", JsonPrimitive(leader))
            put(
                "teamSeats",
                JsonArray(draft.teamSeats.sorted().map { JsonPrimitive(it) })
            )
            put(
                "votesBySeat",
                buildJsonObject {
                    draft.votesBySeat.entries.sortedBy { it.key }.forEach { (seat, choice) ->
                        put(seat.toString(), JsonPrimitive(choice.name))
                    }
                }
            )
            val approve = draft.approveCount
            if (approve == null) put("approveCount", JsonNull) else put("approveCount", JsonPrimitive(approve))
            val reject = draft.rejectCount
            if (reject == null) put("rejectCount", JsonNull) else put("rejectCount", JsonPrimitive(reject))
            val fail = draft.failCount
            if (fail == null) put("failCount", JsonNull) else put("failCount", JsonPrimitive(fail))
            val observed = draft.observedOutcome
            if (observed == null) {
                put("observedOutcome", JsonNull)
            } else {
                put("observedOutcome", JsonPrimitive(observed.name))
            }
            // C-2：编辑草稿需要记住「改的是哪条事件」
            val editTarget = draft.editTargetEventId
            if (editTarget == null) {
                put("editTargetEventId", JsonNull)
            } else {
                put("editTargetEventId", JsonPrimitive(editTarget.raw))
            }
        }
        return obj.toString()
    }

    fun decode(raw: String?): ProposalDraft {
        if (raw.isNullOrBlank()) return ProposalDraft()
        val obj: JsonObject = runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { return ProposalDraft() }

        val teamSeats = (obj["teamSeats"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            ?.toSet()
            ?: emptySet()

        val votes = (obj["votesBySeat"] as? JsonObject)
            ?.entries
            ?.mapNotNull { (seatKey, choiceElement) ->
                val seat = seatKey.toIntOrNull() ?: return@mapNotNull null
                val choiceRaw = (choiceElement as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null
                val choice = TeamVoteChoice.entries.firstOrNull { it.name == choiceRaw }
                    ?: return@mapNotNull null
                seat to choice
            }
            ?.toMap()
            ?: emptyMap()

        return ProposalDraft(
            leaderSeat = obj.intOrNull("leaderSeat"),
            teamSeats = teamSeats,
            votesBySeat = votes,
            approveCount = obj.intOrNull("approveCount"),
            rejectCount = obj.intOrNull("rejectCount"),
            failCount = obj.intOrNull("failCount"),
            observedOutcome = (obj["observedOutcome"] as? JsonPrimitive)
                ?.contentOrNull
                ?.let { rawOutcome -> MissionOutcome.entries.firstOrNull { it.name == rawOutcome } },
            // 缺失或 null → 新增草稿；未知键仍被忽略（A-2 的兼容性由 ignoreUnknownKeys 保证）
            editTargetEventId = (obj["editTargetEventId"] as? JsonPrimitive)
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { com.roundtrace.app.core.model.EventId(it) }
        )
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.intOrNull
    }
}
