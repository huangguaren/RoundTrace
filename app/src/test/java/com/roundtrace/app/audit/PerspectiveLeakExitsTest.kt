package com.roundtrace.app.audit

import com.roundtrace.app.core.export.DefaultImportExportService
import com.roundtrace.app.core.export.ExportOptions
import com.roundtrace.app.core.export.ExportResult
import com.roundtrace.app.core.knowledge.AvalonEvidenceAnalyzer
import com.roundtrace.app.core.knowledge.AvalonKnowledgeProvider
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.GamePosition
import com.roundtrace.app.core.model.KnowledgeLabel
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.model.ReconstructedMatch
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.replay.TimelineBuilder
import com.roundtrace.app.core.replay.VisibleEventPolicy
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.avalon.AvalonRules
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * QA-P1 · RT-005 对抗性回归：`PRIVATE_OBSERVER` 事件（私人声称 / 私人笔记）
 * 不得从任何一个出口漏到公开视角。
 *
 * 五个出口（团队约定的 E2E-07）：
 * 1. `KnowledgeSnapshot.publicFacts`
 * 2. `SeatKnowledge.labels`（`claimLabels`）
 * 3. `EvidenceFinding`（`AvalonEvidenceAnalyzer`）
 * 4. Markdown / JSON 默认导出（`VisibilityFilter`）
 * 5. 复盘时间线（`ReplayViewModel` → `VisibleEventPolicy`）
 *
 * 每个出口都配一组**对照组**（公开声称必须出现），否则「全空」也能让断言通过——
 * 那是假绿。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PerspectiveLeakExitsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val registry = DefaultGameRulesRegistry()
    private val provider = AvalonKnowledgeProvider(registry.avalonReducer)
    private val analyzer = AvalonEvidenceAnalyzer(AvalonRules())

    private companion object {
        /** 4 号（莫德雷德）私下记的「我要对外跳梅林」，只该他自己看得见。 */
        const val PRIVATE_CLAIM_ID = "audit-private-claim-4"
        const val PRIVATE_CLAIM_TEXT = "私下记：我准备对外跳梅林"
        const val PUBLIC_CLAIM_ID = "audit-public-claim-2"
        const val PUBLIC_CLAIM_TEXT = "公开：我认为 3 号有问题"
        const val BASE_ORDER = 900
        const val AT_ORDER = 1000
        const val OBSERVER_SEAT = 4
    }

    private val at = GamePosition(AT_ORDER)

    // ————————————————————————————————————————————————————————
    // 出口 1–3：快照事实 / 声称标签 / 分析结论
    // ————————————————————————————————————————————————————————

    @Test
    fun e2e07_exit1to3_public_perspective_never_sees_private_claim() {
        val match = matchWithPrivateClaim(revealed = false)
        val snapshot = provider.snapshot(match, observer = null, at = at, mode = Perspective.PUBLIC)
        val findings = analyzer.analyze(snapshot, historyAt(match))

        // 对照组：公开声称必须在，证明不是「全空所以绿」
        assertTrue(
            "对照组：公开声称（$PUBLIC_CLAIM_ID）必须进入 publicFacts",
            snapshot.publicFacts.any { it.id == PUBLIC_CLAIM_ID }
        )

        assertFalse(
            "出口1：私有声称不得进入 publicFacts",
            snapshot.publicFacts.any { it.id == PRIVATE_CLAIM_ID }
        )
        assertFalse(
            "出口1：私有声称的文本不得出现在任何公开摘要里",
            snapshot.publicFacts.any { it.summary.contains(PRIVATE_CLAIM_TEXT) }
        )

        val seat4 = snapshot.seatKnowledge.getValue(OBSERVER_SEAT)
        assertFalse(
            "出口2：4 号的 CLAIMED_MERLIN 标签只能来自他自己的私有声称，公开视角必须没有",
            seat4.labels.contains(KnowledgeLabel.CLAIMED_MERLIN)
        )
        assertFalse(
            "出口3：分析结论不得引用私有声称事件",
            findings.any { f -> f.sourceEventIds.any { it.raw == PRIVATE_CLAIM_ID } }
        )
        assertFalse(
            "出口3：分析结论不得对 4 号生成「跳梅林」类结论",
            findings.any { f -> f.ruleId.startsWith("A08") && f.relatedSeats.contains(OBSERVER_SEAT) }
        )

        // 对照组：公开声称确实产生了 A08
        assertTrue(
            "对照组：公开声称必须产生 A08 结论",
            findings.any { f -> f.ruleId.startsWith("A08") }
        )
    }

    @Test
    fun e2e07_exit3_observer_perspective_does_see_own_private_claim() {
        val match = matchWithPrivateClaim(revealed = false)
        val snapshot = provider.snapshot(
            match = match,
            observer = OBSERVER_SEAT,
            at = at,
            mode = Perspective.OBSERVER_AT_TIME
        )
        val findings = analyzer.analyze(snapshot, historyAt(match))

        // 这条是「工程师的说法」：观察者可见性由 A08 引用证明
        assertTrue(
            "观察者本人必须能通过分析结论看到自己的私有声称（A08 引用）",
            findings.any { f -> f.sourceEventIds.any { it.raw == PRIVATE_CLAIM_ID } }
        )

        // 下面两条是本次审计要核实的**快照层**可见性，如实打印，是否成立见报告
        println(
            "[AUDIT] 观察者(4) 视角：publicFacts 含私有声称=" +
                snapshot.publicFacts.any { it.id == PRIVATE_CLAIM_ID } +
                "，seat4.labels=" + snapshot.seatKnowledge.getValue(OBSERVER_SEAT).labels
        )
        assertFalse(
            "私有声称仍然不是公开事实（观察者视角也不能把它升格）",
            snapshot.publicFacts.any { it.id == PRIVATE_CLAIM_ID }
        )
    }

    @Test
    fun e2e07_god_perspective_sees_everything() {
        val match = matchWithPrivateClaim(revealed = true)
        val snapshot = provider.snapshot(
            match = match,
            observer = null,
            at = at,
            mode = Perspective.GOD_AFTER_REVEAL
        )
        val findings = analyzer.analyze(snapshot, historyAt(match))

        assertTrue(
            "上帝视角必须看到全部私有事实（含私有声称）",
            findings.any { f -> f.sourceEventIds.any { it.raw == PRIVATE_CLAIM_ID } }
        )
        assertEquals(
            "上帝视角必须看到 4 号是莫德雷德",
            RoleId.MORDRED,
            snapshot.seatKnowledge.getValue(OBSERVER_SEAT).visibleRole
        )
    }

    // ————————————————————————————————————————————————————————
    // 出口 4：默认导出（JSON / Markdown）
    // ————————————————————————————————————————————————————————

    @Test
    fun e2e07_exit4_default_export_drops_private_claim() = runTest {
        val repository = fakeRepository()
        val service = DefaultImportExportService(
            repository = repository,
            rules = AvalonRules()
        )

        val json = service.exportJson(MatchId("fake-match"), ExportOptions.DEFAULT)
        assertTrue("JSON 导出应成功", json is ExportResult.Success)
        val jsonText = (json as ExportResult.Success).text
        assertFalse("出口4-JSON：默认导出不得包含私有声称", jsonText.contains(PRIVATE_CLAIM_ID))
        assertFalse("出口4-JSON：默认导出不得包含私有声称正文", jsonText.contains(PRIVATE_CLAIM_TEXT))
        assertTrue(
            "对照组：公开声称必须保留在默认导出里",
            jsonText.contains(PUBLIC_CLAIM_ID)
        )

        val markdown = service.exportMarkdown(MatchId("fake-match"), ExportOptions.DEFAULT)
        assertTrue("Markdown 导出应成功", markdown is ExportResult.Success)
        val markdownText = (markdown as ExportResult.Success).text
        assertFalse("出口4-Markdown：默认导出不得包含私有声称正文", markdownText.contains(PRIVATE_CLAIM_TEXT))
        assertTrue(
            "对照组：Markdown 必须写出公开声称正文",
            markdownText.contains(PUBLIC_CLAIM_TEXT)
        )

        // 显式勾选后才允许包含
        val withPrivate = service.exportJson(
            MatchId("fake-match"),
            ExportOptions(includePrivateNotes = true, includeSecrets = true)
        )
        assertTrue(
            "勾选「包含私人笔记」后必须导出私有声称（否则脱敏开关是坏的）",
            (withPrivate as ExportResult.Success).text.contains(PRIVATE_CLAIM_ID)
        )
    }

    // ————————————————————————————————————————————————————————
    // 出口 5：复盘时间线
    // ————————————————————————————————————————————————————————

    @Test
    fun e2e07_exit5_replay_timeline_hides_private_claim_in_public_mode() = runTest {
        val repository = fakeRepository()
        val viewModel = ReplayViewModel(
            matchId = MatchId("fake-match"),
            repository = repository,
            knowledgeProvider = provider,
            analyzer = analyzer
        )
        advanceUntilIdle()

        val publicState = viewModel.uiState.value
        assertEquals(Perspective.PUBLIC, publicState.mode)
        assertFalse(
            "出口5：公开视角的时间线不得出现私有声称",
            publicState.timeline.any { entry -> entry.sourceEventIds.any { it.raw == PRIVATE_CLAIM_ID } }
        )
        assertTrue(
            "对照组：公开视角的时间线必须出现公开声称",
            publicState.timeline.any { entry -> entry.sourceEventIds.any { it.raw == PUBLIC_CLAIM_ID } }
        )

        // 切到「我的当时视角」（mySeat = 4），私有声称属于自己 → 必须出现
        viewModel.onAction(ReplayAction.SelectPerspective(Perspective.OBSERVER_AT_TIME))
        advanceUntilIdle()
        val observerState = viewModel.uiState.value
        assertEquals(Perspective.OBSERVER_AT_TIME, observerState.mode)
        assertTrue(
            "出口5：观察者本人的时间线必须能看到自己记的私有声称",
            observerState.timeline.any { entry -> entry.sourceEventIds.any { it.raw == PRIVATE_CLAIM_ID } }
        )
    }

    // ————————————————————————————————————————————————————————
    // B31 回归：PrivateNoteAdded.availableFromOrder 的时间特例
    // ————————————————————————————————————————————————————————

    @Test
    fun b31_private_note_respects_available_from_order() {
        val builder = AvalonScenario.fullMatch()
        builder.appendRaw(
            com.roundtrace.app.core.model.PrivateNoteAdded(
                eventId = EventId("audit-note-early"),
                occurredOrder = BASE_ORDER,
                recordedAt = 0L,
                visibility = EventVisibility.PRIVATE_OBSERVER,
                questNo = 3,
                observerSeat = AvalonScenario.MERLIN,
                beliefType = com.roundtrace.app.core.model.BeliefType.SUSPECT,
                targetSeats = listOf(OBSERVER_SEAT),
                text = "回填：4 号可疑",
                availableFromOrder = 1
            )
        )
        builder.appendRaw(
            com.roundtrace.app.core.model.PrivateNoteAdded(
                eventId = EventId("audit-note-late"),
                occurredOrder = BASE_ORDER + 1,
                recordedAt = 0L,
                visibility = EventVisibility.PRIVATE_OBSERVER,
                questNo = 3,
                observerSeat = AvalonScenario.MERLIN,
                beliefType = com.roundtrace.app.core.model.BeliefType.SUSPECT,
                targetSeats = listOf(6),
                text = "太晚才想到",
                availableFromOrder = 5_000
            )
        )
        val match = builder.build()
        val early = provider.snapshot(
            match = match,
            observer = AvalonScenario.MERLIN,
            at = GamePosition(1),
            mode = Perspective.OBSERVER_AT_TIME
        )
        val late = provider.snapshot(
            match = match,
            observer = AvalonScenario.MERLIN,
            at = GamePosition(10_000),
            mode = Perspective.OBSERVER_AT_TIME
        )

        assertTrue(
            "B31：availableFromOrder 早于 at 的笔记必须可见（回填语义）",
            early.subjectiveBeliefs.any { it.text == "回填：4 号可疑" }
        )
        assertFalse(
            "B31：availableFromOrder 晚于 at 的笔记不得可见",
            early.subjectiveBeliefs.any { it.text == "太晚才想到" }
        )
        assertTrue(
            "B31：把 at 推到很后面后，晚到的笔记才出现",
            late.subjectiveBeliefs.any { it.text == "太晚才想到" }
        )
        // 主观判断永不进入公开事实
        assertFalse(
            "B31：主观笔记不得进入 publicFacts",
            early.publicFacts.any { it.summary.contains("可疑") }
        )
    }

    // ————————————————————————————————————————————————————————
    // 工具
    // ————————————————————————————————————————————————————————

    private fun privateClaim(): PlayerClaimed = PlayerClaimed(
        eventId = EventId(PRIVATE_CLAIM_ID),
        occurredOrder = BASE_ORDER,
        recordedAt = 0L,
        visibility = EventVisibility.PRIVATE_OBSERVER,
        questNo = 3,
        speakerSeat = OBSERVER_SEAT,
        claimType = ClaimType.ROLE_CLAIM,
        claimedRole = RoleId.MERLIN,
        targetSeats = emptyList(),
        text = PRIVATE_CLAIM_TEXT,
        proposalNo = null
    )

    private fun publicClaim(): PlayerClaimed = PlayerClaimed(
        eventId = EventId(PUBLIC_CLAIM_ID),
        occurredOrder = BASE_ORDER + 1,
        recordedAt = 0L,
        visibility = EventVisibility.PUBLIC,
        questNo = 3,
        speakerSeat = AvalonScenario.LOYAL_2,
        claimType = ClaimType.SUSPECT,
        claimedRole = null,
        targetSeats = listOf(AvalonScenario.MORGANA),
        text = PUBLIC_CLAIM_TEXT,
        proposalNo = null
    )

    private fun matchWithPrivateClaim(revealed: Boolean): ReconstructedMatch {
        val builder = AvalonScenario.fullMatch()
        builder.appendRaw(privateClaim())
        builder.appendRaw(publicClaim())
        return builder.build(
            revealedAtEpochMs = if (revealed) 1_700_000_100_000L else null
        )
    }

    private fun historyAt(match: ReconstructedMatch) =
        match.events.filter { !it.retracted && at.includes(it.occurredOrder) }

    /** 观察者固定为 4 号的 fake 仓储（复盘时间线出口需要 ViewModel 走真实派生链）。 */
    private fun fakeRepository(): FakeMatchRepository {
        // 必须复用同一份「含私有声称」的事件集，否则对照组（公开声称）根本不存在，
        // 断言会因为数据缺失而假绿/假红。
        val match = matchWithPrivateClaim(revealed = false)
        return FakeMatchRepository(
            config = AvalonScenario.eightPlayerConfig().copy(mySeat = OBSERVER_SEAT),
            initialEvents = match.events,
            revealedAt = null,
            initialRoles = match.roleAssignments
        )
    }

    @Test
    fun visible_event_policy_matrix_is_as_documented() {
        val event = privateClaim()
        assertFalse(
            "PUBLIC 看不到私有事件",
            VisibleEventPolicy.isVisibleTo(event, Perspective.PUBLIC, OBSERVER_SEAT)
        )
        assertTrue(
            "观察者本人的 OBSERVER_AT_TIME 能看到",
            VisibleEventPolicy.isVisibleTo(event, Perspective.OBSERVER_AT_TIME, OBSERVER_SEAT)
        )
        assertFalse(
            "其他观察者看不到",
            VisibleEventPolicy.isVisibleTo(event, Perspective.OBSERVER_AT_TIME, 1)
        )
        assertTrue(
            "上帝视角能看到",
            VisibleEventPolicy.isVisibleTo(event, Perspective.GOD_AFTER_REVEAL, null)
        )
        assertNotNull(
            "私有事件必须能归属到具体座位，否则安全侧默认不可见",
            VisibleEventPolicy.ownerSeatOf(event)
        )
        // 无归属的私有事件：只有上帝视角可见（安全侧默认）。
        // 注意：`PlayerClaimed` / `PrivateNoteAdded` 都能归属到座位，所以「无主」这一支
        // 只能用其它事件类型 + PRIVATE_OBSERVER 组合来验证（防止未来新增事件类型漏过滤）。
        val orphan = com.roundtrace.app.core.model.TeamProposed(
            eventId = EventId("audit-orphan-private"),
            occurredOrder = BASE_ORDER + 2,
            recordedAt = 0L,
            visibility = EventVisibility.PRIVATE_OBSERVER,
            questNo = 1,
            proposalNo = 1,
            leaderSeat = 1,
            teamSeats = listOf(1, 2, 3)
        )
        assertNull(
            "无法归属座位的私有事件必须返回 null 归属",
            VisibleEventPolicy.ownerSeatOf(orphan)
        )
        for (seat in 1..8) {
            assertFalse(
                "无主私有事件对观察者 $seat 必须不可见",
                VisibleEventPolicy.isVisibleTo(orphan, Perspective.OBSERVER_AT_TIME, seat)
            )
        }
        assertTrue(
            "无主私有事件只对上帝视角可见",
            VisibleEventPolicy.isVisibleTo(orphan, Perspective.GOD_AFTER_REVEAL, null)
        )
        // TimelineBuilder 对同一批事件的输出条数应与策略过滤一致
        val events = listOf(event)
        assertEquals(
            0,
            TimelineBuilder.build(
                VisibleEventPolicy.forPerspective(events, Perspective.PUBLIC, null)
            ).size
        )
    }
}
