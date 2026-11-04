package com.roundtrace.app.feature.reveal

import com.roundtrace.app.core.data.MatchBundle
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.Participant
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.RoleSlotSpec
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.GameReducer
import com.roundtrace.app.core.util.SequentialIdGenerator
import com.roundtrace.app.core.util.fixedClock
import com.roundtrace.app.core.model.EventFactory
import com.roundtrace.app.knowledge.AvalonScenario
import com.roundtrace.app.util.FakeMatchRepository
import com.roundtrace.app.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 揭晓 ViewModel 的配额 / 二次确认 / 草稿与原子写入（REVEAL-Q-01 / Phase D）。
 *
 * 覆盖 ID-12 / 14 / 17 / 18 与「揭晓行显示玩家身份」：
 * - 8 人局 3 忠臣可以逐个选到三个座位；
 * - 改选释放名额；UNKNOWN 不补齐；
 * - 计划与真实身份不一致 → 明确警告 + 独立确认，绝不自动改计划/真相；
 * - 保存失败/重复点击不部分写入、草稿保留。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RevealQuotaViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val config = AvalonScenario.eightPlayerConfig()

    private fun repo(): FakeMatchRepository = FakeMatchRepository(
        config = config,
        initialEvents = AvalonScenario.fullMatch().events()
    )

    private fun viewModel(repo: FakeMatchRepository): RevealViewModel {
        val registry = DefaultGameRulesRegistry()
        val reducer: GameReducer = registry.avalonReducer
        return RevealViewModel(
            matchId = MatchId("fake-match"),
            repository = repo,
            reducer = reducer,
            eventFactory = EventFactory(SequentialIdGenerator("reveal"), fixedClock(1L))
        )
    }

    @Test
    fun quotaIsTakenFromThisMatchRoleSlots() = runTest {
        val vm = viewModel(repo())
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(3, state.roleQuota[RoleId.LOYAL_SERVANT])
        assertEquals(1, state.roleQuota[RoleId.MERLIN])
        assertEquals(1, state.roleQuota[RoleId.PERCIVAL])
        // 8 人局的 roleSlots 已在夹具里，不是从人数硬编码出来的
        assertEquals(3, config.roleSlots.first { it.roleId == RoleId.LOYAL_SERVANT }.count)
    }

    @Test
    fun threeLoyalServantsCanBeSelectedOnThreeSeats() = runTest {
        val vm = viewModel(repo())
        advanceUntilIdle()
        (1..3).forEach { seat ->
            vm.onAction(RevealAction.SetSeatRole(seat, RoleId.LOYAL_SERVANT))
        }
        advanceUntilIdle()
        val rows = vm.state.value.rows
        assertEquals(3, rows.count { it.roleId == RoleId.LOYAL_SERVANT })
        assertEquals(listOf(1, 2, 3), rows.filter { it.roleId == RoleId.LOYAL_SERVANT }.map { it.seat })
    }

    @Test
    fun switchingRoleReleasesTheOldQuota() = runTest {
        val vm = viewModel(repo())
        advanceUntilIdle()
        (1..3).forEach { seat -> vm.onAction(RevealAction.SetSeatRole(seat, RoleId.LOYAL_SERVANT)) }
        vm.onAction(RevealAction.SetSeatRole(3, RoleId.PERCIVAL))
        advanceUntilIdle()
        assertEquals(2, vm.state.value.rows.count { it.roleId == RoleId.LOYAL_SERVANT })
        assertEquals(RoleId.PERCIVAL, vm.state.value.rows.single { it.seat == 3 }.roleId)
    }

    @Test
    fun unknownNeverAutofillsAndNeverConsumesQuota() = runTest {
        val vm = viewModel(repo())
        advanceUntilIdle()
        (1..5).forEach { seat -> vm.onAction(RevealAction.SetSeatRole(seat, RoleId.UNKNOWN)) }
        advanceUntilIdle()
        assertEquals(0, vm.state.value.roleQuota[RoleId.UNKNOWN] ?: 0)
        assertEquals(8, vm.state.value.rows.count { it.roleId == RoleId.UNKNOWN })
        // 没有把任何 UNKNOWN 补成忠臣
        assertEquals(0, vm.state.value.rows.count { it.roleId == RoleId.LOYAL_SERVANT })
    }

    @Test
    fun overPlanTruthRequiresExplicitConfirmationBeforeWrite() = runTest {
        val repo = repo()
        val vm = viewModel(repo)
        advanceUntilIdle()
        // 真实记录：4 个忠臣，但计划只有 3
        (1..4).forEach { seat -> vm.onAction(RevealAction.SetSeatRole(seat, RoleId.LOYAL_SERVANT)) }
        advanceUntilIdle()
        assertTrue(vm.state.value.planMismatchWarning.contains("计划"))
        assertTrue(vm.state.value.requiresPlanMismatchConfirmation)

        vm.onAction(RevealAction.Reveal)
        advanceUntilIdle()
        // 未确认前不得写库
        assertEquals(null, repo.getBundle(MatchId("fake-match"))!!.revealedAt)
        assertEquals(4, vm.state.value.rows.count { it.roleId == RoleId.LOYAL_SERVANT })

        vm.onAction(RevealAction.ConfirmPlanMismatch)
        vm.onAction(RevealAction.Reveal)
        advanceUntilIdle()
        // 确认后按**真实记录**写入：计划仍是 3，真相仍是 4
        assertEquals(3, config.roleSlots.first { it.roleId == RoleId.LOYAL_SERVANT }.count)
        assertEquals(4, repo.getBundle(MatchId("fake-match"))!!.roleAssignments.values
            .count { it.roleId == RoleId.LOYAL_SERVANT })
        assertFalse(vm.state.value.requiresPlanMismatchConfirmation)
    }

    @Test
    fun changingRowsAfterConfirmationInvalidatesIt() = runTest {
        val vm = viewModel(repo())
        advanceUntilIdle()
        (1..4).forEach { seat -> vm.onAction(RevealAction.SetSeatRole(seat, RoleId.LOYAL_SERVANT)) }
        vm.onAction(RevealAction.ConfirmPlanMismatch)
        advanceUntilIdle()
        assertTrue(vm.state.value.planMismatchConfirmed)

        vm.onAction(RevealAction.SetSeatRole(5, RoleId.MERLIN))
        advanceUntilIdle()
        assertFalse("行变化后必须重新确认", vm.state.value.planMismatchConfirmed)
        assertTrue(vm.state.value.requiresPlanMismatchConfirmation)
    }

    @Test
    fun failedWriteKeepsDraftAndDoesNotPartiallyReveal() = runTest {
        val repo = repo()
        repo.failNextWrite = true
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.onAction(RevealAction.SetSeatRole(1, RoleId.MERLIN))
        vm.onAction(RevealAction.Reveal)
        advanceUntilIdle()

        assertEquals(null, repo.getBundle(MatchId("fake-match"))!!.revealedAt)
        assertEquals(RoleId.MERLIN, vm.state.value.rows.single { it.seat == 1 }.roleId)
        assertEquals(
            com.roundtrace.app.core.ui.component.SaveStatus.FAILED,
            vm.state.value.saveStatus
        )
    }

    @Test
    fun revealRowsExposePlayerIdentity() = runTest {
        val repo = repo()
        repo.updateParticipants(
            MatchId("fake-match"),
            listOf(
                Participant(1, "阿华", avatarUri = "content://a/1"),
                Participant(2, "阿华", avatarUri = null)
            )
        )
        val vm = viewModel(repo)
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals("阿华（1号）", state.seatIdentity.display(1).label)
        assertEquals("阿华（2号）", state.seatIdentity.display(2).label)
        assertEquals("content://a/1", state.seatIdentity.display(1).avatarUri)
    }
}
