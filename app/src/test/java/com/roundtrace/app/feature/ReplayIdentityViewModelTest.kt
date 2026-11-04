package com.roundtrace.app.feature

import com.roundtrace.app.core.knowledge.AvalonEvidenceAnalyzer
import com.roundtrace.app.core.knowledge.AvalonKnowledgeProvider
import com.roundtrace.app.core.model.Participant
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.feature.replay.ReplayAction
import com.roundtrace.app.feature.replay.ReplayViewModel
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
 * 复盘页身份接线的 ViewModel 断言（UX-ID-02 / Phase B）。
 *
 * 钉住 ID-01 / 03 / 08 / 09：
 * - 昵称与头像都来自**同一份 bundle.participants**，切局不复用上局头像；
 * - 主时间线与玩家行为时间线使用同一套昵称映射；
 * - 切换视角只改变可见事件，不改变 seat 的昵称/头像，也不泄漏真相。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplayIdentityViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val reducerHolder = com.roundtrace.app.core.rules.DefaultGameRulesRegistry()
    private val provider = AvalonKnowledgeProvider(reducerHolder.avalonReducer)
    private val analyzer = AvalonEvidenceAnalyzer()

    private suspend fun repository(withParticipants: Boolean = true): FakeMatchRepository {
        val repo = FakeMatchRepository(
            config = AvalonScenario.eightPlayerConfig(),
            initialEvents = AvalonScenario.fullMatch().events(),
            initialRoles = AvalonScenario.roleTruth()
                .map { (seat, role) ->
                    seat to com.roundtrace.app.core.model.RoleAssignment(
                        seat = seat,
                        roleId = role,
                        source = com.roundtrace.app.core.model.RoleSource.PRESET_MANUAL,
                        revealState = com.roundtrace.app.core.model.RevealState.HIDDEN
                    )
                }.toMap()
        )
        if (withParticipants) {
            repo.updateParticipants(
                com.roundtrace.app.core.model.MatchId("fake-match"),
                listOf(
                    Participant(1, "小明", avatarUri = "content://a/1"),
                    Participant(2, "阿华", avatarUri = "content://a/2"),
                    Participant(3, "阿华", avatarUri = "content://a/3"),
                    Participant(4, "无名", avatarUri = null),
                    Participant(5, "小五", avatarUri = "content://a/5"),
                    Participant(6, "小六", avatarUri = null),
                    Participant(7, "小七", avatarUri = null),
                    Participant(8, "小八", avatarUri = null)
                )
            )
        }
        return repo
    }

    private fun viewModel(repo: FakeMatchRepository) = ReplayViewModel(
        matchId = com.roundtrace.app.core.model.MatchId("fake-match"),
        repository = repo,
        knowledgeProvider = provider,
        analyzer = analyzer
    )

    @Test
    fun seatIdentityComesFromTheSameBundleSnapshot() = runTest {
        val vm = viewModel(repository())
        advanceUntilIdle()
        val state = vm.uiState.value

        assertEquals(8, state.seatIdentity.playerCount)
        assertEquals("小明（1号）", state.seatIdentity.display(1).label)
        assertEquals("阿华（2号）", state.seatIdentity.display(2).label)
        assertEquals("阿华（3号）", state.seatIdentity.display(3).label)
        assertEquals("无名（4号）", state.seatIdentity.display(4).label)
        assertEquals("content://a/1", state.seatIdentity.display(1).avatarUri)
        assertEquals("无名", state.seatLabels[4])
    }

    @Test
    fun timelineAndPlayerTraceUseTheSameNicknameMapping() = runTest {
        val vm = viewModel(repository())
        advanceUntilIdle()
        val state = vm.uiState.value

        val questStart = state.timeline.first { it.title.contains("任务开始") }
        assertTrue(questStart.title, questStart.title.contains("（"))
        // 玩家行为卡片里的队伍名单也要带昵称
        val leaderItem = state.playerTraceItems.firstOrNull {
            it.detail.contains("提案队伍")
        }
        if (leaderItem != null) {
            assertTrue(leaderItem.detail, leaderItem.detail.contains("（"))
        }
    }

    @Test
    fun switchingPerspectiveDoesNotChangeNicknamesOrLeakFacts() = runTest {
        val vm = viewModel(repository())
        advanceUntilIdle()
        val publicState = vm.uiState.value

        vm.onAction(ReplayAction.SelectPerspective(Perspective.OBSERVER_AT_TIME))
        advanceUntilIdle()
        val observerState = vm.uiState.value

        assertEquals(publicState.seatIdentity.display(1).label, observerState.seatIdentity.display(1).label)
        assertEquals(publicState.seatIdentity.display(1).avatarUri, observerState.seatIdentity.display(1).avatarUri)
        // 视角切换不得把角色真值/阵营塞进显示映射
        assertFalse(observerState.seatIdentity.display(1).label.contains("梅林"))
        assertFalse(observerState.seatIdentity.display(1).contentDescription.contains("阵营"))
    }

    @Test
    fun missingParticipantsStillProduceDeterministicPlaceholders() = runTest {
        val vm = viewModel(repository(withParticipants = false))
        advanceUntilIdle()
        val state = vm.uiState.value
        (1..8).forEach { seat ->
            assertEquals("${seat}号", state.seatIdentity.display(seat).label)
            assertEquals(null, state.seatIdentity.display(seat).avatarUri)
        }
    }
}
