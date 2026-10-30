package com.roundtrace.app.knowledge

import com.roundtrace.app.core.knowledge.AvalonKnowledgeProvider
import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.BeliefType
import com.roundtrace.app.core.model.GamePosition
import com.roundtrace.app.core.model.KnowledgeBasis
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.PrivateRelation
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.util.MatchBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K09 + 三道闸门（TASKS.md T54②⑤ / T51）。
 *
 * **K09 是全套测试里最容易被写错的一条**：
 * 上帝视角看过结局后，切回第三轮当时视角，后续真相**绝不能泄漏**。
 */
class PerspectiveIsolationTest {

    private val provider = AvalonKnowledgeProvider(AvalonScenario.reducer())

    // ————————————————————————————————————————————————————————
    // K09：上帝视角 → 当时视角，不泄漏
    // ————————————————————————————————————————————————————————

    @Test
    fun k09_god_view_then_back_to_past_does_not_leak_mordred() {
        val revealed = AvalonScenario.match(revealed = true)
        val end = AvalonScenario.end(revealed)

        // ① 上帝视角：能看到 4 号就是莫德雷德
        val godSnapshot = provider.snapshot(
            match = revealed,
            observer = null,
            at = end,
            mode = Perspective.GOD_AFTER_REVEAL
        )
        assertEquals(
            "K09 上帝视角应看到 4 号是莫德雷德",
            RoleId.MORDRED,
            godSnapshot.seatKnowledge.getValue(4).visibleRole
        )
        assertEquals(
            "K09 上帝视角应看到 4 号阵营为坏人",
            Alignment.EVIL,
            godSnapshot.seatKnowledge.getValue(4).knownAlignment
        )
        // 注意：本场景没有走到终局（3 成功 / 3 失败 / 五连否决），
        // 按 ARCHITECTURE §9.3-7 的数据条件 canOpenGodView 应为 false——
        // 「能构建上帝快照（已揭晓）」与「允许从 UI 进入上帝视角（还需终局）」是两件事。
        assertFalse(
            "K09 未进入终局阶段时，上帝视角入口应保持关闭",
            godSnapshot.canOpenGodView
        )

        // ② 切回第 3 个任务的「当时视角」（观察者 1 号梅林）
        val atThirdQuest = AvalonScenario.afterQuest(revealed, 3)
        val pastSnapshot = provider.snapshot(
            match = revealed,
            observer = AvalonScenario.MERLIN,
            at = atThirdQuest,
            mode = Perspective.OBSERVER_AT_TIME
        )

        assertNull(
            "K09 当时视角绝不能因为对局已揭晓就泄漏 4 号阵营",
            pastSnapshot.seatKnowledge.getValue(4).knownAlignment
        )
        assertNull(
            "K09 当时视角绝不能泄漏 4 号真实角色",
            pastSnapshot.seatKnowledge.getValue(4).visibleRole
        )
        assertTrue(
            "K09 4 号在当时视角必须仍是未知",
            pastSnapshot.seatKnowledge.getValue(4).isUnknown
        )

        // ③ 当时视角的私有事实里也不能出现 4 号
        val knownEvil = pastSnapshot.privateRoleFacts
            .filter { it.relation == PrivateRelation.KNOWN_EVIL }
            .map { it.targetSeat }
            .sorted()
        assertEquals("K09 夜间知识仍然只能是 3、5", listOf(3, 5), knownEvil)
        assertFalse("K09 绝不能出现 4 号", knownEvil.contains(4))
    }

    @Test
    fun k09_reconstructed_role_is_flagged_explicitly() {
        val revealed = AvalonScenario.match(revealed = true)
        val atThirdQuest = AvalonScenario.afterQuest(revealed, 3)

        val snapshot = provider.snapshot(
            match = revealed,
            observer = AvalonScenario.MERLIN,
            at = atThirdQuest,
            mode = Perspective.OBSERVER_AT_TIME
        )

        assertTrue(
            "K09 角色是结局后补录的，必须标记 observerRoleIsReconstructed = true",
            snapshot.observerRoleIsReconstructed
        )
        assertTrue(
            "K09 这种情况下事实来源必须是 RECONSTRUCTED_INITIAL",
            snapshot.privateRoleFacts
                .filter { it.relation == PrivateRelation.KNOWN_EVIL }
                .all { it.basis == KnowledgeBasis.RECONSTRUCTED_INITIAL }
        )
    }

