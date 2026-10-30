package com.roundtrace.app.knowledge

import com.roundtrace.app.core.knowledge.AvalonKnowledgeProvider
import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.GamePosition
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.PrivateRelation
import com.roundtrace.app.core.model.RoleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 坏人互认规则（TASKS.md T54④）：含同伴但**排除奥伯伦与自身**。
 *
 * 注意反对称陷阱：莫德雷德只是不被梅林看见，**仍然参与坏人互认**（B17 已钉住）。
 */
class EvilTeamKnowledgeTest {

    private val provider = AvalonKnowledgeProvider(AvalonScenario.reducer())

    @Test
    fun assassin_knows_morgana_and_mordred_but_not_himself() {
        val match = AvalonScenario.match()
        val snapshot = provider.snapshot(
            match = match,
            observer = AvalonScenario.ASSASSIN,
            at = GamePosition.start(),
            mode = Perspective.OBSERVER_AT_TIME
        )

        val teammates = snapshot.privateRoleFacts
            .filter { it.relation == PrivateRelation.EVIL_TEAMMATE }
            .map { it.targetSeat }
            .sorted()

        assertEquals(
            "刺客应认识 3 号莫甘娜与 4 号莫德雷德",
            listOf(AvalonScenario.MORGANA, AvalonScenario.MORDRED),
            teammates
        )
        assertFalse("互认不得包含自己", teammates.contains(AvalonScenario.ASSASSIN))

        assertEquals(
            "自己知道自己是坏人",
            Alignment.EVIL,
            snapshot.seatKnowledge.getValue(AvalonScenario.ASSASSIN).knownAlignment
        )
        assertEquals(
            "莫甘娜对刺客而言是确定的坏人",
            Alignment.EVIL,
            snapshot.seatKnowledge.getValue(AvalonScenario.MORGANA).knownAlignment
        )
        assertEquals(
            "莫德雷德对刺客而言同样是确定的坏人（莫德雷德只是对梅林隐形）",
            Alignment.EVIL,
            snapshot.seatKnowledge.getValue(AvalonScenario.MORDRED).knownAlignment
        )
    }

    @Test
    fun evil_sees_good_seats_as_unknown_not_good() {
        val match = AvalonScenario.match()
        val snapshot = provider.snapshot(
            match = match,
            observer = AvalonScenario.MORGANA,
            at = GamePosition.start(),
            mode = Perspective.OBSERVER_AT_TIME
        )

        // 梅林是好人，但莫甘娜只知道「不是同伴」，不能确定其阵营
        val merlinSeat = snapshot.seatKnowledge.getValue(AvalonScenario.MERLIN)
        assertEquals(
            "坏人不能把梅林标成确定好人（只能知道不是同伴）",
            null,
            merlinSeat.knownAlignment
        )
        assertTrue(
            "梅林在坏人视角下应为未知",
            merlinSeat.isUnknown
        )
    }

    @Test
    fun ten_player_oberon_is_excluded_from_mutual_recognition() {
        val assignments = com.roundtrace.app.util.RoleFixtures.TEN_PLAYER_OBERON
        val knowledge = com.roundtrace.app.core.rules.avalon.AvalonRoleKnowledge.knowledgeFor(
            seat = 7,
            roleId = RoleId.ASSASSIN,
            assignments = assignments
        )

        assertEquals(
            "10 人局：刺客应认识 8 号莫甘娜与 9 号莫德雷德，但不认识 10 号奥伯伦",
            listOf(8, 9),
            knowledge.evilTeammates
        )
        assertFalse("互认不得包含奥伯伦", knowledge.evilTeammates.contains(10))
    }

    @Test
    fun oberon_has_no_teammates_and_is_invisible_to_merlin() {
        val assignments = com.roundtrace.app.util.RoleFixtures.TEN_PLAYER_OBERON

        val oberon = com.roundtrace.app.core.rules.avalon.AvalonRoleKnowledge.knowledgeFor(
            seat = 10,
            roleId = RoleId.OBERON,
            assignments = assignments
        )
        assertTrue("奥伯伦不应认识任何同伴", oberon.evilTeammates.isEmpty())
        assertTrue("奥伯伦也没有坏人名单", oberon.visibleEvilSeats.isEmpty())

        val merlin = com.roundtrace.app.core.rules.avalon.AvalonRoleKnowledge.knowledgeFor(
            seat = 1,
            roleId = RoleId.MERLIN,
            assignments = assignments
        )
        assertFalse("梅林看不见奥伯伦", merlin.visibleEvilSeats.contains(10))
        assertFalse("梅林也看不见莫德雷德", merlin.visibleEvilSeats.contains(9))
        assertEquals(
            "梅林只看见刺客与莫甘娜",
            listOf(7, 8),
            merlin.visibleEvilSeats
        )
    }
}
