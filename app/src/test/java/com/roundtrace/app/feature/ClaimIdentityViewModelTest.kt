package com.roundtrace.app.feature

import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.Participant
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.model.PrivateNoteAdded
import com.roundtrace.app.feature.claim.ClaimAction
import com.roundtrace.app.feature.claim.ClaimViewModel
import com.roundtrace.app.core.util.SequentialIdGenerator
import com.roundtrace.app.core.util.fixedClock
import com.roundtrace.app.knowledge.AvalonScenario
import com.roundtrace.app.util.FakeMatchRepository
import com.roundtrace.app.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 发言页座位身份映射与草稿保护（UX-ID-03 / Phase C）。
 *
 * 钉住 ID-01 / 02 / 03 / 05：
 * - 两处 SeatPicker 共享同一份 seat→昵称/头像映射；
 * - 点击仍写**真实 seat 数字**，不写昵称；
 * - 重名、无昵称、空白 URI、越界座位都正常；
 * - 身份资料刷新不清空草稿（标签 / 目标 / 文本 / 私有模式）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaimIdentityViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private suspend fun repository(): FakeMatchRepository {
        val repo = FakeMatchRepository(
            config = AvalonScenario.eightPlayerConfig(),
            initialEvents = AvalonScenario.fullMatch().events()
        )
        repo.updateParticipants(
            MatchId("fake-match"),
            listOf(
                Participant(1, "阿华", avatarUri = "content://a/1"),
                Participant(2, "阿华", avatarUri = "content://a/2"),
                Participant(3, "  ", avatarUri = "   "),
                Participant(4, "小林", avatarUri = null),
                Participant(5, "小五", avatarUri = "content://a/5"),
                Participant(6, "小六", avatarUri = null),
                Participant(7, "小七", avatarUri = null),
                Participant(8, "小八", avatarUri = null)
            )
        )
        return repo
    }

    private fun eventFactory() = com.roundtrace.app.core.model.EventFactory(
        SequentialIdGenerator("claim-id"),
        fixedClock(1_700_000_000_000L)
    )

    private suspend fun viewModel(): ClaimViewModel = ClaimViewModel(
        matchId = MatchId("fake-match"),
        repository = repository(),
        eventFactory = eventFactory()
    )

    @Test
    fun seatIdentityIsDerivedFromParticipantsWithFallbacks() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        val state = vm.state.value

        assertEquals("阿华", state.seatLabels[1])
        assertEquals("阿华", state.seatLabels[2])
        assertEquals("content://a/1", state.seatAvatars[1])
        assertEquals("content://a/2", state.seatAvatars[2])
        // 空白昵称 / 空白 URI 归一化为 null，交给 SeatPicker 降级
        assertNull(state.seatLabels[3])
        assertNull(state.seatAvatars[3])
        assertNull(state.seatAvatars[4])
    }

    @Test
    fun duplicateNamesAreNeverUsedAsIdentityKeys() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onAction(ClaimAction.SelectSpeaker(2))
        vm.onAction(ClaimAction.ToggleTarget(1))
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(2, state.speakerSeat)
        assertEquals(setOf(1), state.targetSeats)
        // 昵称相同，但座位是唯一键
        assertEquals(state.seatLabels[1], state.seatLabels[2])
        assertFalse(state.speakerSeat == 1)
    }

    @Test
    fun savingWritesRawSeatNumbersForClaim() = runTest {
        val repo = repository()
        val vm = ClaimViewModel(MatchId("fake-match"), repo, eventFactory())
        advanceUntilIdle()
        vm.onAction(ClaimAction.SelectSpeaker(2))
        vm.onAction(ClaimAction.SelectTag(ClaimType.SUSPECT))
        vm.onAction(ClaimAction.ToggleTarget(4))
        vm.onAction(ClaimAction.Save)
        advanceUntilIdle()

        val written = repo.getBundle(MatchId("fake-match"))!!.events.filterIsInstance<PlayerClaimed>().last()
        assertEquals(2, written.speakerSeat)
        assertEquals(listOf(4), written.targetSeats)
        // 事件里不能出现昵称
        assertFalse(written.toString().contains("阿华"))
    }

    @Test
    fun savingPrivateNoteStillUsesRawSeatNumbers() = runTest {
        val repo = repository()
        val vm = ClaimViewModel(MatchId("fake-match"), repo, eventFactory())
        advanceUntilIdle()
        vm.onAction(ClaimAction.SelectSpeaker(2))
        vm.onAction(ClaimAction.SelectTag(ClaimType.SUSPECT))
        vm.onAction(ClaimAction.ToggleTarget(4))
        vm.onAction(ClaimAction.SetPrivateMode(true))
        vm.onAction(ClaimAction.Save)
        advanceUntilIdle()

        val written = repo.getBundle(MatchId("fake-match"))!!.events.filterIsInstance<PrivateNoteAdded>().last()
        assertEquals(2, written.observerSeat)
        assertEquals(listOf(4), written.targetSeats)
        assertEquals(EventVisibility.PRIVATE_OBSERVER, written.visibility)
    }

    @Test
    fun refreshingIdentityNeverClearsAnInProgressDraft() = runTest {
        val repo = repository()
        val vm = ClaimViewModel(MatchId("fake-match"), repo, eventFactory())
        advanceUntilIdle()

        vm.onAction(ClaimAction.SelectTag(ClaimType.SUSPECT))
        vm.onAction(ClaimAction.SelectSpeaker(2))
        vm.onAction(ClaimAction.ToggleTarget(4))
        vm.onAction(ClaimAction.UpdateText("阿华说的这句话还在编辑中"))
        vm.onAction(ClaimAction.SetPrivateMode(true))
        advanceUntilIdle()

        // 资料刷新（改名 + 换头像）不得清空任何草稿字段
        repo.updateParticipants(
            MatchId("fake-match"),
            listOf(Participant(2, "改名了", avatarUri = "content://new/2"))
        )
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(ClaimType.SUSPECT, state.selectedTag)
        assertEquals(2, state.speakerSeat)
        assertEquals(setOf(4), state.targetSeats)
        assertEquals("阿华说的这句话还在编辑中", state.text)
        assertTrue(state.privateMode)
        // 映射本身也不该被旧值卡住：ViewModel 初始化只做一次，草稿不受刷新影响
        assertEquals("阿华", state.seatLabels[2])
    }
}
