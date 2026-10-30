package com.roundtrace.app.rules

import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.GameConfig
import com.roundtrace.app.core.model.GameType
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.RoleSlotSpec
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.rules.avalon.AvalonRules
import com.roundtrace.app.util.RoleFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R01：5/6/7/8/9/10 人的阵营人数与任务人数矩阵严格正确（TASKS.md T24）。
 *
 * 期望值来源：`02_技术实施文档_TDD.md` §4.1 的 `STANDARD` 表，逐格抄写（不是从实现里反推）。
 */
class AvalonPresetMatrixTest {

    /** TDD §4.1 的期望矩阵，手写副本——故意与实现分离，避免「用实现验证实现」。 */
    private val expectedMatrix: Map<Int, ExpectedCounts> = mapOf(
        5 to ExpectedCounts(good = 3, evil = 2, teamSizes = listOf(2, 3, 2, 3, 3)),
        6 to ExpectedCounts(good = 4, evil = 2, teamSizes = listOf(2, 3, 4, 3, 4)),
        7 to ExpectedCounts(good = 4, evil = 3, teamSizes = listOf(2, 3, 3, 4, 4)),
        8 to ExpectedCounts(good = 5, evil = 3, teamSizes = listOf(3, 4, 4, 5, 5)),
        9 to ExpectedCounts(good = 6, evil = 3, teamSizes = listOf(3, 4, 4, 5, 5)),
        10 to ExpectedCounts(good = 6, evil = 4, teamSizes = listOf(3, 4, 4, 5, 5))
    )

    private data class ExpectedCounts(
        val good: Int,
        val evil: Int,
        val teamSizes: List<Int>
    )

    @Test
    fun `R01 五种到十人的阵营人数与任务人数矩阵逐格一致`() {
        for ((playerCount, expected) in expectedMatrix) {
            val actual = AvalonPreset.counts(playerCount)
            assertEquals(
                "R01 好人人数不符：playerCount=$playerCount",
                expected.good,
                actual.good
            )
            assertEquals(
                "R01 坏人人数不符：playerCount=$playerCount",
                expected.evil,
                actual.evil
            )
            assertEquals(
                "R01 任务人数矩阵不符：playerCount=$playerCount",
                expected.teamSizes,
                actual.teamSizes
            )
            assertEquals(
                "R01 阵营总数应等于人数：playerCount=$playerCount",
                playerCount,
                actual.good + actual.evil
            )
        }
    }

    @Test
    fun `R01 标准预设在每种人数下总数等于人数且阵营配额正确`() {
        for (playerCount in 5..10) {
            val slots = AvalonPreset.standardPreset(playerCount)
            val total = slots.sumOf { it.count }
            assertEquals(
                "标准预设角色总数必须等于人数：playerCount=$playerCount",
                playerCount,
                total
            )

            val goodPlanned = slots.sumOf { if (it.roleId.alignment == Alignment.GOOD) it.count else 0 }
            val evilPlanned = slots.sumOf { if (it.roleId.alignment == Alignment.EVIL) it.count else 0 }
            val expected = expectedMatrix.getValue(playerCount)
            assertEquals(
                "标准预设好人配额不符：playerCount=$playerCount",
                expected.good,
                goodPlanned
            )
            assertEquals(
                "标准预设坏人配额不符：playerCount=$playerCount",
                expected.evil,
                evilPlanned
            )
            assertTrue(
                "标准预设不得包含 UNKNOWN 角色：playerCount=$playerCount",
                slots.none { it.roleId == RoleId.UNKNOWN }
            )
        }
    }

    // ————————————————————————————————————————————————————————
    // T17：官方角色构成逐人数断言（角色计数 + 阵营计数 + 唯一性）
    // 权威来源：官方规则手册（主理人 2026-10-30 裁决，见 docs/decisions.md B17）
    // 注意：5、6 人局**没有派西维尔**；10 人局第 4 个坏人是**奥伯伦**。
    // ————————————————————————————————————————————————————————

    /** 统计某角色的计划数量。 */
    private fun RoleSlotSpec.countOf(role: RoleId): Int =
        if (this.roleId == role) this.count else 0

