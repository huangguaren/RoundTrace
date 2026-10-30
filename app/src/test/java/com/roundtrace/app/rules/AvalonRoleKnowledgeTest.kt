package com.roundtrace.app.rules

import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.rules.avalon.AvalonRoleKnowledge
import com.roundtrace.app.util.RoleFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 夜间知识规则（TASKS.md T19）的自动化验证。
 *
 * 三条官方规则必须被钉住：
 * 1. 梅林**看不见莫德雷德**，也**看不见奥伯伦**；
 * 2. 派西维尔：存在莫甘娜时看到两名候选（梅林 + 莫甘娜）且无法区分真假；不存在时只看到梅林；
 * 3. 坏人互认**排除奥伯伦**与自身；奥伯伦自己不参与互认。
 *
 * 说明：K01–K10 的视角引擎测试属 M4（T53/T54），这里只覆盖**规则层**的夜间知识，
 * 因为 10 人局的奥伯伦是主理人 2026-10-30 裁决新增的场景（见 docs/decisions.md B17）。
 */
class AvalonRoleKnowledgeTest {

    // ————————————————————————————————————————————————————————
    // 梅林
    // ————————————————————————————————————————————————————————

    @Test
    fun `T19 梅林看见三号与五号坏人但看不见四号莫德雷德`() {
        val assignments = RoleFixtures.EIGHT_PLAYER_MERLIN_MORDRED
        val knowledge = AvalonRoleKnowledge.knowledgeFor(
            seat = RoleFixtures.MERLIN_SEAT,
            roleId = RoleId.MERLIN,
            assignments = assignments
        )

        assertEquals(
            "梅林应看见 3 号（莫甘娜）与 5 号（刺客）",
            listOf(3, 5),
            knowledge.visibleEvilSeats
        )
        assertFalse(
            "梅林绝不能看见 4 号莫德雷德",
            knowledge.visibleEvilSeats.contains(RoleFixtures.MORDRED_SEAT)
        )
        assertTrue(
            "应附带规则键说明为何排除莫德雷德",
            knowledge.notes.contains(AvalonRoleKnowledge.NOTE_MERLIN_EXCLUDES_MORDRED_AND_OBERON)
        )
    }

    @Test
    fun `T19 十人局梅林也看不见奥伯伦`() {
        val assignments = RoleFixtures.TEN_PLAYER_OBERON
        val knowledge = AvalonRoleKnowledge.knowledgeFor(
            seat = 1,
            roleId = RoleId.MERLIN,
            assignments = assignments
        )

        // 10 人局坏人 = 7 刺客 / 8 莫甘娜 / 9 莫德雷德 / 10 奥伯伦
        assertEquals(
            "梅林在 10 人局只应看见刺客与莫甘娜",
            listOf(7, 8),
            knowledge.visibleEvilSeats
        )
        assertFalse("梅林不得看见莫德雷德", knowledge.visibleEvilSeats.contains(9))
        assertFalse("梅林不得看见奥伯伦", knowledge.visibleEvilSeats.contains(10))
    }

    // ————————————————————————————————————————————————————————
    // 派西维尔
    // ————————————————————————————————————————————————————————

    @Test
    fun `T19 派西维尔在有莫甘娜时看到两名候选且无法区分`() {
        val knowledge = AvalonRoleKnowledge.knowledgeFor(
            seat = RoleFixtures.PERCIVAL_SEAT,
            roleId = RoleId.PERCIVAL,
            assignments = RoleFixtures.EIGHT_PLAYER_MERLIN_MORDRED
        )

        assertEquals(
            "派西维尔候选应为 [梅林 1, 莫甘娜 3]",
            listOf(1, 3),
            knowledge.merlinCandidates
        )
        assertTrue(
            "应附带规则键说明存在两名候选",
            knowledge.notes.contains(AvalonRoleKnowledge.NOTE_PERCIVAL_TWO_CANDIDATES)
        )
        assertTrue("派西维尔不应获得坏人名单", knowledge.visibleEvilSeats.isEmpty())
    }

    @Test
    fun `T19 六人局没有莫甘娜时派西维尔只看到真梅林`() {
        // 官方 6 人局没有派西维尔；这里用「自定义局」验证规则分支本身
        val assignments = mapOf(
            1 to RoleId.MERLIN,
            2 to RoleId.PERCIVAL,
            3 to RoleId.LOYAL_SERVANT,
            4 to RoleId.ASSASSIN,
            5 to RoleId.MINION,
            6 to RoleId.LOYAL_SERVANT
        )
        val knowledge = AvalonRoleKnowledge.knowledgeFor(
            seat = 2,
            roleId = RoleId.PERCIVAL,
            assignments = assignments
        )

        assertEquals(
            "没有莫甘娜时候选只有真梅林",
            listOf(1),
            knowledge.merlinCandidates
        )
        assertTrue(
            "应附带单候选规则键",
            knowledge.notes.contains(AvalonRoleKnowledge.NOTE_PERCIVAL_SINGLE_CANDIDATE)
        )
    }

