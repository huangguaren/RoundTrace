package com.roundtrace.app.feature

import com.roundtrace.app.core.model.EventFactory
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventType
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.QuestStarted
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteRecorded
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.rules.avalon.AvalonReducer
import com.roundtrace.app.core.rules.avalon.AvalonRules
import com.roundtrace.app.core.ui.component.SaveStatus
import com.roundtrace.app.core.util.SequentialIdGenerator
import com.roundtrace.app.core.util.fixedClock
import com.roundtrace.app.feature.record.ProposalDraftCodec
import com.roundtrace.app.feature.record.RecordAction
import com.roundtrace.app.feature.record.RecordCommitResult
import com.roundtrace.app.feature.record.RecordViewModel
import com.roundtrace.app.util.FakeMatchRepository
import com.roundtrace.app.util.MainDispatcherRule
import com.roundtrace.app.util.MatchBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * C-2：编辑历史事件（3 类可编辑：组队提案 / 组队投票 / 任务结果）。
 *
 * 最核心的一条是 `editingFirstQuestDoesNotWriteToCurrentQuest`：
 * 编辑必须写到**被编辑事件所在的那一轮**，而不是「当前轮」——
 * 后者是「按事件类型 + 当前轮」定位的必然结果，也是这一轮改造的原始动机。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordEditSessionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val rules = AvalonRules()

    private fun eightPlayerConfig() = MatchCreationDraft(
        title = "编辑会话测试",
        playerCount = 8,
        roleSlots = AvalonPreset.standardPreset(8),
        mySeat = 1,
        initialLeaderSeat = 1,
        presetId = AvalonPreset.presetId(8),
        rulesetId = AvalonPreset.RULESET_ID,
        rulesetVersion = AvalonPreset.RULESET_VERSION
    ).toGameConfig(createdAtEpochMs = 0L)

    private fun newViewModel(repository: FakeMatchRepository) = RecordViewModel(
        matchId = MatchId("fake-match"),
        repository = repository,
        gameRulesRegistry = DefaultGameRulesRegistry(),
        reducer = AvalonReducer(rules),
        rules = rules,
        eventFactory = EventFactory(SequentialIdGenerator("evt"), fixedClock(1_700_000_000_000L))
    )

    /** 两轮已结算 + 第三轮待录：`currentQuest` 会是 3。 */
    private fun twoQuestsSettled(): MatchBuilder = MatchBuilder.eightPlayerMerlinMordred()
        .questCycle(1, 1, listOf(1, 2, 3), 0, MissionOutcome.SUCCESS)
        .questCycle(2, 2, listOf(1, 2, 3, 4), 0, MissionOutcome.SUCCESS)

    private fun repositoryWithTwoQuests(): FakeMatchRepository = FakeMatchRepository(
        config = eightPlayerConfig(),
        initialEvents = twoQuestsSettled().events()
    )

    private fun firstQuestProposalId(repository: FakeMatchRepository): EventId =
        repository.bundle()!!.events
            .filterIsInstance<TeamProposed>()
            .first { it.questNo == 1 }
            .eventId

    // ————————————————————————————————————————————————————————
    // 核心：编辑第一轮不会写到当前轮
    // ————————————————————————————————————————————————————————

    @Test
    fun editingFirstQuestDoesNotWriteToCurrentQuest() = runTest {
        val repository = repositoryWithTwoQuests()
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        // 前提：当前轮是第 3 轮（前两轮已结算）
        assertEquals("前提：当前轮应为 3", 3, viewModel.uiState.value.quest?.questNo)

        val targetId = firstQuestProposalId(repository)
        viewModel.onAction(RecordAction.OpenEdit(targetId))
        advanceUntilIdle()

        val editing = viewModel.uiState.value
        assertNotNull("应进入编辑态", editing.editSession)
        assertEquals("编辑的必须是第 1 轮", 1, editing.editSession?.questNo)
        assertEquals(
            "预填：队伍应等于原提案的 3 人",
            setOf(1, 2, 3),
            editing.draft.teamSeats
        )

        // 改一个人：把 3 号换成 5 号
        val eventsBeforeSubmit = repository.bundle()!!.events
        viewModel.onAction(RecordAction.ToggleTeamSeat(3))
        viewModel.onAction(RecordAction.ToggleTeamSeat(5))
        viewModel.onAction(RecordAction.ConfirmProposal)
        advanceUntilIdle()

        // NX-06：编辑历史事件先出影响预览，**确认前零历史写入**
        assertNotNull("应先产出待确认预览", viewModel.uiState.value.pendingEdit)
        assertEquals("确认前不得写库", eventsBeforeSubmit, repository.bundle()!!.events)
        viewModel.onAction(RecordAction.ConfirmPendingEdit)
        advanceUntilIdle()

        val events = repository.bundle()!!.events
        val questOneProposals = events.filterIsInstance<TeamProposed>().filter { it.questNo == 1 }
        val questThreeProposals = events.filterIsInstance<TeamProposed>().filter { it.questNo == 3 }

        assertTrue(
            "第 1 轮必须有一条新版本，且队伍为 1/2/5，实际=${questOneProposals.map { it.teamSeats }}",
            questOneProposals.any { it.teamSeats == listOf(1, 2, 5) }
        )
        assertTrue(
            "**绝不能写到当前轮（第 3 轮）**，实际=${questThreeProposals.map { it.teamSeats }}",
            questThreeProposals.isEmpty()
        )
        assertEquals(
            "第 1 轮的位置上最终只应有一条 active 提案",
            1,
            questOneProposals.size
        )
    }

    @Test
    fun editingPrefillsVoteValues() = runTest {
        val repository = repositoryWithTwoQuests()
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        val voteEvent = repository.bundle()!!.events
            .filterIsInstance<TeamVoteRecorded>()
            .first { it.questNo == 1 }

        viewModel.onAction(RecordAction.OpenEdit(voteEvent.eventId))
        advanceUntilIdle()

        val draft = viewModel.uiState.value.draft
        assertEquals("赞成票应预填", voteEvent.approveCount, draft.approveCount)
        assertEquals("反对票应预填", voteEvent.rejectCount, draft.rejectCount)
        assertEquals(
            "编辑目标的 id 必须写进草稿（用于编辑草稿恢复时校验）",
            voteEvent.eventId,
            draft.editTargetEventId
        )
    }

    // ————————————————————————————————————————————————————————
    // 不可编辑类型 / 目标失效
    // ————————————————————————————————————————————————————————

    @Test
    fun openingNonEditableTypeDoesNotEnterEditMode() = runTest {
        val repository = repositoryWithTwoQuests()
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        // QuestStarted 不在白名单里
        val questStarted = repository.bundle()!!.events
            .filterIsInstance<QuestStarted>()
            .first()

        viewModel.onAction(RecordAction.OpenEdit(questStarted.eventId))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull("不可编辑类型不得进入编辑态", state.editSession)
        assertTrue("应提示「原记录已变更，请重新加载」", state.editTargetInvalidated)
    }

    @Test
    fun openingMissingEventDoesNotEnterEditMode() = runTest {
        val repository = repositoryWithTwoQuests()
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        viewModel.onAction(RecordAction.OpenEdit(EventId("no-such-event")))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.editSession)
        assertTrue(viewModel.uiState.value.editTargetInvalidated)
    }

    // ————————————————————————————————————————————————————————
    // 失败保留输入 / 取消不写库
    // ————————————————————————————————————————————————————————

    @Test
    fun commitFailureKeepsInputAndEditSession() = runTest {
        val repository = repositoryWithTwoQuests()
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        val targetId = firstQuestProposalId(repository)
        viewModel.onAction(RecordAction.OpenEdit(targetId))
        advanceUntilIdle()

        viewModel.onAction(RecordAction.ToggleTeamSeat(5))
        advanceUntilIdle()
        val teamBeforeSave = viewModel.uiState.value.draft.teamSeats

        // 先拿到预览（预览只读，不会消耗 failNextWrite），再让**确认写库**失败
        viewModel.onAction(RecordAction.ConfirmProposal)
        advanceUntilIdle()
        assertNotNull("前提：预览已就绪", viewModel.uiState.value.pendingEdit)

        repository.failNextWrite = true
        viewModel.onAction(RecordAction.ConfirmPendingEdit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("失败必须如实标 FAILED", SaveStatus.FAILED, state.saveStatus)
        assertTrue(
            "必须给出失败结果（UI 据此保留弹窗 + 重试）",
            state.commitResult is RecordCommitResult.Failed
        )
        assertEquals("**用户输入必须保留**", teamBeforeSave, state.draft.teamSeats)
        assertNotNull("编辑态必须保留，否则用户得重新点一遍", state.editSession)
        assertEquals(
            "旧的有效事件不得被动",
            targetId,
            state.editSession?.targetEventId
        )
    }

    @Test
    fun cancelEditDiscardsDraftWithoutWriting() = runTest {
        val repository = repositoryWithTwoQuests()
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        val targetId = firstQuestProposalId(repository)
        viewModel.onAction(RecordAction.OpenEdit(targetId))
        advanceUntilIdle()
        val eventsBefore = repository.bundle()!!.events.size

        viewModel.onAction(RecordAction.CancelEdit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull("取消后应退出编辑态", state.editSession)
        assertTrue("取消后草稿应清空", state.draft.teamSeats.isEmpty())
        assertEquals(
            "**取消不得写库**",
            eventsBefore,
            repository.bundle()!!.events.size
        )
    }

    // ————————————————————————————————————————————————————————
    // 编辑草稿恢复：目标失效时保留输入 + 提示
    // ————————————————————————————————————————————————————————

    @Test
    fun restoringEditDraftWithInvalidTargetKeepsInputAndFlags() = runTest {
        val repository = repositoryWithTwoQuests()
        // 预置一份「目标已不存在」的编辑草稿
        repository.seedDraft(
            RecordViewModel.DRAFT_KEY_EDIT,
            ProposalDraftCodec.encode(
                com.roundtrace.app.feature.record.ProposalDraft(
                    leaderSeat = 4,
                    teamSeats = setOf(4, 6, 8),
                    editTargetEventId = EventId("vanished-event")
                )
            )
        )

        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull("目标失效时不得进入编辑态", state.editSession)
        assertTrue("必须提示原记录已变更", state.editTargetInvalidated)
        assertEquals(
            "**用户输入不得被静默丢弃**",
            setOf(4, 6, 8),
            state.draft.teamSeats
        )
    }

    @Test
    fun restoringEditDraftWithValidTargetEntersEditMode() = runTest {
        val repository = repositoryWithTwoQuests()
        val targetId = firstQuestProposalId(repository)
        repository.seedDraft(
            RecordViewModel.DRAFT_KEY_EDIT,
            ProposalDraftCodec.encode(
                com.roundtrace.app.feature.record.ProposalDraft(
                    leaderSeat = 6,
                    teamSeats = setOf(1, 5, 6),
                    editTargetEventId = targetId
                )
            )
        )

        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull("目标仍有效时应恢复编辑态", state.editSession)
        assertEquals("编辑轮次取自目标事件", 1, state.editSession?.questNo)
        assertEquals("草稿内容应恢复", setOf(1, 5, 6), state.draft.teamSeats)
        assertFalse("不应报目标失效", state.editTargetInvalidated)
    }

    // ————————————————————————————————————————————————————————
    // 弹层的规则口径跟着「被编辑的轮次」走
    // ————————————————————————————————————————————————————————

    @Test
    fun editingHistoricalQuestUsesThatQuestTeamSize() = runTest {
        val repository = repositoryWithTwoQuests()
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        // 8 人局：第 1 轮 3 人队，第 3 轮 4 人队 —— 两者不同，能区分口径
        assertEquals("前提：当前轮为第 3 轮", 3, viewModel.uiState.value.sheetQuestNo)
        assertEquals(
            "前提：第 3 轮（8 人局）应为 4 人队",
            4,
            viewModel.uiState.value.sheetExpectedTeamSize
        )

        viewModel.onAction(RecordAction.OpenEdit(firstQuestProposalId(repository)))
        advanceUntilIdle()

        val editing = viewModel.uiState.value
        assertEquals("弹层轮次 = 被编辑轮次", 1, editing.sheetQuestNo)
        assertEquals(
            "弹层期望人数必须按第 1 轮算（3 人），否则提交按钮永远点不亮",
            3,
            editing.sheetExpectedTeamSize
        )

        viewModel.onAction(RecordAction.CancelEdit)
        advanceUntilIdle()

        val restored = viewModel.uiState.value
        assertEquals("退出编辑后回到当前轮", 3, restored.sheetQuestNo)
        assertEquals("退出编辑后回到当前轮人数", 4, restored.sheetExpectedTeamSize)
    }

    // ————————————————————————————————————————————————————————
    // 改票不碰决议
    // ————————————————————————————————————————————————————————
    @Test
    fun editingVoteDoesNotTouchProposalDecision() = runTest {
        val repository = repositoryWithTwoQuests()
        val viewModel = newViewModel(repository)
        advanceUntilIdle()

        val voteEvent = repository.bundle()!!.events
            .filterIsInstance<TeamVoteRecorded>()
            .first { it.questNo == 1 }
        val decisionsBefore = repository.bundle()!!.events
            .filter { it.type == EventType.TEAM_PROPOSAL_RESOLVED && it.questNo == 1 }

        viewModel.onAction(RecordAction.OpenEdit(voteEvent.eventId))
        advanceUntilIdle()
        viewModel.onAction(RecordAction.SetVoteSummary(approveCount = 3, rejectCount = 5))
        viewModel.onAction(RecordAction.SaveVotes)
        advanceUntilIdle()
        // NX-06：改票同样先预览、再确认（B35：只修订投票）
        viewModel.onAction(RecordAction.ConfirmPendingEdit)
        advanceUntilIdle()

        val decisionsAfter = repository.bundle()!!.events
            .filter { it.type == EventType.TEAM_PROPOSAL_RESOLVED && it.questNo == 1 }

        assertEquals(
            "改票**不得**顺手改同轮决议（现场既成事实），数量不变",
            decisionsBefore.size,
            decisionsAfter.size
        )
        assertEquals(
            "决议内容也不得被改写",
            decisionsBefore.map { it.eventId },
            decisionsAfter.map { it.eventId }
        )
    }
}