    private fun List<RoleSlotSpec>.roleCount(role: RoleId): Int = sumOf { it.countOf(role) }

    private fun List<RoleSlotSpec>.totalCount(): Int = sumOf { it.count }

    private fun List<RoleSlotSpec>.goodCount(): Int =
        sumOf { if (it.roleId.alignment == Alignment.GOOD) it.count else 0 }

    private fun List<RoleSlotSpec>.evilCount(): Int =
        sumOf { if (it.roleId.alignment == Alignment.EVIL) it.count else 0 }

    /**
     * 逐行断言：角色计数 + 阵营计数 + 唯一性 + 与矩阵自洽。
     *
     * @param expected 该人数下的期望角色计数（未列出的角色数量必须为 0）
     */
    private fun assertOfficialPreset(playerCount: Int, expected: Map<RoleId, Int>) {
        val slots = AvalonPreset.standardPreset(playerCount)
        val matrix = AvalonPreset.counts(playerCount)

        // ① 角色计数逐格一致
        for (role in RoleId.entries) {
            if (role == RoleId.UNKNOWN) continue
            assertEquals(
                "T17 ${playerCount} 人局角色数量不符：role=${role.name}",
                expected[role] ?: 0,
                slots.roleCount(role)
            )
        }

        // ② 阵营计数：既等于期望值，也等于 TDD §4.1 矩阵
        val expectedGood = expected.entries
            .filter { it.key.alignment == Alignment.GOOD }
            .sumOf { it.value }
        val expectedEvil = expected.entries
            .filter { it.key.alignment == Alignment.EVIL }
            .sumOf { it.value }
        assertEquals(
            "T17 好人角色数之和应为 $expectedGood（${playerCount} 人局）",
            expectedGood,
            slots.goodCount()
        )
        assertEquals(
            "T17 坏人角色数之和应为 $expectedEvil（${playerCount} 人局）",
            expectedEvil,
            slots.evilCount()
        )
        assertEquals(
            "T17 好人配额必须与矩阵一致（${playerCount} 人局）",
            matrix.good,
            slots.goodCount()
        )
        assertEquals(
            "T17 坏人配额必须与矩阵一致（${playerCount} 人局）",
            matrix.evil,
            slots.evilCount()
        )

        // ③ 总数严格等于人数
        assertEquals(
            "T17 角色总数必须严格等于人数（${playerCount} 人局）",
            playerCount,
            slots.totalCount()
        )
        assertEquals(
            "T17 好人数 + 坏人数必须等于人数（${playerCount} 人局）",
            playerCount,
            slots.goodCount() + slots.evilCount()
        )

        // ④ 唯一性：每个角色至多 1 张；梅林与刺客标准局必须各恰好 1 张
        assertTrue(
            "T17 同一角色不得出现多个槽（${playerCount} 人局）",
            slots.map { it.roleId }.distinct().size == slots.size
        )
        assertEquals("T17 标准局必须恰有 1 名梅林（${playerCount} 人局）", 1, slots.roleCount(RoleId.MERLIN))
        assertEquals("T17 标准局必须恰有 1 名刺客（${playerCount} 人局）", 1, slots.roleCount(RoleId.ASSASSIN))
        for (role in RoleId.AT_MOST_ONE) {
            assertTrue(
                "T17 ${role.name} 至多 1 张（${playerCount} 人局）",
                slots.roleCount(role) <= 1
            )
        }
        assertTrue(
            "T17 不得出现数量为 0 的角色槽（${playerCount} 人局）",
            slots.all { it.count > 0 }
        )
    }

    @Test
    fun `T17 五人局为梅林一忠臣二刺客一爪牙一且无派西维尔`() {
        assertOfficialPreset(
            playerCount = 5,
            expected = mapOf(
                RoleId.MERLIN to 1,
                RoleId.LOYAL_SERVANT to 2,
                RoleId.ASSASSIN to 1,
                RoleId.MINION to 1
            )
        )
        assertEquals(
            "T17 官方规则：5 人局没有派西维尔",
            0,
            AvalonPreset.standardPreset(5).roleCount(RoleId.PERCIVAL)
        )
        assertEquals("T17 5 人局不应有莫甘娜", 0, AvalonPreset.standardPreset(5).roleCount(RoleId.MORGANA))
        assertEquals("T17 5 人局不应有莫德雷德", 0, AvalonPreset.standardPreset(5).roleCount(RoleId.MORDRED))
        assertEquals("T17 5 人局不应有奥伯伦", 0, AvalonPreset.standardPreset(5).roleCount(RoleId.OBERON))
    }

