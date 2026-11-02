package com.roundtrace.app.feature.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundtrace.app.core.data.MatchBundle
import com.roundtrace.app.core.data.MatchRepository
import com.roundtrace.app.core.model.EventFactory
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
            undoFeedbackFlow
        ) { bundle, draft, saveStatus, undoFeedback ->
            buildUiState(
                bundle = bundle,
                draft = draft,
                saveStatus = saveStatus,
                undoFeedback = undoFeedback
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

            RecordAction.ClearUndoFeedback -> undoFeedbackFlow.value = null
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
        val json = ProposalDraftCodec.encode(draftFlow.value)
        viewModelScope.launch {
            saveStatusFlow.value = SaveStatus.SAVING
            val result = runCatching { repository.saveDraft(matchId, DRAFT_KEY, json) }
            saveStatusFlow.value = if (result.isSuccess) SaveStatus.SAVED else SaveStatus.FAILED
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
        append(eventFactory.questStarted(questNo = quest.questNo, leaderSeat = leader))
    }

    private fun confirmProposal() {
        val state = uiState.value
        val quest = state.quest ?: return
        val leader = state.draft.leaderSeat ?: return
        val team = state.draft.teamSeats.sorted()
        if (team.isEmpty()) return
        val proposalNo = quest.nextProposalNo
        viewModelScope.launch {
            // 编辑模式：撤回该提案的旧 TeamProposed
            val bundle = repository.getBundle(matchId)
            if (bundle != null) {
                bundle.events.filter {
                    it is com.roundtrace.app.core.model.TeamProposed &&
                        it.questNo == quest.questNo &&
                        it.proposalNo == proposalNo &&
                        !it.retracted
                }.forEach { old -> runCatching { repository.retractEvent(matchId, old.eventId) } }
            }
            val event = eventFactory.teamProposed(
                questNo = quest.questNo,
                proposalNo = proposalNo,
                leaderSeat = leader,
                teamSeats = team
            )
            append(event)
        }
    }

    private fun saveVotes() {
        val state = uiState.value
        val quest = state.quest ?: return
        val draft = state.draft
        if (!draft.hasVoteData) return
        val proposalNo = quest.nextProposalNo - 1
        viewModelScope.launch {
            // 编辑模式：撤回该提案的旧投票记录
            val bundle = repository.getBundle(matchId)
            if (bundle != null) {
                bundle.events.filter {
                    it is com.roundtrace.app.core.model.TeamVoteRecorded &&
                        it.questNo == quest.questNo &&
                        it.proposalNo == proposalNo &&
                        !it.retracted
                }.forEach { old -> runCatching { repository.retractEvent(matchId, old.eventId) } }
            }
            val event = eventFactory.teamVoteRecorded(
                questNo = quest.questNo,
                proposalNo = proposalNo,
                playerCountAtVote = state.playerCount,
                votesBySeat = draft.votesBySeat.ifEmpty { null },
                approveCount = draft.approveCount,
                rejectCount = draft.rejectCount,
                unknownVoteCount = draft.unknownVoteCount(state.playerCount)
            )
            append(event)
        }
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
        append(event)
    }

    private fun saveMission() {
        val state = uiState.value
        val quest = state.quest ?: return
        val draft = state.draft
        val team = draft.teamSeats.takeIf { it.isNotEmpty() }?.sorted()
        viewModelScope.launch {
            // 编辑模式：如果当前任务已有 MISSION_RESOLVED 事件，先撤回
            val bundle = repository.getBundle(matchId)
            if (bundle != null) {
                val existing = bundle.events.filter {
                    it is com.roundtrace.app.core.model.MissionResolved && it.questNo == quest.questNo && !it.retracted
                }
                existing.forEach { old ->
                    runCatching { repository.retractEvent(matchId, old.eventId) }
                }
            }
            val event = eventFactory.missionResolved(
                questNo = quest.questNo,
                teamSizeExpected = quest.expectedTeamSize,
                teamSeats = team,
                failCount = draft.failCount,
                observedOutcome = draft.observedOutcome
            )
            append(event)
        }
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

    /** 回滚到指定任务：删除该任务结算之后的所有事件。 */
    private fun rollbackToQuest(questNo: Int) {
        viewModelScope.launch {
            val bundle = repository.getBundle(matchId) ?: return@launch
            // 找任务N的最后一个事件 order（该任务的 MISSION_RESOLVED 或最后一个相关事件）
            val questEvents = bundle.events.filter { event ->
                when (event) {
                    is com.roundtrace.app.core.model.QuestStarted -> event.questNo == questNo
                    is com.roundtrace.app.core.model.TeamProposed -> event.questNo == questNo
                    is com.roundtrace.app.core.model.TeamVoteRecorded -> event.questNo == questNo
                    is com.roundtrace.app.core.model.TeamProposalResolved -> event.questNo == questNo
                    is com.roundtrace.app.core.model.MissionResolved -> event.questNo == questNo
                    else -> false
                }
            }
            val cutoffOrder = questEvents.maxOfOrNull { it.occurredOrder } ?: 0
            // 删除所有 order > cutoffOrder 的事件
            bundle.events.filter { it.occurredOrder > cutoffOrder }.forEach { e ->
                runCatching { repository.retractEvent(matchId, e.eventId) }
            }
        }
    }

    private fun append(event: MatchEvent) {
        viewModelScope.launch {
            saveStatusFlow.value = SaveStatus.SAVING
            val result = runCatching { repository.appendEvent(matchId, event) }
            saveStatusFlow.value = when {
                result.isFailure -> SaveStatus.FAILED
                result.getOrNull()?.isSuccess != true -> SaveStatus.FAILED
                else -> SaveStatus.SAVED
            }
            if (result.isSuccess && result.getOrNull()?.isSuccess == true) {
                // 事件已落库 → 清空本轮表单内容
                // 如果是任务开始事件，不保留队长，新任务重新选队长
                val keepLeader = event !is com.roundtrace.app.core.model.QuestStarted
                draftFlow.value = ProposalDraft(leaderSeat = if (keepLeader) draftFlow.value.leaderSeat else null)
                runCatching { repository.clearDraft(matchId, DRAFT_KEY) }
            }
        }
    }

    // ————————————————————————————————————————————————————————
    // 状态派生
    // ————————————————————————————————————————————————————————

    private fun buildUiState(
        bundle: MatchBundle?,
        draft: ProposalDraft,
        saveStatus: SaveStatus,
        undoFeedback: UndoFeedback?
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

        val expectedTeamSize = rules.expectedTeamSizeFor(playerCount, activeQuestNo)
        val failThreshold = if (playerCount in AvalonPreset.MIN_PLAYERS..AvalonPreset.MAX_PLAYERS) {
            rules.failThreshold(playerCount, activeQuestNo)
        } else {
            1
        }

        val currentQuest = gameState.quests.firstOrNull { it.questNo == activeQuestNo }
        val nextProposalNo = if (currentQuest == null) {
            1
        } else {
            currentQuest.latestProposalNo + 1
        }

        val quest = QuestUiModel(
            questNo = activeQuestNo,
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
        val draftIssues: List<RuleIssue> = rules.validateTeamDraft(
            teamSeats = draft.teamSeats.sorted(),
            playerCount = playerCount,
            questNo = activeQuestNo
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
            if (rules.isMissionFailed(failCount, playerCount, activeQuestNo)) {
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
            errorMessage = null
        )
    }

    companion object {
        /** 草稿防抖间隔（T49①：300ms）。 */
        const val DRAFT_DEBOUNCE_MS: Long = 300L

        /** `match_drafts.screenKey`：提案录入。 */
        const val DRAFT_KEY: String = "RECORD_PROPOSAL"
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
                ?.let { rawOutcome -> MissionOutcome.entries.firstOrNull { it.name == rawOutcome } }
        )
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.intOrNull
    }
}
