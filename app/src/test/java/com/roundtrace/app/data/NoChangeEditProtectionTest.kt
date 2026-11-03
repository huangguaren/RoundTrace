package com.roundtrace.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.audit.MutableClock
import com.roundtrace.app.core.data.EditApplyResult
import com.roundtrace.app.core.data.RoomMatchRepository
import com.roundtrace.app.core.data.edit.EditPlanRejection
import com.roundtrace.app.core.data.edit.EditPlanResult
import com.roundtrace.app.core.database.EventCodec
import com.roundtrace.app.core.database.RoundTraceDatabase
import com.roundtrace.app.core.database.entity.MatchEventEntity
import com.roundtrace.app.core.model.EventFactory
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.TeamProposalResolved
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteRecorded
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.util.SequentialIdGenerator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P2：**业务字段无变化的历史编辑不得产生新修订**（审计链防空转）。
 *
 * ## 为什么这是缺陷而不是洁癖
 *
 * `openEdit` 用原值预填、`canSubmit` 只校验人数，所以「打开编辑 → 什么都不改 → 保存」
 * 是现场最容易误触的路径。修复前它虽然不会再撤销别人（D2 已修），但仓储仍会走
 * `reviseWithSlot`：旧版本 `retracted = 1`、新版本 `revisionSeq + 1`、`matches.updatedAt` 刷新。
 * 结果是一条内容完全相同的新版本留在审计链里 —— 既污染时间线，也让「这条记录被改过吗」
 * 这个问题永远答错。
 *
 * ## 这些用例为什么必须用真库
 *
 * 「零写入」包含四件事，只有真库能同时证明：
 * 1. `match_events` 行数与 id 集合不变；
 * 2. 没有任何行 `retracted` 翻转；
 * 3. 没有 `revisionSeq + 1`（不产生新修订链节点）；
 * 4. `matches.updatedAt` 不变（投影没被重算）。
 *
 * 另外，这里刻意验证**闸门顺序**：`NO_BUSINESS_CHANGE` 必须发生在令牌校验之前，
 * 这样即使 UI 传来了过期的令牌集合，只要内容本就等价，也不会被误判成 `StalePreview`
 * 更不会走进写入路径（TOCTOU 防护）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoChangeEditProtectionTest {

    private lateinit var database: RoundTraceDatabase
    private lateinit var repository: RoomMatchRepository
    private val registry = DefaultGameRulesRegistry()
    private val clock = MutableClock(1_700_000_000_000L)
    private val ids = SequentialIdGenerator("p2")
    private val events = EventFactory(ids, clock)

    private val playerCount = 8

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RoundTraceDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = RoomMatchRepository(
            database, registry, registry.avalonReducer, EventCodec(), ids, clock
        )
    }

    @After
    fun tearDown() = database.close()

    // ————————————————————————————————————————————————————————
    // 夹具
    // ————————————————————————————————————————————————————————

    private suspend fun createMatch(): MatchId = repository.createMatch(
        MatchCreationDraft(
            title = "无变化编辑测试",
            playerCount = playerCount,
            mySeat = 1,
            roleSlots = AvalonPreset.standardPreset(playerCount),
            participants = MatchCreationDraft.defaultParticipants(playerCount),
            initialLeaderSeat = 1,
            presetId = AvalonPreset.presetId(playerCount)
        )
    )

    private suspend fun append(match: MatchId, event: MatchEvent) {
        val result = repository.appendEvent(match, event)
        assertTrue("前置写入失败：${result.error}", result.isSuccess)
    }

    private suspend fun appendCycle(
        match: MatchId,
        questNo: Int,
        leaderSeat: Int,
        teamSeats: List<Int>,
        failCount: Int?,
        approve: Int? = playerCount,
        reject: Int? = 0,
        decision: ProposalDecision = ProposalDecision.APPROVED
    ) {
        append(match, events.questStarted(questNo, leaderSeat))
        append(match, events.teamProposed(questNo, 1, leaderSeat, teamSeats))
        append(
            match,
            events.teamVoteRecorded(
                questNo = questNo,
                proposalNo = 1,
                playerCountAtVote = playerCount,
                approveCount = approve,
                rejectCount = reject
            )
        )
        append(
            match,
            events.teamProposalResolved(
                questNo = questNo,
                proposalNo = 1,
                decision = decision,
                playerCountAtVote = playerCount,
                approveCount = approve,
                rejectCount = reject
            )
        )
        append(
            match,
            events.missionResolved(
                questNo = questNo,
                teamSizeExpected = teamSeats.size,
                teamSeats = teamSeats,
                failCount = failCount
            )
        )
    }

    private suspend fun rows(match: MatchId): List<MatchEventEntity> =
        database.matchEventDao().listAll(match.raw)

    private suspend fun bundle(match: MatchId) = repository.getBundle(match)!!

    private suspend fun updatedAt(match: MatchId): Long = bundle(match).summary.updatedAtEpochMs

    /**
     * 「历史表 + 投影」的可比对快照。
     *
     * 用字符串而不是对象比较，是为了让失败信息直接可读：一旦有行被改写或新增，
     * 断言输出就是两份清单的差异，而不是一句 `expected true`。
     */
    private suspend fun historySignature(match: MatchId): List<String> =
        rows(match)
            .map { row ->
                "${row.id}|pos=${row.logicalPosition}|rev=${row.revisionSeq}|retracted=${row.retracted}"
            }
            .sorted()

    private suspend fun planResult(
        match: MatchId,
        targetId: com.roundtrace.app.core.model.EventId,
        replacement: MatchEvent
    ): EditPlanResult = repository.planHistoricalEdit(match, targetId, replacement)

    /** 断言「零写入」的四件事一起成立。 */
    private suspend fun assertNothingWritten(
        match: MatchId,
        signatureBefore: List<String>,
        updatedAtBefore: Long
    ) {
        assertEquals("历史表不得新增/改写任何行", signatureBefore, historySignature(match))
        assertEquals("投影不得被重算（updatedAt 不应变化）", updatedAtBefore, updatedAt(match))
    }

    // ————————————————————————————————————————————————————————
    // 1. 三类可编辑事件：无变化 → 拒绝 + 零写入
    // ————————————————————————————————————————————————————————

    @Test
    fun noChangeProposalIsRejectedAndWritesNothing() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0)
        appendCycle(match, 2, 2, listOf(2, 3, 4, 5), failCount = 0)

        val proposal = bundle(match).events.filterIsInstance<TeamProposed>().first { it.questNo == 1 }
        val before = historySignature(match)
        val updatedBefore = updatedAt(match)

        // 队长、队伍都沿用原值（= 打开编辑后直接保存）
        val identical = events.teamProposed(1, 1, proposal.leaderSeat, proposal.teamSeats)
        val result = planResult(match, proposal.eventId, identical)

        assertTrue("无变化必须被拒绝，实际 $result", result is EditPlanResult.Rejected)
        assertEquals(
            "拒绝原因必须是 NO_BUSINESS_CHANGE",
            EditPlanRejection.NO_BUSINESS_CHANGE,
            (result as EditPlanResult.Rejected).reason
        )
        assertNothingWritten(match, before, updatedBefore)
    }

    @Test
    fun noChangeVoteIsRejectedAndWritesNothing() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0)

        val vote = bundle(match).events.filterIsInstance<TeamVoteRecorded>().first { it.questNo == 1 }
        val before = historySignature(match)
        val updatedBefore = updatedAt(match)

        val identical = events.teamVoteRecorded(
            questNo = vote.questNo,
            proposalNo = vote.proposalNo,
            playerCountAtVote = vote.playerCountAtVote,
            votesBySeat = vote.votesBySeat,
            approveCount = vote.approveCount,
            rejectCount = vote.rejectCount,
            unknownVoteCount = vote.unknownVoteCount
        )
        val result = planResult(match, vote.eventId, identical)

        assertEquals(
            "无变化的投票必须被拒",
            EditPlanRejection.NO_BUSINESS_CHANGE,
            (result as? EditPlanResult.Rejected)?.reason
        )
        assertNothingWritten(match, before, updatedBefore)
    }

    @Test
    fun noChangeMissionIsRejectedAndWritesNothing() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 1)

        val mission = bundle(match).events.filterIsInstance<MissionResolved>()
            .first { it.questNo == 1 }
        val before = historySignature(match)
        val updatedBefore = updatedAt(match)

        val identical = events.missionResolved(
            questNo = mission.questNo,
            teamSizeExpected = mission.teamSizeExpected,
            teamSeats = mission.teamSeats,
            failCount = mission.failCount,
            observedOutcome = mission.observedOutcome
        )
        val result = planResult(match, mission.eventId, identical)

        assertEquals(
            "无变化的任务结果必须被拒",
            EditPlanRejection.NO_BUSINESS_CHANGE,
            (result as? EditPlanResult.Rejected)?.reason
        )
        assertNothingWritten(match, before, updatedBefore)
    }

    // ————————————————————————————————————————————————————————
    // 2. 集合语义：换录入顺序不等于改了内容
    // ————————————————————————————————————————————————————————

    @Test
    fun reorderedTeamIsStillNoChange() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0)

        val proposal = bundle(match).events.filterIsInstance<TeamProposed>().first { it.questNo == 1 }
        val reordered = events.teamProposed(
            1, 1, proposal.leaderSeat, proposal.teamSeats.reversed()
        )
        val result = planResult(match, proposal.eventId, reordered)

        assertEquals(
            "同一批座位换了录入顺序仍是同一支队伍",
            EditPlanRejection.NO_BUSINESS_CHANGE,
            (result as? EditPlanResult.Rejected)?.reason
        )
    }

    @Test
    fun reorderedMissionTeamIsStillNoChange() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3, 4), failCount = 0)

        val mission = bundle(match).events.filterIsInstance<MissionResolved>()
            .first { it.questNo == 1 }
        val reordered = events.missionResolved(
            questNo = mission.questNo,
            teamSizeExpected = mission.teamSizeExpected,
            teamSeats = mission.teamSeats?.reversed(),
            failCount = mission.failCount,
            observedOutcome = mission.observedOutcome
        )
        val result = planResult(match, mission.eventId, reordered)

        assertEquals(
            "任务结果的队员顺序变化不算业务修改",
            EditPlanRejection.NO_BUSINESS_CHANGE,
            (result as? EditPlanResult.Rejected)?.reason
        )
    }

    // ————————————————————————————————————————————————————————
    // 3. null 与 0 不是同一件事（不得被等价掉）
    // ————————————————————————————————————————————————————————

    @Test
    fun missionFailCountUnknownVersusZeroIsARealChange() = runBlocking {
        val match = createMatch()
        // failCount = null → 玩家当时没记失败牌
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = null)

        val mission = bundle(match).events.filterIsInstance<MissionResolved>()
            .first { it.questNo == 1 }
        // 改成 0 → 「确定没出失败牌」，这是一次真实的补录，必须允许进入预览
        val filled = events.missionResolved(
            questNo = mission.questNo,
            teamSizeExpected = mission.teamSizeExpected,
            teamSeats = mission.teamSeats,
            failCount = 0,
            observedOutcome = mission.observedOutcome
        )
        val result = planResult(match, mission.eventId, filled)

        assertTrue(
            "null（未记录）改成 0（确定零失败）是真实修改，不能被当成无变化，实际 $result",
            result is EditPlanResult.Ready
        )
    }

    @Test
    fun voteApproveCountUnknownVersusZeroIsARealChange() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0, approve = null, reject = null)

        val vote = bundle(match).events.filterIsInstance<TeamVoteRecorded>().first { it.questNo == 1 }
        val filled = events.teamVoteRecorded(
            questNo = vote.questNo,
            proposalNo = vote.proposalNo,
            playerCountAtVote = vote.playerCountAtVote,
            votesBySeat = vote.votesBySeat,
            approveCount = 0,
            rejectCount = 0,
            unknownVoteCount = vote.unknownVoteCount
        )
        val result = planResult(match, vote.eventId, filled)

        assertTrue(
            "null 票数补录为 0 是真实修改，实际 $result",
            result is EditPlanResult.Ready
        )
    }

    @Test
    fun changingOnlyPlayerCountAtVoteIsARealChange() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0)

        val vote = bundle(match).events.filterIsInstance<TeamVoteRecorded>().first { it.questNo == 1 }
        val edited = events.teamVoteRecorded(
            questNo = vote.questNo,
            proposalNo = vote.proposalNo,
            playerCountAtVote = vote.playerCountAtVote + 1,
            votesBySeat = vote.votesBySeat,
            approveCount = vote.approveCount,
            rejectCount = vote.rejectCount,
            unknownVoteCount = vote.unknownVoteCount
        )
        val result = planResult(match, vote.eventId, edited)

        assertTrue(
            "投票时人数决定半数口径，改它就是业务修改，实际 $result",
            result is EditPlanResult.Ready
        )
    }

    // ————————————————————————————————————————————————————————
    // 4. 合法修改仍然走「预览 → 单事务修订」
    // ————————————————————————————————————————————————————————

    @Test
    fun realTeamChangeStillPreviewsAndCommits() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0)

        val proposal = bundle(match).events.filterIsInstance<TeamProposed>().first { it.questNo == 1 }
        val changed = events.teamProposed(1, 1, proposal.leaderSeat, listOf(4, 5, 6))

        val planned = planResult(match, proposal.eventId, changed)
        assertTrue("合法修改必须能预览，实际 $planned", planned is EditPlanResult.Ready)
        val plan = (planned as EditPlanResult.Ready).plan

        val applied = repository.applyConfirmedEdit(
            matchId = match,
            targetEventId = proposal.eventId,
            replacement = changed,
            confirmedRetractIds = plan.retractIds,
            expectedTokens = plan.expectedActive
        )
        assertTrue("合法修改必须提交成功，实际 $applied", applied is EditApplyResult.Success)

        // 修订确实发生了：原行 retracted，新行 revisionSeq = 1 且逻辑位置继承
        val after = rows(match)
        val old = after.first { it.id == proposal.eventId.raw }
        val fresh = after.first { it.id == changed.eventId.raw }
        assertTrue("旧版本必须被撤销（不是物理删除）", old.retracted)
        assertEquals("新版本必须是第 1 次修订", 1, fresh.revisionSeq)
        assertEquals("修订必须继承逻辑位置", old.logicalPosition, fresh.logicalPosition)
    }

    @Test
    fun resolvingUnknownMissionCountsAsChangeButKeepsPeerRows() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = null)

        val mission = bundle(match).events.filterIsInstance<MissionResolved>()
            .first { it.questNo == 1 }
        val filled = events.missionResolved(
            questNo = mission.questNo,
            teamSizeExpected = mission.teamSizeExpected,
            teamSeats = mission.teamSeats,
            failCount = 0,
            observedOutcome = mission.observedOutcome
        )
        val plan = (planResult(match, mission.eventId, filled) as EditPlanResult.Ready).plan

        // 补录失败牌不改变终局性 → 不得连带撤销任何其它记录
        assertTrue(
            "补录失败牌不应产生依赖撤销，实际 ${plan.retractIds.map { it.raw }}",
            plan.retractIds.isEmpty()
        )

        val applied = repository.applyConfirmedEdit(
            matchId = match,
            targetEventId = mission.eventId,
            replacement = filled,
            confirmedRetractIds = plan.retractIds,
            expectedTokens = plan.expectedActive
        )
        assertTrue("补录应成功，实际 $applied", applied is EditApplyResult.Success)

        val after = rows(match)
        assertEquals("旧任务结果被撤销", true, after.first { it.id == mission.eventId.raw }.retracted)
        assertEquals(
            "同任务其它记录不受影响",
            0,
            after.count { it.retracted && it.eventType != mission.type.name && it.id != mission.eventId.raw }
        )
        assertNotEquals(
            "修订后不应复用旧行 id",
            mission.eventId.raw,
            filled.eventId.raw
        )
    }

    // ————————————————————————————————————————————————————————
    // 5. 闸门顺序：NoChange 不掩盖真正的前置错误，也不会被令牌校验抢走
    // ————————————————————————————————————————————————————————

    @Test
    fun noChangeCheckRunsBeforeTokenValidation() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0)

        val proposal = bundle(match).events.filterIsInstance<TeamProposed>().first { it.questNo == 1 }
        val before = historySignature(match)
        val updatedBefore = updatedAt(match)

        // 直接确认（模拟「预览后 UI 手里的令牌已经过期」）→ 业务等价必须优先判出 NoChange
        val identical = events.teamProposed(1, 1, proposal.leaderSeat, proposal.teamSeats)
        val applied = repository.applyConfirmedEdit(
            matchId = match,
            targetEventId = proposal.eventId,
            replacement = identical,
            confirmedRetractIds = emptyList(),
            expectedTokens = emptyList()
        )

        assertEquals(
            "业务等价必须在令牌校验之前判出，实际 $applied",
            EditApplyResult.NoChange,
            applied
        )
        assertNothingWritten(match, before, updatedBefore)
    }

    @Test
    fun missingTargetStillReportsTargetNotFoundInsteadOfNoChange() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0)

        val proposal = bundle(match).events.filterIsInstance<TeamProposed>().first { it.questNo == 1 }
        // 先合法改一次，让原事件成为「已撤销的旧版本」
        val changed = events.teamProposed(1, 1, proposal.leaderSeat, listOf(4, 5, 6))
        val plan = (planResult(match, proposal.eventId, changed) as EditPlanResult.Ready).plan
        repository.applyConfirmedEdit(
            matchId = match,
            targetEventId = proposal.eventId,
            replacement = changed,
            confirmedRetractIds = plan.retractIds,
            expectedTokens = plan.expectedActive
        )

        // 再对着已失效的旧 id 提交「与原值相同」的内容：必须报目标失效，不能被误判成 NoChange
        val stale = events.teamProposed(1, 1, proposal.leaderSeat, proposal.teamSeats)
        val result = planResult(match, proposal.eventId, stale)

        assertEquals(
            "目标已失效时必须是 TARGET_NOT_FOUND",
            EditPlanRejection.TARGET_NOT_FOUND,
            (result as? EditPlanResult.Rejected)?.reason
        )
    }

    @Test
    fun nonEditableEventTypeIsStillRejectedBeforeNoChangeCheck() = runBlocking {
        val match = createMatch()
        appendCycle(match, 1, 1, listOf(1, 2, 3), failCount = 0)

        val resolution = bundle(match).events.filterIsInstance<TeamProposalResolved>()
            .first { it.questNo == 1 }
        val before = historySignature(match)
        val updatedBefore = updatedAt(match)

        // 内容完全一致的「替换」也不得因为「没变化」而被放行到别处
        val identical = events.teamProposalResolved(
            questNo = resolution.questNo,
            proposalNo = resolution.proposalNo,
            decision = resolution.decision,
            playerCountAtVote = resolution.playerCountAtVote,
            approveCount = resolution.approveCount,
            rejectCount = resolution.rejectCount
        )
        val result = planResult(match, resolution.eventId, identical)

        assertEquals(
            "B35/B37：决议不可编辑，先于「无变化」判定被拒",
            EditPlanRejection.TARGET_NOT_EDITABLE,
            (result as? EditPlanResult.Rejected)?.reason
        )
        assertNothingWritten(match, before, updatedBefore)
    }
}