    @Test
    fun `T17 六人局为梅林一忠臣三刺客一爪牙一且无派西维尔`() {
        assertOfficialPreset(
            playerCount = 6,
            expected = mapOf(
                RoleId.MERLIN to 1,
                RoleId.LOYAL_SERVANT to 3,
                RoleId.ASSASSIN to 1,
                RoleId.MINION to 1
            )
        )
        assertEquals(
            "T17 官方规则：6 人局仍然没有派西维尔",
            0,
            AvalonPreset.standardPreset(6).roleCount(RoleId.PERCIVAL)
        )
        assertEquals("T17 6 人局不应有莫甘娜", 0, AvalonPreset.standardPreset(6).roleCount(RoleId.MORGANA))
        assertEquals("T17 6 人局不应有莫德雷德", 0, AvalonPreset.standardPreset(6).roleCount(RoleId.MORDRED))
    }

    @Test
    fun `T17 七人局开始出现派西维尔与莫甘娜`() {
        assertOfficialPreset(
            playerCount = 7,
            expected = mapOf(
                RoleId.MERLIN to 1,
                RoleId.PERCIVAL to 1,
                RoleId.LOYAL_SERVANT to 2,
                RoleId.ASSASSIN to 1,
                RoleId.MORGANA to 1,
                RoleId.MINION to 1
            )
        )
        val slots = AvalonPreset.standardPreset(7)
        assertEquals("T17 7 人局出现 1 名派西维尔", 1, slots.roleCount(RoleId.PERCIVAL))
        assertEquals("T17 7 人局出现 1 名莫甘娜", 1, slots.roleCount(RoleId.MORGANA))
        assertEquals("T17 7 人局还没有莫德雷德", 0, slots.roleCount(RoleId.MORDRED))
    }

    @Test
    fun `T17 八人局与真实案例夹具角色构成完全一致`() {
        assertOfficialPreset(
            playerCount = 8,
            expected = mapOf(
                RoleId.MERLIN to 1,
                RoleId.PERCIVAL to 1,
                RoleId.LOYAL_SERVANT to 3,
                RoleId.ASSASSIN to 1,
                RoleId.MORGANA to 1,
                RoleId.MORDRED to 1
            )
        )
        // 与 TDD §11 夹具（RoleFixtures.EIGHT_PLAYER_MERLIN_MORDRED）逐角色比对
        val fromFixture = RoleFixtures.EIGHT_PLAYER_MERLIN_MORDRED.values
        val slots = AvalonPreset.standardPreset(8)
        for (role in RoleId.entries) {
            if (role == RoleId.UNKNOWN) continue
            assertEquals(
                "T17 8 人局预设必须与 TDD §11 夹具一致：role=${role.name}",
                fromFixture.count { it == role },
                slots.roleCount(role)
            )
        }
        assertEquals("T17 8 人局不应有奥伯伦", 0, slots.roleCount(RoleId.OBERON))
        assertEquals("T17 8 人局不应有爪牙", 0, slots.roleCount(RoleId.MINION))
    }

    @Test
    fun `T17 九人局为梅林一派西维尔一忠臣四刺客一莫甘娜一莫德雷德一`() {
        assertOfficialPreset(
            playerCount = 9,
            expected = mapOf(
                RoleId.MERLIN to 1,
                RoleId.PERCIVAL to 1,
                RoleId.LOYAL_SERVANT to 4,
                RoleId.ASSASSIN to 1,
                RoleId.MORGANA to 1,
                RoleId.MORDRED to 1
            )
        )
        assertEquals("T17 9 人局不应有爪牙", 0, AvalonPreset.standardPreset(9).roleCount(RoleId.MINION))
        assertEquals("T17 9 人局不应有奥伯伦", 0, AvalonPreset.standardPreset(9).roleCount(RoleId.OBERON))
    }