    // ————————————————————————————————————————————————————————
    // 坏人互认（含奥伯伦例外）
    // ————————————————————————————————————————————————————————

    @Test
    fun `T19 坏人互认包含同伴但排除奥伯伦与自身`() {
        val assignments = RoleFixtures.TEN_PLAYER_OBERON
        val knowledge = AvalonRoleKnowledge.knowledgeFor(
            seat = 7,
            roleId = RoleId.ASSASSIN,
            assignments = assignments
        )

        assertEquals(
            "刺客应认识 8 号莫甘娜与 9 号莫德雷德，但不认识 10 号奥伯伦，也不含自己",
            listOf(8, 9),
            knowledge.evilTeammates
        )
        assertFalse("互认不得包含奥伯伦", knowledge.evilTeammates.contains(10))
        assertFalse("互认不得包含自己", knowledge.evilTeammates.contains(7))
    }

    @Test
    fun `T19 奥伯伦不参与坏人互认`() {
        val knowledge = AvalonRoleKnowledge.knowledgeFor(
            seat = 10,
            roleId = RoleId.OBERON,
            assignments = RoleFixtures.TEN_PLAYER_OBERON
        )

        assertTrue("奥伯伦不应认识任何坏人同伴", knowledge.evilTeammates.isEmpty())
        assertTrue("奥伯伦也不应获得坏人名单", knowledge.visibleEvilSeats.isEmpty())
        assertTrue(
            "应附带奥伯伦独立规则键",
            knowledge.notes.contains(AvalonRoleKnowledge.NOTE_OBERON_IS_SOLO)
        )
    }

    @Test
    fun `T19 莫德雷德仍可参与坏人互认`() {
        // 莫德雷德只是不被梅林看见，互认不受影响
        val knowledge = AvalonRoleKnowledge.knowledgeFor(
            seat = 9,
            roleId = RoleId.MORDRED,
            assignments = RoleFixtures.TEN_PLAYER_OBERON
        )
        assertEquals(
            "莫德雷德应认识刺客与莫甘娜（不含奥伯伦）",
            listOf(7, 8),
            knowledge.evilTeammates
        )
    }

    // ————————————————————————————————————————————————————————
    // 忠臣 / 未知
    // ————————————————————————————————————————————————————————

    @Test
    fun `T19 忠臣没有额外夜间知识`() {
        val knowledge = AvalonRoleKnowledge.knowledgeFor(
            seat = 2,
            roleId = RoleId.LOYAL_SERVANT,
            assignments = RoleFixtures.EIGHT_PLAYER_MERLIN_MORDRED
        )
        assertTrue("忠臣不应有任何夜间知识", knowledge.isEmpty)
        assertTrue(
            "应附带无额外知识的规则键",
            knowledge.notes.contains(AvalonRoleKnowledge.NOTE_NO_EXTRA_KNOWLEDGE)
        )
    }

    @Test
    fun `T19 角色未知时返回空知识且不抛异常`() {
        val knowledge = AvalonRoleKnowledge.knowledgeFor(
            seat = 8,
            roleId = RoleId.UNKNOWN,
            assignments = RoleFixtures.EIGHT_PLAYER_MERLIN_MORDRED
        )
        assertEquals("角色未知时应回退为 UNKNOWN", RoleId.UNKNOWN, knowledge.role)
        assertTrue("角色未知时不应有任何知识", knowledge.isEmpty)
        assertTrue(
            "应附带角色未知的规则键",
            knowledge.notes.contains(AvalonRoleKnowledge.NOTE_ROLE_UNKNOWN)
        )
    }

    @Test
    fun `T19 知识表按座位升序且跳过未知角色`() {
        val assignments = RoleFixtures.EIGHT_PLAYER_MERLIN_MORDRED.toMutableMap()
        assignments[6] = RoleId.UNKNOWN

        val table = AvalonRoleKnowledge.knowledgeTable(assignments)
        assertEquals("未知角色的座位不应进入知识表", 7, table.size)
        assertEquals(
            "知识表必须按座位升序",
            table.map { it.observerSeat }.sorted(),
            table.map { it.observerSeat }
        )
        assertFalse(
            "未知角色的座位不得出现在知识表",
            table.any { it.observerSeat == 6 }
        )
    }

    @Test
    fun `T19 八人局官方构成下梅林与派西维尔的结论与夹具期望一致`() {
        val assignments = RoleFixtures.EIGHT_PLAYER_MERLIN_MORDRED

        assertEquals(
            "夹具声明的梅林可见坏人应等于规则计算结果",
            RoleFixtures.MERLIN_VISIBLE_EVIL_EIGHT_PLAYER,
            AvalonRoleKnowledge.knowledgeFor(1, RoleId.MERLIN, assignments).visibleEvilSeats
        )
        assertEquals(
            "夹具声明的派西维尔候选应等于规则计算结果",
            RoleFixtures.PERCIVAL_CANDIDATES_EIGHT_PLAYER,
            AvalonRoleKnowledge.knowledgeFor(7, RoleId.PERCIVAL, assignments).merlinCandidates
        )
    }
}
