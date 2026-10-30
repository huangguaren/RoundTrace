package com.roundtrace.app.knowledge

import com.roundtrace.app.core.knowledge.AvalonKnowledgeProvider
import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.GamePosition
import com.roundtrace.app.core.model.KnowledgeLabel
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.util.MatchBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K03：2 号（普通忠臣）观察开局 —— 不直接得到任何坏人身份。
 * K10：缺失某玩家角色 —— 退化为未知，显示「信息不足」。
 */
class LoyalServantKnowledgeTest {

    private val provider = AvalonKnowledgeProvider(AvalonScenario.reducer())

    @Test
    fun k03_loyal_servant_knows_only_self() {
        val match = AvalonScenario.match()
        val snapshot = provider.snapshot(
            match = match,
            observer = AvalonScenario.LOYAL_2,
            at = GamePosition.start(),
            mode = Perspective.OBSERVER_AT_TIME
        )

        assertTrue(
            "K03 忠臣不应有任何私有关系事实（除了自己）",
            snapshot.privateRoleFacts.none {
                it.relation != com.roundtrace.app.core.model.PrivateRelation.KNOWN_GOOD_SELF
            }
        )
        assertEquals(
            "K03 自己知道自己是好人",
            Alignment.GOOD,
            snapshot.seatKnowledge.getValue(AvalonScenario.LOYAL_2).knownAlignment
        )
        assertEquals(
            "K03 自己应为 SELF 标签",
            setOf(KnowledgeLabel.SELF),
            snapshot.seatKnowledge.getValue(AvalonScenario.LOYAL_2).labels
        )

        listOf(3, 4, 5).forEach { seat ->
            assertNull(
                "K03 忠臣不得知道 $seat 号的阵营",
                snapshot.seatKnowledge.getValue(seat).knownAlignment
            )
            assertNull(
                "K03 忠臣不得知道 $seat 号的角色",
                snapshot.seatKnowledge.getValue(seat).visibleRole
            )
            assertTrue(
                "K03 $seat 号对忠臣必须完全未知",
                snapshot.seatKnowledge.getValue(seat).isUnknown
            )
        }
    }

    @Test
    fun k10_unknown_observer_role_degrades_whole_perspective() {
        // 8 号角色未填写，并让 8 号作为观察者
        val builder: MatchBuilder = AvalonScenario.fullMatch()
        builder.withUnknownRole(AvalonScenario.LOYAL_8)
        val match = builder.build()

        val snapshot = provider.snapshot(
            match = match,
            observer = AvalonScenario.LOYAL_8,
            at = GamePosition.start(),
            mode = Perspective.OBSERVER_AT_TIME
        )

        assertEquals(
            "K10 观察者角色应为 UNKNOWN",
            RoleId.UNKNOWN,
            snapshot.observerRole
        )
        assertTrue(
            "K10 角色未知时不得产出任何私有事实",
            snapshot.privateRoleFacts.isEmpty()
        )
        assertTrue(
            "K10 必须产出「观察者角色未知」的 UnknownItem",
            snapshot.unknowns.any {
                it.code == AvalonKnowledgeProvider.UNKNOWN_OBSERVER_ROLE
            }
        )
        assertTrue(
            "K10 该视角下所有座位都应退化为未知",
            snapshot.seatKnowledge.values.all { it.isUnknown }
        )
        assertFalse("K10 有未知项时 hasUnknowns 应为 true", !snapshot.hasUnknowns)
    }

    @Test
    fun k10_other_seat_missing_role_becomes_unknown_without_breaking_others() {
        // 让 5 号（刺客）角色缺失；梅林的夜间知识里就会少掉 5 号
        val builder: MatchBuilder = AvalonScenario.fullMatch()
        builder.withUnknownRole(AvalonScenario.ASSASSIN)
        val match = builder.build()

        val snapshot = provider.snapshot(
            match = match,
            observer = AvalonScenario.MERLIN,
            at = GamePosition.start(),
            mode = Perspective.OBSERVER_AT_TIME
        )

        assertTrue(
            "K10 角色缺失的座位必须退化为未知",
            snapshot.seatKnowledge.getValue(AvalonScenario.ASSASSIN).isUnknown
        )
        assertEquals(
            "K10 但已知的坏人 3 号仍应确定",
            Alignment.EVIL,
            snapshot.seatKnowledge.getValue(AvalonScenario.MORGANA).knownAlignment
        )
        assertTrue(
            "K10 必须给出「多数座位未知」的提示",
            snapshot.unknowns.any { it.code == AvalonKnowledgeProvider.UNKNOWN_MOST_SEATS }
        )
    }

    @Test
    fun k10_incomplete_mission_data_produces_unknown_items() {
        val match = AvalonScenario.match()
        val snapshot = provider.snapshot(
            match = match,
            observer = AvalonScenario.MERLIN,
            at = AvalonScenario.end(match),
            mode = Perspective.OBSERVER_AT_TIME
        )

        assertTrue(
            "K10 第 5 任务失败牌未记录，必须给出 UNKNOWN_FAIL_COUNT",
            snapshot.unknowns.any {
                it.code == AvalonKnowledgeProvider.UNKNOWN_FAIL_COUNT && it.questNo == 5
            }
        )
        assertTrue(
            "K10 第 4 任务队员不完整，必须给出 UNKNOWN_MISSION_TEAM",
            snapshot.unknowns.any {
                it.code == AvalonKnowledgeProvider.UNKNOWN_MISSION_TEAM && it.questNo == 4
            }
        )
    }
}
