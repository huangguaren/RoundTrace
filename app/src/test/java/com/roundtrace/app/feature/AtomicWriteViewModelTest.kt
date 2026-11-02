package com.roundtrace.app.feature

import com.roundtrace.app.core.data.AppendResult
import com.roundtrace.app.core.data.MatchRepository
import com.roundtrace.app.core.data.RepositoryError
import com.roundtrace.app.core.model.EventFactory
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.RoleAssignment
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.ui.component.SaveStatus
import com.roundtrace.app.core.util.SequentialIdGenerator
import com.roundtrace.app.core.util.fixedClock
import com.roundtrace.app.feature.record.RecordAction
import com.roundtrace.app.feature.record.RecordViewModel
import com.roundtrace.app.feature.record.UndoFeedback
import com.roundtrace.app.feature.reveal.RevealAction
import com.roundtrace.app.feature.reveal.RevealViewModel
import com.roundtrace.app.util.FakeMatchRepository
import com.roundtrace.app.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 锁定实际 ViewModel 入口，防止事务端口已具备但页面仍逐条写入。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AtomicWriteViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val registry = DefaultGameRulesRegistry()
    private val matchId = MatchId("fake-match")
    private val factory = EventFactory(SequentialIdGenerator("vm-atomic"), fixedClock(1_700_000_000_000L))
    private val config = MatchCreationDraft(
        title = "原子写入页面测试",
        playerCount = 8,
        mySeat = 1,
        roleSlots = AvalonPreset.standardPreset(8),
        initialLeaderSeat = 1,
        presetId = AvalonPreset.presetId(8)
    ).toGameConfig(0L)

    private class Writes(
        val fake: FakeMatchRepository,
        var reject: Boolean = false,
        var gate: CompletableDeferred<Unit>? = null
    ) : MatchRepository by fake {
        var revealCalls = 0
        val cutoffs = mutableListOf<Int>()

        override suspend fun revealMatch(matchId: MatchId, assignments: List<RoleAssignment>): AppendResult {
            revealCalls++
            gate?.await()
            return if (reject) AppendResult(matchId, null, null, error = RepositoryError.WRITE_FAILED)
            else fake.revealMatch(matchId, assignments)
        }

        override suspend fun retractAfterOrder(matchId: MatchId, logicalPosition: Int): AppendResult {
            cutoffs += logicalPosition
            gate?.await()
            return if (reject) AppendResult(matchId, null, null, error = RepositoryError.WRITE_FAILED)
            else fake.retractAfterOrder(matchId, logicalPosition)
        }

        override suspend fun saveRoleAssignments(matchId: MatchId, assignments: List<RoleAssignment>) {
            error("页面应使用 revealMatch 原子端口")
        }

        override suspend fun revealAllRoles(matchId: MatchId): AppendResult =
            error("页面应使用 revealMatch 原子端口")

        override suspend fun retractEvent(matchId: MatchId, eventId: EventId): AppendResult =
            error("回滚应使用批量事务端口")
    }

    private fun reveal(repository: MatchRepository) = RevealViewModel(
        matchId, repository, registry.avalonReducer, factory
    )

    private fun record(repository: MatchRepository) = RecordViewModel(
        matchId, repository, registry, registry.avalonReducer, registry.avalonRuleSet, factory
    )

    private fun rollbackRepository() = Writes(FakeMatchRepository(
        config,
        initialEvents = listOf(
            factory.questStarted(1, 1).copy(occurredOrder = 1),
            factory.questStarted(2, 2).copy(occurredOrder = 2)
        )
    ))

    @Test
    fun revealUsesOnlyAtomicPortAndRefreshesCommittedRoles() = runTest {
        val writes = Writes(FakeMatchRepository(config))
        val vm = reveal(writes)
        advanceUntilIdle()
        vm.onAction(RevealAction.SetSeatRole(1, RoleId.MERLIN))
        vm.onAction(RevealAction.Reveal)
        advanceUntilIdle()
        assertEquals(1, writes.revealCalls)
        assertEquals(SaveStatus.SAVED, vm.state.value.saveStatus)
        assertNotNull(vm.state.value.revealedAt)
        assertEquals(RoleId.MERLIN, vm.state.value.rows.first().roleId)
    }

    @Test
    fun failedRevealResultKeepsInputAndDoesNotPretendToBeSaved() = runTest {
        val writes = Writes(FakeMatchRepository(config), reject = true)
        val vm = reveal(writes)
        advanceUntilIdle()
        vm.onAction(RevealAction.SetSeatRole(1, RoleId.MERLIN))
        vm.onAction(RevealAction.Reveal)
        advanceUntilIdle()
        assertEquals(SaveStatus.FAILED, vm.state.value.saveStatus)
        assertEquals(RoleId.MERLIN, vm.state.value.rows.first().roleId)
        assertNull(vm.state.value.revealedAt)
        assertNotNull(vm.state.value.errorMessage)
    }

    @Test
    fun repeatedRevealDuringSaveMakesOnlyOneAtomicCall() = runTest {
        val gate = CompletableDeferred<Unit>()
        val writes = Writes(FakeMatchRepository(config), gate = gate)
        val vm = reveal(writes)
        advanceUntilIdle()
        vm.onAction(RevealAction.Reveal)
        vm.onAction(RevealAction.Reveal)
        assertEquals(1, writes.revealCalls)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(SaveStatus.SAVED, vm.state.value.saveStatus)
    }

    @Test
    fun rollbackUsesOneBulkCallAndPreservesTargetQuest() = runTest {
        val writes = rollbackRepository()
        val vm = record(writes)
        advanceUntilIdle()
        vm.onAction(RecordAction.RollbackToQuest(1))
        advanceUntilIdle()
        assertEquals(listOf(1), writes.cutoffs)
        assertEquals(listOf(1), writes.fake.bundle()!!.events.map { it.questNo })
        assertEquals(UndoFeedback.DONE, vm.uiState.value.undoFeedback)
    }

    @Test
    fun nonexistentRollbackQuestDoesNotClearTheWholeMatch() = runTest {
        val writes = rollbackRepository()
        val before = writes.fake.bundle()!!.events
        val vm = record(writes)
        advanceUntilIdle()
        vm.onAction(RecordAction.RollbackToQuest(5))
        advanceUntilIdle()
        assertTrue(writes.cutoffs.isEmpty())
        assertEquals(before, writes.fake.bundle()!!.events)
        assertEquals(SaveStatus.FAILED, vm.uiState.value.saveStatus)
    }

    @Test
    fun bulkRollbackFailureIsReportedAndHistoryRemains() = runTest {
        val writes = rollbackRepository().apply { reject = true }
        val before = writes.fake.bundle()!!.events
        val vm = record(writes)
        advanceUntilIdle()
        vm.onAction(RecordAction.RollbackToQuest(1))
        advanceUntilIdle()
        assertEquals(before, writes.fake.bundle()!!.events)
        assertEquals(SaveStatus.FAILED, vm.uiState.value.saveStatus)
        assertEquals(UndoFeedback.FAILED, vm.uiState.value.undoFeedback)
    }

    @Test
    fun repeatedRollbackDuringSaveMakesOneBulkCall() = runTest {
        val gate = CompletableDeferred<Unit>()
        val writes = rollbackRepository().apply { this.gate = gate }
        val vm = record(writes)
        advanceUntilIdle()
        vm.onAction(RecordAction.RollbackToQuest(1))
        vm.onAction(RecordAction.RollbackToQuest(1))
        assertEquals(listOf(1), writes.cutoffs)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(UndoFeedback.DONE, vm.uiState.value.undoFeedback)
    }
}