    @Test
    fun `T17 十人局第四个坏人是奥伯伦而不是爪牙`() {
        assertOfficialPreset(
            playerCount = 10,
            expected = mapOf(
                RoleId.MERLIN to 1,
                RoleId.PERCIVAL to 1,
                RoleId.LOYAL_SERVANT to 4,
                RoleId.ASSASSIN to 1,
                RoleId.MORGANA to 1,
                RoleId.MORDRED to 1,
                RoleId.OBERON to 1
            )
        )
        val slots = AvalonPreset.standardPreset(10)
        assertEquals("T17 10 人局第 4 个坏人必须是奥伯伦", 1, slots.roleCount(RoleId.OBERON))
        assertEquals("T17 10 人局不得有爪牙（官方推荐用奥伯伦）", 0, slots.roleCount(RoleId.MINION))
        assertEquals(
            "T17 10 人局坏人阵营总数为 4",
            4,
            slots.evilCount()
        )
    }

    @Test
    fun `T17 预设表六行全部自洽且与人数矩阵双向一致`() {
        assertEquals("预设表必须覆盖 5..10 共 6 行", 6, AvalonPreset.standardPresetTable.size)
        for (playerCount in 5..10) {
            val slots = AvalonPreset.standardPresetTable.getValue(playerCount)
            val matrix = AvalonPreset.counts(playerCount)
            assertEquals(
                "预设表 $playerCount 人总数应为 $playerCount",
                playerCount,
                slots.totalCount()
            )
            assertEquals(
                "预设表 $playerCount 人好人数应与矩阵一致",
                matrix.good,
                slots.goodCount()
            )
            assertEquals(
                "预设表 $playerCount 人坏人数应与矩阵一致",
                matrix.evil,
                slots.evilCount()
            )
        }
    }

    @Test
    fun `T17 角色构成属于预设而非规则5人局也能通过 setup 校验`() {
        // 关键回归：5/6 人局没有派西维尔，validateSetup 不能因此报"角色缺失"
        for (playerCount in 5..10) {
            val issues = AvalonRules().validateSetup(
                GameConfig(
                    gameType = GameType.AVALON,
                    rulesetId = AvalonPreset.RULESET_ID,
                    rulesetVersion = AvalonPreset.RULESET_VERSION,
                    presetId = AvalonPreset.presetId(playerCount),
                    playerCount = playerCount,
                    roleSlots = AvalonPreset.standardPreset(playerCount),
                    mySeat = 1,
                    initialLeaderSeat = 1,
                    title = "测试",
                    createdAtEpochMs = 0L
                )
            )
            assertTrue(
                "T17 标准 $playerCount 人预设必须完全合法，实际=${issues.map { it.code }}",
                issues.none { it.isBlocking }
            )
        }
    }


    @Test
    fun `R01 非法人数抛 IllegalArgumentException`() {
        assertThrows(
            "4 人局应被拒绝",
            IllegalArgumentException::class.java
        ) { AvalonPreset.counts(4) }
        assertThrows(
            "11 人局应被拒绝",
            IllegalArgumentException::class.java
        ) { AvalonPreset.counts(11) }
        assertThrows(
            "标准预设也不接受非法人数",
            IllegalArgumentException::class.java
        ) { AvalonPreset.standardPreset(3) }
    }

    @Test
    fun `R01 预设标识与人数一致`() {
        assertEquals("avalon.standard.8p", AvalonPreset.presetId(8))
        assertTrue(AvalonPreset.isStandardPreset(8, "avalon.standard.8p"))
        assertTrue("自定义预设不应被当作标准局", !AvalonPreset.isStandardPreset(8, "custom"))
    }

    @Test
    fun `R01 角色槽数量为负时不崩溃但仍可被规则校验发现`() {
        // 领域层不对脏数据抛异常，问题统一由 validateSetup 报 ROLE_SLOT_NEGATIVE
        val slot = RoleSlotSpec(RoleId.MERLIN, -1)
        assertEquals("负数量应原样保留，供规则层报告", -1, slot.count)
    }
}
