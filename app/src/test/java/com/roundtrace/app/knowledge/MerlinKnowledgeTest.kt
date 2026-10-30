package com.roundtrace.app.knowledge

import com.roundtrace.app.core.knowledge.AvalonKnowledgeProvider
import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.GamePosition
import com.roundtrace.app.core.model.KnowledgeBasis
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.PrivateRelation
import com.roundtrace.app.core.model.RoleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K01：8 人局 1 号（梅林）观察开局 —— 知道 3/5 是坏人，**不知道 4 号是坏人**。
 *
 * 这是整个产品最基本的一条反剧透规则：莫德雷德对梅林隐形。
 */
class MerlinKnowledgeTest {

    private val provider = AvalonKnowledgeProvider(AvalonScenario.reducer())
    private val match = AvalonScenario.match()

    @Test
    fun k01_merlin_sees_3_and_5_but_not_4() {
        val snapshot = provider.snapshot(
            match = match,
            observer = AvalonScenario.MERLIN,
            at = GamePosition.start(),
            mode = Perspective.OBSERVER_AT_TIME
        )

        val knowledge = snapshot.seatKnowledge
        assertEquals(
            "K01 梅林必须确定 3 号（莫甘娜）是坏人",
            Alignment.EVIL,
            knowledge.getValue(3).knownAlignment
        )
        assertEquals(
            "K01 梅林必须确定 5 号（刺客）是坏人",
            Alignment.EVIL,
            knowledge.getValue(5).knownAlignment
        )
        assertNull(
            "K01 梅林绝不能知道 4 号（莫德雷德）是坏人",
            knowledge.getValue(4).knownAlignment
        )
        assertNull(
            "K01 也不能通过 visibleRole 泄漏 4 号身份",
            knowledge.getValue(4).visibleRole
        )
        assertTrue(
            "K01 4 号在梅林视角必须是完全未知",
            knowledge.getValue(4).isUnknown
        )
    }

    @Test
    fun k01_merlin_private_facts_only_know_evil_3_and_5() {
        val snapshot = provider.snapshot(
            match = match,
            observer = AvalonScenario.MERLIN,
            at = GamePosition.start(),
            mode = Perspective.OBSERVER_AT_TIME
        )

        val knownEvil = snapshot.privateRoleFacts
            .filter { it.relation == PrivateRelation.KNOWN_EVIL }
            .map { it.targetSeat }
            .sorted()
        assertEquals("K01 私有事实里的坏人只能是 3、5", listOf(3, 5), knownEvil)
        assertFalse("K01 绝不能包含 4 号", knownEvil.contains(4))

        assertTrue(
            "K01 所有私有事实都属于观察者本人（subjectSeat == observer）",
            snapshot.privateRoleFacts.all { it.subjectSeat == AvalonScenario.MERLIN }
        )
    }

    @Test
    fun k01_merlin_knows_own_role_and_basis_is_ruleset_initial() {
        val snapshot = provider.snapshot(
            match = match,
            observer = AvalonScenario.MERLIN,
            at = GamePosition.start(),
            mode = Perspective.OBSERVER_AT_TIME
        )

        assertEquals("K01 观察者角色应为梅林", RoleId.MERLIN, snapshot.observerRole)
        assertFalse(
            "K01 未揭晓时知识来自开局规则，不是事后重建",
            snapshot.observerRoleIsReconstructed
        )
        val nightFacts = snapshot.privateRoleFacts.filter {
            it.relation == PrivateRelation.KNOWN_EVIL
        }
        assertTrue(
            "K01 夜间知识的来源必须是 RULESET_INITIAL",
            nightFacts.all { it.basis == KnowledgeBasis.RULESET_INITIAL }
        )
        assertTrue(
            "K01 自己知道自己，标签应含 SELF",
            snapshot.seatKnowledge.getValue(AvalonScenario.MERLIN)
                .labels.contains(com.roundtrace.app.core.model.KnowledgeLabel.SELF)
        )
    }

    @Test
    fun k01_public_perspective_leaks_nothing() {
        val snapshot = provider.snapshot(
            match = match,
            observer = null,
            at = AvalonScenario.end(match),
            mode = Perspective.PUBLIC
        )

        assertNull("K01 公开视角没有观察者", snapshot.observerSeat)
        assertTrue("K01 公开视角不得有私有事实", snapshot.privateRoleFacts.isEmpty())
        assertTrue("K01 公开视角不得有主观判断", snapshot.subjectiveBeliefs.isEmpty())
        assertTrue(
            "K01 公开视角下所有座位的 knownAlignment 必须为 null",
            snapshot.seatKnowledge.values.all { it.knownAlignment == null }
        )
        assertTrue(
            "K01 公开视角下不得出现 visibleRole",
            snapshot.seatKnowledge.values.all { it.visibleRole == null }
        )
        assertFalse("K01 公开视角不能开上帝视角", snapshot.canOpenGodView)
    }
}
