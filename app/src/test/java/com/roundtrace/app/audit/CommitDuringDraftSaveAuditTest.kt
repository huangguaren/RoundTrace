package com.roundtrace.app.audit

import com.roundtrace.app.core.data.AppendResult
import com.roundtrace.app.core.data.MatchRepository
import com.roundtrace.app.core.model.EventFactory
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteChoice
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.rules.avalon.AvalonReducer
import com.roundtrace.app.core.rules.avalon.AvalonRules
import com.roundtrace.app.core.ui.component.SaveStatus
import com.roundtrace.app.core.util.SequentialIdGenerator
import com.roundtrace.app.core.util.fixedClock
import com.roundtrace.app.feature.record.RecordAction
import com.roundtrace.app.feature.record.RecordCommitResult
import com.roundtrace.app.feature.record.RecordViewModel
import com.roundtrace.app.util.FakeMatchRepository
import com.roundtrace.app.util.MainDispatcherRule
import com.roundtrace.app.util.MatchBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * QA 阶段 D · V06（UI 侧对抗）：**「正在保存」会不会吞掉用户的真实提交**。
 *
 * ## 为什么要这个用例
 *
 * 原来的并发闸门是 `RecordViewModel.commit()` 开头的
 * `if (saveStatusFlow.value == SaveStatus.SAVING) return`。它对「连点两次提交」有效，
 * 但 `SaveStatus.SAVING` **同时被草稿自动保存复用**（`persistDraftNow()`），于是有一个窄窗口：
 *
 *   「选完队员 → 防抖草稿保存还在飞 → 点确认提案」
 *   → commit 直接 return，**没有事件写入，也没有任何提示**，随后状态还变成 SAVED。
 *
 * 这个窗口只有几毫秒，普通测试永远撞不到；本类用一个**装饰仓储**把
 * `saveDraft` / `appendEvent` 卡在 `CompletableDeferred` 上，把窗口撑开来断言。
 *
 * ## 【历史说明】这条用例为什么曾经断言「缺陷行为」
 *
 * 阶段 D 验收时（HEAD=`30e328a`，`856cd38..ec83007` 期间的代码）本用例是**特征化用例**：
 * 它断言的正是上面那个**缺陷本身**（`commitResult == null` 且事件数不变）。
 * 那是当时唯一能把这个静默失败钉住的方式——如果不这么写，"提交被吞"没有任何自动化证据。
 *
 * F1 修复后，**这里已翻转为正向断言**（提交必须落库）。这与「为了让测试变绿而改期望」的区别是：
 * ① 期望的方向与产品需求一致（点了提交就必须写），不是迁就实现；
 * ② 修复前后的行为都留下了记录（上面的段落 + `docs/audit/qa-phase-d.md` §3.4 / §7）；
 * ③ 同时新增 `v06b` 守住「防重复」——防止把闸门拆掉之后连点两次写两条。
 *
 * 看 git 历史时请按这个脉络理解，不要以为是测试写错了。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommitDuringDraftSaveAuditTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val rules = AvalonRules()

    /**
     * 只拦截「草稿保存」与「事件提交」两条路，其余全部委托给 fake。
     *
     * 两个 gate 分开，才能分别构造「草稿在飞」与「提交在飞」两种窗口。
     */
    private class BlockingRepository(
        private val delegate: FakeMatchRepository
    ) : MatchRepository by delegate {

        val draftGate = CompletableDeferred<Unit>()
        val commitGate = CompletableDeferred<Unit>()

        @Volatile
        var blockDraft: Boolean = false

        @Volatile
        var blockCommit: Boolean = false

        override suspend fun saveDraft(matchId: MatchId, screenKey: String, json: String) {
            if (blockDraft) draftGate.await()
            delegate.saveDraft(matchId, screenKey, json)
        }

        override suspend fun appendEvent(matchId: MatchId, event: MatchEvent): AppendResult {
            if (blockCommit) commitGate.await()
            return delegate.appendEvent(matchId, event)
        }

        override suspend fun reviseEvent(
            matchId: MatchId,
            targetEventId: EventId,
            replacement: MatchEvent
        ): AppendResult {
            if (blockCommit) commitGate.await()
            return delegate.reviseEvent(matchId, targetEventId, replacement)
        }
    }

    private fun twoQuestsSettled(): MatchBuilder = MatchBuilder.eightPlayerMerlinMordred()
        .questCycle(1, 1, listOf(1, 2, 3), 0, MissionOutcome.SUCCESS)
        .questCycle(2, 2, listOf(1, 2, 3, 4), 0, MissionOutcome.SUCCESS)

    private fun fakeRepository(): FakeMatchRepository {
        val builder = twoQuestsSettled()
        val config = MatchCreationDraft(
            title = "审计对局（保存窗口）",
            playerCount = 8,
            roleSlots = AvalonPreset.standardPreset(8),
            mySeat = 1,
            initialLeaderSeat = 1,
            presetId = AvalonPreset.presetId(8),
            rulesetId = AvalonPreset.RULESET_ID,
            rulesetVersion = AvalonPreset.RULESET_VERSION
        ).toGameConfig(createdAtEpochMs = 0L)
        return FakeMatchRepository(
            config = config,
            initialEvents = builder.events(),
            revealedAt = null,
            initialRoles = emptyMap()
        )
    }

    private fun newViewModel(repository: MatchRepository) = RecordViewModel(
        matchId = MatchId("fake-match"),
        repository = repository,
        gameRulesRegistry = DefaultGameRulesRegistry(),
        reducer = AvalonReducer(rules),
        rules = rules,
        eventFactory = EventFactory(SequentialIdGenerator("evt"), fixedClock(1_700_000_000_000L))
    )

    /**
     * 只数「提案」事件。
     *
     * 不能数 `events.size`：`FakeMatchRepository.appendEvent` 与真实仓储一致，
     * 首次提交某任务的提案时会**同事务补建 `QuestStarted`**（ARCHITECTURE §9.3-18），
     * 所以一次提交 = 2 行事件。数全部行会把「补建的 QS」误当成「写了两次」。
     */
    private fun proposalCount(fake: FakeMatchRepository, questNo: Int = 3): Int =
        fake.bundle()!!.events
            .filterIsInstance<TeamProposed>()
            .count { it.questNo == questNo }

    /** 8 人局当前是第 3 任务（需 4 人），先把草稿填成合法。 */
    private fun seedLegalDraft(viewModel: RecordViewModel) {
        viewModel.onAction(RecordAction.SelectLeader(1))
        (1..4).forEach { viewModel.onAction(RecordAction.ToggleTeamSeat(it)) }
    }

    // ————————————————————————————————————————————————————————
    // V06（翻转后）：草稿保存窗口内的提交**必须**落库
    // ————————————————————————————————————————————————————————

    @Test
    fun v06_commit_during_draft_save_window_still_persists() = runTest {
        val fake = fakeRepository()
        val blocking = BlockingRepository(fake)
        val viewModel = newViewModel(blocking)
        advanceUntilIdle()

        seedLegalDraft(viewModel)
        advanceUntilIdle()
        assertEquals(
            "前置条件：草稿保存完成后应为 SAVED",
            SaveStatus.SAVED,
            viewModel.uiState.value.saveStatus
        )

        val proposalsBefore = proposalCount(fake)
        assertEquals("前置条件：第 3 任务还没有提案", 0, proposalsBefore)

        // ① 让下一次草稿保存卡住，并用一个「不改变合法性」的动作触发它
        blocking.blockDraft = true
        viewModel.onAction(RecordAction.SetVote(1, TeamVoteChoice.APPROVE))
        advanceUntilIdle()
        assertEquals(
            "前置条件：此时 SaveStatus 必须是 SAVING（草稿保存正在飞）",
            SaveStatus.SAVING,
            viewModel.uiState.value.saveStatus
        )

        // ② 只放行「后续」草稿保存，已经在飞的这一次继续保持挂起 —— 窗口因此保持打开
        blocking.blockDraft = false

        // ③ 用户点「确认提案」：必须真的写进去
        viewModel.onAction(RecordAction.ConfirmProposal)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        println(
            "[AUDIT-D] 草稿保存中提交（F1 后）：saveStatus=${state.saveStatus}，" +
                "commitResult=${state.commitResult}，第3任务提案数=$proposalsBefore→${proposalCount(fake, questNo = 3)}"
        )

        assertTrue(
            "F1：草稿保存窗口内的提交必须成功，实际=${state.commitResult}",
            state.commitResult is RecordCommitResult.Succeeded
        )
        assertEquals(
            "F1：第 3 任务的提案必须真的落库",
            1,
            proposalCount(fake, questNo = 3)
        )

        // ④ 放行挂起的草稿保存：不得把提交结果冲掉，也不得回退事件
        blocking.draftGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(
            "草稿保存收尾后状态应为 SAVED",
            SaveStatus.SAVED,
            viewModel.uiState.value.saveStatus
        )
        assertEquals(
            "草稿保存收尾不得回退已落库的提案",
            1,
            proposalCount(fake, questNo = 3)
        )
    }

    // ————————————————————————————————————————————————————————
    // V06b：防重复仍然有效（防止 F1 把闸门一起拆掉）
    // ————————————————————————————————————————————————————————

    @Test
    fun v06b_double_submit_writes_exactly_one_event() = runTest {
        val fake = fakeRepository()
        val blocking = BlockingRepository(fake)
        val viewModel = newViewModel(blocking)
        advanceUntilIdle()

        seedLegalDraft(viewModel)
        advanceUntilIdle()
        assertEquals("前置条件：第 3 任务还没有提案", 0, proposalCount(fake, questNo = 3))

        // 让第一次提交停在写库中间，模拟「真实设备上写库有延迟」
        blocking.blockCommit = true
        viewModel.onAction(RecordAction.ConfirmProposal)
        advanceUntilIdle()

        // 用户在等待期间又点了一次
        viewModel.onAction(RecordAction.ConfirmProposal)
        advanceUntilIdle()

        blocking.commitGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            "连点两次只能写一条提案（第 3 任务）",
            1,
            proposalCount(fake, questNo = 3)
        )
        assertTrue(
            "第一次提交必须成功",
            viewModel.uiState.value.commitResult is RecordCommitResult.Succeeded
        )
    }

    // ————————————————————————————————————————————————————————
    // 对照组：空闲时提交必须成功（证明上面的用例不是数据本身有问题）
    // ————————————————————————————————————————————————————————

    @Test
    fun v06_control_commit_works_when_idle() = runTest {
        val fake = fakeRepository()
        val viewModel = newViewModel(fake)
        advanceUntilIdle()

        val eventsBefore = fake.bundle()!!.events.size
        seedLegalDraft(viewModel)
        advanceUntilIdle() // 草稿保存完成，回到 SAVED

        viewModel.onAction(RecordAction.ConfirmProposal)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        println("[AUDIT-D] 对照组提交结果：${state.commitResult}，saveStatus=${state.saveStatus}")
        assertTrue(
            "对照组：空闲时提交必须成功",
            state.commitResult is RecordCommitResult.Succeeded
        )
        assertTrue(
            "对照组：事件必须真的写进去了",
            fake.bundle()!!.events.size > eventsBefore
        )
        assertNotNull("对照组：必须有提交结果", state.commitResult)
    }
}
