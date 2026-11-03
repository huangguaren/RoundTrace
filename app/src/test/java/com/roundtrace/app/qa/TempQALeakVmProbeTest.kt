package com.roundtrace.app.qa

import com.roundtrace.app.core.knowledge.AvalonEvidenceAnalyzer
import com.roundtrace.app.core.knowledge.AvalonKnowledgeProvider
import com.roundtrace.app.core.model.BeliefType
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.model.PrivateNoteAdded
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.replay.PlayerTraceCategory
import com.roundtrace.app.feature.replay.ReplayAction
import com.roundtrace.app.feature.replay.ReplayViewModel
import com.roundtrace.app.util.FakeMatchRepository
import com.roundtrace.app.util.MainDispatcherRule
import com.roundtrace.app.util.MatchBuilder
import com.roundtrace.app.util.RoleFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * QA 临时探针（team-lead 追加项，ViewModel 端到端）：
 * A. 私有声称在 PUBLIC 下不漏、在 OBSERVER 下「被提及」条目的内容边界；
 * B. B31 回填笔记点击后是否把复盘位置拨到未来。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TempQALeakVmProbeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val provider = AvalonKnowledgeProvider(
        com.roundtrace.app.core.rules.DefaultGameRulesRegistry().avalonReducer
    )
    private val analyzer = AvalonEvidenceAnalyzer()

    private fun repository(): FakeMatchRepository {
        val b = MatchBuilder.of(
            playerCount = 8,
            rolesBySeat = RoleFixtures.EIGHT_PLAYER_MERLIN_MORDRED,
            mySeat = 1
        ).apply {
            questStarted(1, 2)
            teamProposed(1, 1, 2, listOf(2, 3, 5)) // 5 号有一条公开的「被提名」记录
        }
        b.appendRaw(
            PlayerClaimed(
                eventId = EventId("qa-private-claim"),
                occurredOrder = 4,
                recordedAt = 4_000L,
                visibility = EventVisibility.PRIVATE_OBSERVER,
                questNo = 1,
                speakerSeat = 1,
                claimType = ClaimType.ROLE_CLAIM,
                claimedRole = RoleId.MERLIN,
                targetSeats = listOf(5),
                text = "私密：5 号看着像坏人"
            )
        )
        b.appendRaw(
            PrivateNoteAdded(
                eventId = EventId("qa-note-900"),
                occurredOrder = 900,
                recordedAt = 900_000L,
                visibility = EventVisibility.PRIVATE_OBSERVER,
                questNo = null,
                observerSeat = 1,
                beliefType = BeliefType.SUSPECT,
                targetSeats = listOf(5),
                text = "私密笔记QA",
                availableFromOrder = 3
            )
        )
        val built = b.build()
        return FakeMatchRepository(
            config = built.config,
            initialEvents = b.events(),
            revealedAt = null,
            initialRoles = built.roleAssignments
        )
    }

    private fun newViewModel() = ReplayViewModel(
        matchId = MatchId("qa-match"),
        repository = repository(),
        knowledgeProvider = provider,
        analyzer = analyzer
    )

    @Test
    fun publicPerspectiveShowsNoPrivateClaimItem() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.onAction(ReplayAction.SelectPlayerTraceSeat(5))
        vm.onAction(ReplayAction.SelectPosition(5))
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals("默认 PUBLIC", Perspective.PUBLIC, s.mode)
        assertEquals(
            "PUBLIC 下 5 号不得有 SPEAKER/MENTIONED：${s.playerTraceItems.map { it.title }}",
            0,
            s.playerTraceItems.count {
                it.category == PlayerTraceCategory.SPEAKER || it.category == PlayerTraceCategory.MENTIONED
            }
        )
        assertTrue(
            "PUBLIC 下 5 号仍应有其公开记录（证明不是空列表假通过）",
            s.playerTraceItems.any { it.category == PlayerTraceCategory.NOMINATED }
        )
        s.playerTraceItems.forEach {
            assertFalse("PUBLIC 泄漏私有正文：${it.title}|${it.detail}", it.detail.contains("私密"))
            assertFalse("PUBLIC 泄漏声称角色：${it.title}|${it.detail}", it.detail.contains("梅林"))
        }
        println("[E] PUBLIC 下 5 号条目 = " + s.playerTraceItems.map { "${it.occurredOrder}#${it.category.name}" })
    }

    /**
     * 【断言已按 P1 修复改写】
     * 旧期望：观察者本人（= 声称者 1 号）可在 5 号轨迹里看到自己私录声称指向的「被提及」1 条。
     * 新期望（更严格）：私有声称只在发言者本人的轨迹里出现；5 号轨迹里 0 条「被提及」，
     * 但 5 号仍保留它的公开记录（证明不是靠空列表假通过）。
     */
    @Test
    fun observerPerspectiveMentionedItemStaysMinimal() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.onAction(ReplayAction.SelectPerspective(Perspective.OBSERVER_AT_TIME))
        vm.onAction(ReplayAction.SelectPlayerTraceSeat(5))
        vm.onAction(ReplayAction.SelectPosition(5))
        advanceUntilIdle()

        val seat5 = vm.uiState.value.playerTraceItems
        assertEquals(
            "P1 修复后：私有声称不得进 5 号的轨迹（宁可少显示）",
            0,
            seat5.count { it.category == PlayerTraceCategory.MENTIONED }
        )
        assertTrue(
            "5 号仍应有其公开记录（证明不是空列表假通过）：${seat5.map { it.category }}",
            seat5.any { it.category == PlayerTraceCategory.NOMINATED }
        )
        seat5.forEach {
            assertFalse("不得带出声称角色：${it.title}|${it.detail}", it.detail.contains("梅林"))
            assertFalse("不得带出私有正文：${it.title}|${it.detail}", it.detail.contains("私密"))
        }

        // 切到 1 号本人：私有声称出现，且带私有来源标记
        vm.onAction(ReplayAction.SelectPlayerTraceSeat(1))
        advanceUntilIdle()
        val own = vm.uiState.value.playerTraceItems
            .filter { it.category == PlayerTraceCategory.SPEAKER }
        assertEquals("1 号本人应看到自己那条私有声称", 1, own.size)
        assertEquals(4, own.single().occurredOrder)
        assertEquals(EventVisibility.PRIVATE_OBSERVER, own.single().sourceVisibility)
        println("[E] OBSERVER 下 1 号自己的私有声称条目 = title[${own.single().title}] " +
            "detail[${own.single().detail}] sourceVisibility=${own.single().sourceVisibility}")
    }

    @Test
    fun backfilledNoteClickMovesBackwardsNotToTheFuture() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.onAction(ReplayAction.SelectPerspective(Perspective.OBSERVER_AT_TIME))
        vm.onAction(ReplayAction.SelectPlayerTraceSeat(1))
        vm.onAction(ReplayAction.SelectPosition(5))
        advanceUntilIdle()

        val before = vm.uiState.value
        assertEquals("at=5", 5, before.at.occurredOrder)
        val note = before.playerTraceItems.single { it.category == PlayerTraceCategory.PRIVATE_NOTE }
        assertEquals("笔记定位点 = availableFromOrder", 3, note.occurredOrder)
        assertTrue("定位点不得晚于当前 at", note.occurredOrder <= before.at.occurredOrder)
        assertTrue(
            "at=5 时不得出现晚于 5 的定位点：${before.playerTraceItems.map { it.occurredOrder }}",
            before.playerTraceItems.all { it.occurredOrder <= 5 }
        )

        vm.onAction(ReplayAction.SelectPosition(note.occurredOrder))
        advanceUntilIdle()
        val after = vm.uiState.value
        assertEquals("点击回填笔记后 at 落到 availableFromOrder=3（往回拨，不是到 900）", 3, after.at.occurredOrder)
        assertTrue("跳转后位置不得为笔记的 occurredOrder=900", after.at.occurredOrder != 900)
        assertTrue(
            "跳转后列表仍不得越界：${after.playerTraceItems.map { it.occurredOrder }}",
            after.playerTraceItems.all { it.occurredOrder <= 3 }
        )
        println("[E] 点击回填笔记: at 5 -> ${after.at.occurredOrder}；条目定位点=${after.playerTraceItems.map { it.occurredOrder }}")
    }
}
