package com.roundtrace.app.feature

import com.roundtrace.app.core.knowledge.AvalonEvidenceAnalyzer
import com.roundtrace.app.core.knowledge.AvalonKnowledgeProvider
import com.roundtrace.app.core.model.BeliefType
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.PrivateNoteAdded
import com.roundtrace.app.core.replay.PlayerTraceCategory
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 玩家行为时间线的 ViewModel 测试（F05-01 Phase B）。
 *
 * 钉住四件事：
 * 1. 默认座位 = 观察者（配置合法时），非法选择回落到默认而不是把非法值传下去；
 * 2. 座位 / 任务 / 类别三个筛选都能改变列表；
 * 3. 视角切换后列表随之变化（PUBLIC 看不到私人笔记，OBSERVER 本人可见）；
 * 4. `SelectPosition` 仍走既有入口：位置一变，列表按时间上界收敛。
 *
 * 夹具：`AvalonScenario`（1 号 = 观察者梅林，且**从未上车**）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerTraceViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private companion object {
        const val PRIVATE_NOTE_ID = "trace-private-note"
    }

    private val reducerHolder = com.roundtrace.app.core.rules.DefaultGameRulesRegistry()
    private val provider = AvalonKnowledgeProvider(reducerHolder.avalonReducer)
    private val analyzer = AvalonEvidenceAnalyzer()

    private fun repository(
        observerSeat: Int = AvalonScenario.MERLIN,
        withPrivateNote: Boolean = true
    ): FakeMatchRepository {
        val builder = AvalonScenario.fullMatch()
        if (withPrivateNote) {
            builder.appendRaw(
                PrivateNoteAdded(
                    eventId = EventId(PRIVATE_NOTE_ID),
                    occurredOrder = 900,
                    recordedAt = 0L,
                    visibility = EventVisibility.PRIVATE_OBSERVER,
                    questNo = 3,
                    observerSeat = AvalonScenario.MERLIN,
                    beliefType = BeliefType.SUSPECT,
                    targetSeats = listOf(AvalonScenario.MORDRED),
                    text = "私下记：4 号可疑",
                    availableFromOrder = 1
                )
            )
        }
        val config = AvalonScenario.eightPlayerConfig().copy(mySeat = observerSeat)
        return FakeMatchRepository(
            config = config,
            initialEvents = builder.events(),
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
    }

    private fun newViewModel(repo: FakeMatchRepository) = ReplayViewModel(
        matchId = MatchId("fake-match"),
        repository = repo,
        knowledgeProvider = provider,
        analyzer = analyzer
    )

    @Test
    fun defaultTraceSeatIsObserverSeat() = runTest {
        val viewModel = newViewModel(repository())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("默认应选中观察者座位", AvalonScenario.MERLIN, state.playerTraceSeat)
        // 夹具里 1 号：任务 4 被提名上车（提案 1/3/4/5/8）+ 记入任务结果（1/3/4），
        // 任务 3 被 7 号的「声称派西维尔」提及（targetSeats = 1/3）
        assertTrue("1 号应有公开记录", state.playerTraceItems.isNotEmpty())
        assertEquals(
            "1 号的公开记录只应包含「被提及 / 被提名 / 任务队员」三类：${state.playerTraceItems.map { it.category }}",
            setOf(
                PlayerTraceCategory.MENTIONED,
                PlayerTraceCategory.NOMINATED,
                PlayerTraceCategory.MISSION_MEMBER
            ),
            state.playerTraceItems.map { it.category }.toSet()
        )
        assertTrue(
            "被提及条目必须点明提及者：${state.playerTraceItems.map { it.title }}",
            state.playerTraceItems.single { it.category == PlayerTraceCategory.MENTIONED }
                .title.contains("7 号")
        )
        assertTrue(
            "默认视角是公开视角，不得出现私人笔记",
            state.playerTraceItems.none { it.category == PlayerTraceCategory.PRIVATE_NOTE }
        )
        assertEquals("默认不过滤任务", null, state.playerTraceQuestFilter)
        assertEquals("默认不过滤类别", null, state.playerTraceCategoryFilter)
    }

    @Test
    fun selectingSeatSwitchesTheTrace() = runTest {
        val viewModel = newViewModel(repository())
        advanceUntilIdle()

        viewModel.onAction(ReplayAction.SelectPlayerTraceSeat(AvalonScenario.LOYAL_2))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(AvalonScenario.LOYAL_2, state.playerTraceSeat)
        assertTrue("2 号是任务 1 的队长，轨迹不应为空", state.playerTraceItems.isNotEmpty())
        assertTrue(
            "2 号应有队长记录：${state.playerTraceItems.map { it.category }}",
            state.playerTraceItems.any { it.category == PlayerTraceCategory.LEADER }
        )
        assertTrue(
            "2 号应被记录为任务队员",
            state.playerTraceItems.any { it.category == PlayerTraceCategory.MISSION_MEMBER }
        )
        assertTrue(
            "公开视角不得出现私人笔记",
            state.playerTraceItems.none { it.category == PlayerTraceCategory.PRIVATE_NOTE }
        )
        assertTrue("列表必须按时间升序", state.playerTraceItems.map { it.occurredOrder }.let { it == it.sorted() })
    }

    @Test
    fun illegalSeatSelectionFallsBackToDefault() = runTest {
        val viewModel = newViewModel(repository())
        advanceUntilIdle()

        viewModel.onAction(ReplayAction.SelectPlayerTraceSeat(99))
        advanceUntilIdle()

        assertEquals(
            "越界座位必须回落到默认（观察者），不能把非法值传进列表",
            AvalonScenario.MERLIN,
            viewModel.uiState.value.playerTraceSeat
        )
    }

    @Test
    fun questAndCategoryFiltersNarrowTheTrace() = runTest {
        val viewModel = newViewModel(repository())
        advanceUntilIdle()
        viewModel.onAction(ReplayAction.SelectPlayerTraceSeat(AvalonScenario.LOYAL_2))
        advanceUntilIdle()

        val unfiltered = viewModel.uiState.value.playerTraceItems.size
        assertTrue("先把夹具钉住：2 号应有多条记录", unfiltered >= 3)

        viewModel.onAction(ReplayAction.SetPlayerTraceQuestFilter(1))
        advanceUntilIdle()
        val quest1 = viewModel.uiState.value
        assertTrue("任务 1 的记录不应为空", quest1.playerTraceItems.isNotEmpty())
        assertTrue("只剩任务 1", quest1.playerTraceItems.all { it.questNo == 1 })
        assertTrue("条数应变少", quest1.playerTraceItems.size < unfiltered)

        viewModel.onAction(ReplayAction.SetPlayerTraceQuestFilter(null))
        viewModel.onAction(ReplayAction.SetPlayerTraceCategoryFilter(PlayerTraceCategory.MISSION_MEMBER))
        advanceUntilIdle()
        val missions = viewModel.uiState.value
        assertTrue("只剩任务队员记录", missions.playerTraceItems.all { it.category == PlayerTraceCategory.MISSION_MEMBER })
        assertTrue("2 号上过车，不应为空", missions.playerTraceItems.isNotEmpty())

        // 与任务筛选叠加时取交集（不是并集）
        viewModel.onAction(ReplayAction.SetPlayerTraceQuestFilter(1))
        advanceUntilIdle()
        assertTrue(
            "叠加筛选应取交集",
            viewModel.uiState.value.playerTraceItems.all {
                it.category == PlayerTraceCategory.MISSION_MEMBER && it.questNo == 1
            }
        )
    }

    @Test
    fun emptyQuestFilterYieldsEmptyTraceInsteadOfCrash() = runTest {
        val viewModel = newViewModel(repository())
        advanceUntilIdle()
        viewModel.onAction(ReplayAction.SelectPlayerTraceSeat(AvalonScenario.LOYAL_2))
        viewModel.onAction(ReplayAction.SetPlayerTraceQuestFilter(99))
        advanceUntilIdle()

        assertTrue("不存在的任务筛选应给空列表", viewModel.uiState.value.playerTraceItems.isEmpty())
        assertEquals(99, viewModel.uiState.value.playerTraceQuestFilter)
    }

    @Test
    fun perspectiveSwitchChangesTheTrace() = runTest {
        val viewModel = newViewModel(repository())
        advanceUntilIdle()

        // 默认 PUBLIC：1 号只看到公开记录，看不到自己那条私有笔记
        assertEquals(Perspective.PUBLIC, viewModel.uiState.value.mode)
        assertTrue(
            "公开视角不得出现私人笔记",
            viewModel.uiState.value.playerTraceItems.none {
                it.category == PlayerTraceCategory.PRIVATE_NOTE
            }
        )

        viewModel.onAction(ReplayAction.SelectPerspective(Perspective.OBSERVER_AT_TIME))
        advanceUntilIdle()
        val observer = viewModel.uiState.value
        assertEquals(Perspective.OBSERVER_AT_TIME, observer.mode)
        assertEquals(
            "观察者本人应看到自己的私人判断",
            1,
            observer.playerTraceItems.count { it.category == PlayerTraceCategory.PRIVATE_NOTE }
        )

        viewModel.onAction(ReplayAction.SelectPerspective(Perspective.PUBLIC))
        advanceUntilIdle()
        assertTrue(
            "切回公开视角后私人笔记必须消失",
            viewModel.uiState.value.playerTraceItems.none { it.category == PlayerTraceCategory.PRIVATE_NOTE }
        )
    }

    @Test
    fun selectingPositionNarrowsTheTraceThroughTheExistingAction() = runTest {
        val viewModel = newViewModel(repository())
        advanceUntilIdle()
        viewModel.onAction(ReplayAction.SelectPlayerTraceSeat(AvalonScenario.LOYAL_2))
        advanceUntilIdle()
        val latestSize = viewModel.uiState.value.playerTraceItems.size
        assertTrue(latestSize > 0)

        viewModel.onAction(ReplayAction.SelectPosition(1))
        advanceUntilIdle()

        val early = viewModel.uiState.value
        assertEquals("SelectPosition 应更新位置", 1, early.at.occurredOrder)
        assertTrue(
            "列表必须随位置收敛：${early.playerTraceItems.map { it.occurredOrder }}",
            early.playerTraceItems.all { it.occurredOrder <= 1 }
        )
        assertTrue("早期位置的记录应更少", early.playerTraceItems.size < latestSize)
        assertNotEquals("位置变化不应改变座位选择", null, early.playerTraceSeat)
        assertFalse("位置变化不应让视图进入错误态", early.errorMessage != null)
    }
}