    // ————————————————————————————————————————————————————————
    // 闸门 1：时间边界
    // ————————————————————————————————————————————————————————

    @Test
    fun gate1_time_boundary_excludes_later_events() {
        val match = AvalonScenario.match()
        val atQuest1 = AvalonScenario.afterQuest(match, 1)

        val snapshot = provider.snapshot(
            match = match,
            observer = AvalonScenario.MERLIN,
            at = atQuest1,
            mode = Perspective.OBSERVER_AT_TIME
        )

        assertTrue(
            "闸门1 第 1 任务结束后不应出现第 2 任务的事实",
            snapshot.publicFacts.none { it.questNo == 2 }
        )
        assertTrue(
            "闸门1 也不应出现第 2~5 任务的任何事实",
            snapshot.publicFacts.all { (it.questNo ?: 0) <= 1 }
        )
        assertTrue(
            "闸门1 事实的 occurredOrder 不得越过 at",
            snapshot.publicFacts.all { it.occurredOrder <= atQuest1.occurredOrder }
        )
    }

    @Test
    fun gate1_start_position_has_no_facts() {
        val snapshot = provider.snapshot(
            match = AvalonScenario.match(),
            observer = AvalonScenario.MERLIN,
            at = GamePosition.start(),
            mode = Perspective.OBSERVER_AT_TIME
        )
        assertTrue("闸门1 开局位置不应有任何公开事实", snapshot.publicFacts.isEmpty())
        assertTrue("闸门1 开局位置也不应有主观判断", snapshot.subjectiveBeliefs.isEmpty())
        // 但夜间知识仍在（它是开局就有的规则信息）
        assertTrue("闸门1 开局位置必须仍有夜间知识", snapshot.privateRoleFacts.isNotEmpty())
    }

    @Test
    fun gate1_private_note_respects_available_from_order() {
        val builder: MatchBuilder = AvalonScenario.fullMatch()
        // 在第 5 个任务之后补一条「从第 1 个事件起可见」的笔记（回填历史）
        builder.privateNoteAdded(
            observerSeat = AvalonScenario.MERLIN,
            beliefType = BeliefType.SUSPECT,
            targetSeats = listOf(4),
            questNo = 5,
            availableFromOrder = 1,
            text = "补记：4 号可疑"
        )
        val match = builder.build()
        val atQuest3 = AvalonScenario.afterQuest(match, 3)

        val snapshot = provider.snapshot(
            match = match,
            observer = AvalonScenario.MERLIN,
            at = atQuest3,
            mode = Perspective.OBSERVER_AT_TIME
        )

        assertTrue(
            "闸门1 availableFromOrder 早于 at 的笔记应可见（回填语义）",
            snapshot.subjectiveBeliefs.any { it.text == "补记：4 号可疑" }
        )
        assertTrue(
            "闸门1 主观判断仍不得进入公开事实",
            snapshot.publicFacts.none { it.summary.contains("补记") }
        )
    }

    @Test
    fun gate1_private_note_with_later_available_from_is_hidden() {
        val builder: MatchBuilder = AvalonScenario.fullMatch()
        builder.privateNoteAdded(
            observerSeat = AvalonScenario.MERLIN,
            beliefType = BeliefType.SUSPECT,
            targetSeats = listOf(7),
            questNo = 5,
            availableFromOrder = 999,
            text = "太晚才想到"
        )
        val match = builder.build()
        val atQuest3 = AvalonScenario.afterQuest(match, 3)

        val snapshot = provider.snapshot(
            match = match,
            observer = AvalonScenario.MERLIN,
            at = atQuest3,
            mode = Perspective.OBSERVER_AT_TIME
        )

        assertTrue(
            "闸门1 availableFromOrder 晚于 at 的笔记不得显示",
            snapshot.subjectiveBeliefs.none { it.text == "太晚才想到" }
        )
    }

