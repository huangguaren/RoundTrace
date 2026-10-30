package com.roundtrace.app.util

import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.RoleId
import java.io.BufferedReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T23③：真实案例 fixture 必须能被 `kotlinx-serialization` 解析，且字段与 TDD §11 一致。
 *
 * 这里刻意不使用 `@Serializable` DTO：fixture 是**外部输入契约**，
 * 用 `JsonElement` 解析可以精确断言「字段确实存在且类型正确」，也避免领域模型被注解污染。
 */
class AvalonFixtureTest {

    private val fixturePath = "fixtures/avalon_eight_merlin_mordred.json"

    private fun loadFixture(): JsonObject {
        val stream = javaClass.classLoader?.getResourceAsStream(fixturePath)
        assertNotNull("找不到 fixture 资源：$fixturePath", stream)
        val text = stream!!.bufferedReader().use(BufferedReader::readText)
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `T23 fixture 可被解析且基础字段正确`() {
        val root = loadFixture()

        assertEquals("schemaVersion 必须为 1", 1, root.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("app 字段固定", "RoundTrace", root.getValue("app").jsonPrimitive.content)
        assertEquals("gameType 必须为 AVALON", "AVALON", root.getValue("gameType").jsonPrimitive.content)
        assertEquals("人数必须为 8", 8, root.getValue("playerCount").jsonPrimitive.int)

        val ruleset = root.getValue("ruleset").jsonObject
        assertEquals("avalon.standard", ruleset.getValue("id").jsonPrimitive.content)
        assertEquals(1, ruleset.getValue("version").jsonPrimitive.int)
    }

    @Test
    fun `T23 fixture 角色表与 TDD 第十一节逐座位一致`() {
        val roles = loadFixture().getValue("roles").jsonObject

        val expected = mapOf(
            "1" to "MERLIN",
            "2" to "LOYAL_SERVANT",
            "3" to "MORGANA",
            "4" to "MORDRED",
            "5" to "ASSASSIN",
            "6" to "LOYAL_SERVANT",
            "7" to "PERCIVAL",
            "8" to "LOYAL_SERVANT"
        )
        assertEquals("角色表应有 8 个座位", 8, roles.size)
        for ((seat, roleName) in expected) {
            assertEquals(
                "座位 $seat 的角色不符",
                roleName,
                roles.getValue(seat).jsonPrimitive.content
            )
            assertEquals(
                "角色字符串必须能被 RoleId.fromPersisted 解析",
                roleName,
                RoleId.fromPersisted(roleName).name
            )
        }

        // 与测试夹具常量保持一致
        val fromFixtures = RoleFixtures.EIGHT_PLAYER_MERLIN_MORDRED.mapKeys { it.key.toString() }
            .mapValues { it.value.name }
        assertEquals("fixture JSON 与 RoleFixtures 必须一致", fromFixtures, expected)
    }

    @Test
    fun `T23 fixture 五个任务的关键结构正确`() {
        val quests = loadFixture().getValue("quests").jsonArray
        assertEquals("应有 5 个任务", 5, quests.size)

        // 任务 1：队长 2，队伍 2/4/6，成功
        val q1 = quests[0].jsonObject
        assertEquals(1, q1.getValue("questNo").jsonPrimitive.int)
        assertEquals(2, q1.getValue("leaderSeat").jsonPrimitive.int)
        assertEquals(
            listOf(2, 4, 6),
            q1.getValue("teamSeats").jsonArray.map { it.jsonPrimitive.int }
        )
        assertEquals(0, q1.getValue("mission").jsonObject.getValue("failCount").jsonPrimitive.int)

        // 任务 3：投票未记录 → decision 为 UNKNOWN 且票数为 null
        val q3 = quests[2].jsonObject
        val q3Vote = q3.getValue("teamVote").jsonObject
        assertEquals("UNKNOWN", q3Vote.getValue("decision").jsonPrimitive.content)
        assertTrue(
            "任务 3 的赞成票必须保持 null（未记录，不是 0）",
            q3Vote.getValue("approveCount") is JsonNull
        )

        // 任务 4：成员不完整必须保留 null，不得臆填
        val q4Team = quests[3].jsonObject.getValue("teamSeats").jsonArray
        assertEquals("任务 4 队伍应保留 5 个位置", 5, q4Team.size)
        assertTrue("任务 4 第 4 位成员必须为 null", q4Team[3] is JsonNull)
        assertTrue("任务 4 第 5 位成员必须为 null", q4Team[4] is JsonNull)
        assertEquals(
            "任务 4 应有 2 张失败牌",
            2,
            quests[3].jsonObject.getValue("mission").jsonObject.getValue("failCount").jsonPrimitive.int
        )

        // 任务 5：队长未记录、失败牌数未记录
        val q5 = quests[4].jsonObject
        assertTrue("任务 5 队长必须为 null", q5.getValue("leaderSeat") is JsonNull)
        assertTrue(
            "任务 5 失败牌数必须为 null",
            q5.getValue("mission").jsonObject.getValue("failCount") is JsonNull
        )
    }

    @Test
    fun `T23 fixture 的复盘期望与规则一致`() {
        val expectedReview = loadFixture().getValue("expectedReview").jsonObject

        val merlinKnownEvil = expectedReview.getValue("merlinKnownEvil").jsonArray.map { it.jsonPrimitive.int }
        assertEquals("梅林开局应知道 3、5 是坏人", listOf(3, 5), merlinKnownEvil)
        assertEquals(
            "与 RoleFixtures 的梅林可见坏人一致",
            RoleFixtures.MERLIN_VISIBLE_EVIL_EIGHT_PLAYER,
            merlinKnownEvil
        )

        val mustNotKnow = expectedReview.getValue("merlinMustNotKnow").jsonArray.map { it.jsonPrimitive.int }
        assertEquals("梅林绝不能知道 4 号（莫德雷德）", listOf(4), mustNotKnow)
        assertEquals(
            "4 号的阵营确实是 EVIL（只是不可见）",
            Alignment.EVIL,
            RoleId.MORDRED.alignment
        )

        val percival = expectedReview.getValue("percivalCandidates").jsonArray.map { it.jsonPrimitive.int }
        assertEquals("派西维尔候选为 1、3", listOf(1, 3), percival)
        assertEquals(
            "与 RoleFixtures 的派西维尔候选一致",
            RoleFixtures.PERCIVAL_CANDIDATES_EIGHT_PLAYER,
            percival
        )
    }
}