    // ————————————————————————————————————————————————————————
    // 闸门 2：可见性边界
    // ————————————————————————————————————————————————————————

    @Test
    fun gate2_private_note_only_visible_to_its_owner() {
        val builder: MatchBuilder = AvalonScenario.fullMatch()
        builder.privateNoteAdded(
            observerSeat = AvalonScenario.MERLIN,
            beliefType = BeliefType.SUSPECT,
            targetSeats = listOf(7),
            questNo = 5,
            availableFromOrder = 1,
            text = "7 号说法很自信"
        )
        val match = builder.build()
        val end = AvalonScenario.end(match)

        val asMerlin = provider.snapshot(
            match, AvalonScenario.MERLIN, end, Perspective.OBSERVER_AT_TIME
        )
        val asOther = provider.snapshot(
            match, AvalonScenario.LOYAL_2, end, Perspective.OBSERVER_AT_TIME
        )
        val asPublic = provider.snapshot(match, null, end, Perspective.PUBLIC)

        assertTrue(
            "闸门2 笔记对本人可见",
            asMerlin.subjectiveBeliefs.any { it.text == "7 号说法很自信" }
        )
        assertTrue(
            "闸门2 笔记对其他人不可见",
            asOther.subjectiveBeliefs.none { it.text == "7 号说法很自信" }
        )
        assertTrue(
            "闸门2 笔记在公开视角不可见",
            asPublic.subjectiveBeliefs.isEmpty()
        )
    }

    // ————————————————————————————————————————————————————————
    // 上帝视角的开放条件与绕过尝试
    // ————————————————————————————————————————————————————————

    @Test
    fun god_view_throws_when_match_not_revealed() {
        val notRevealed = AvalonScenario.match(revealed = false)
        val error = assertThrows(IllegalStateException::class.java) {
            provider.snapshot(
                match = notRevealed,
                observer = null,
                at = AvalonScenario.end(notRevealed),
                mode = Perspective.GOD_AFTER_REVEAL
            )
        }
        assertNotNull("必须抛出可读异常", error.message)
        assertTrue(
            "异常信息应说明尚未揭晓，实际=${error.message}",
            error.message!!.contains("揭晓")
        )
    }

    @Test
    fun god_view_flag_requires_revealed_at_and_terminal_status() {
        val notRevealed = AvalonScenario.match(revealed = false)
        val publicSnapshot = provider.snapshot(
            match = notRevealed,
            observer = null,
            at = AvalonScenario.end(notRevealed),
            mode = Perspective.PUBLIC
        )
        assertFalse(
            "未揭晓时 canOpenGodView 必须为 false",
            publicSnapshot.canOpenGodView
        )

        val revealed = AvalonScenario.match(revealed = true)
        val revealedSnapshot = provider.snapshot(
            match = revealed,
            observer = AvalonScenario.MERLIN,
            at = AvalonScenario.end(revealed),
            mode = Perspective.OBSERVER_AT_TIME
        )
        // 场景本身没有走到终局（3 成功 / 3 失败 / 五连否决），所以即使揭晓了也不该开放
        assertFalse(
            "已揭晓但对局未进入终局阶段时，上帝视角仍应关闭",
            revealedSnapshot.canOpenGodView
        )
    }

    @Test
    fun god_view_facts_do_not_depend_on_observer() {
        val revealed = AvalonScenario.match(revealed = true)
        val end = AvalonScenario.end(revealed)
        val first = provider.snapshot(revealed, null, end, Perspective.GOD_AFTER_REVEAL)
        val second = provider.snapshot(revealed, AvalonScenario.MERLIN, end, Perspective.GOD_AFTER_REVEAL)

        assertEquals(
            "上帝视角的私有事实应与观察者无关",
            first.privateRoleFacts.size,
            second.privateRoleFacts.size
        )
        assertEquals(
            "上帝视角应看到全部座位的真实阵营",
            first.seatKnowledge.values.count { it.knownAlignment != null },
            second.seatKnowledge.values.count { it.knownAlignment != null }
        )
    }
}
