package com.roundtrace.app.rules

import com.roundtrace.app.core.model.GameConfig
import com.roundtrace.app.core.model.GameType
import com.roundtrace.app.core.model.IssueCode
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.RoleSlotSpec
import com.roundtrace.app.core.model.RuleIssue
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.rules.avalon.AvalonRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R13 的「配置（setup）部分」（TASKS.md T25）。
 *
 * 红线：配置不合法时**草稿可保存**（无异常、有可读 issue），
 * 但「确认创建」必须被 BLOCKING 拦住。
 */
class AvalonSetupValidationTest {

    private val rules = AvalonRules()

    private fun config(
        playerCount: Int,
        roleSlots: List<RoleSlotSpec>,
        leaderSeat: Int = 1,
        mySeat: Int? = 1,
        presetId: String = "custom"
    ): GameConfig = GameConfig(
        gameType = GameType.AVALON,
        rulesetId = AvalonPreset.RULESET_ID,
        rulesetVersion = AvalonPreset.RULESET_VERSION,
        presetId = presetId,
        playerCount = playerCount,
        roleSlots = roleSlots,
        mySeat = mySeat,
        initialLeaderSeat = leaderSeat,
        title = "测试对局",
        createdAtEpochMs = 0L
    )

    private fun standardConfig(playerCount: Int = 8): GameConfig = config(
        playerCount = playerCount,
        roleSlots = AvalonPreset.standardPreset(playerCount),
        presetId = AvalonPreset.presetId(playerCount)
    )

    private fun List<RuleIssue>.codes(): List<IssueCode> = map { it.code }

    // ————————————————————————————————————————————————————————
    // 合法配置
    // ————————————————————————————————————————————————————————

    @Test
    fun `R13 标准八人配置无任何问题`() {
        val issues = rules.validateSetup(standardConfig(8))
        assertTrue(
            "标准 8 人配置不应产生任何 issue，实际=${issues.codes()}",
            issues.isEmpty()
        )
    }

    @Test
    fun `R01 五到十人的标准配置全部合法`() {
        for (playerCount in 5..10) {
            val issues = rules.validateSetup(standardConfig(playerCount))
            assertTrue(
                "标准 $playerCount 人配置应合法，实际=${issues.codes()}",
                issues.none { it.isBlocking }
            )
        }
    }

    // ————————————————————————————————————————————————————————
    // 七个配置类 IssueCode 全覆盖
    // ————————————————————————————————————————————————————————

    @Test
    fun `R13 人数超出范围返回 BLOCKING`() {
        val issues = rules.validateSetup(
            config(playerCount = 11, roleSlots = listOf(RoleSlotSpec(RoleId.MERLIN, 11)))
        )
        assertTrue(
            "人数 11 应报 PLAYER_COUNT_OUT_OF_RANGE，实际=${issues.codes()}",
            issues.any { it.code == IssueCode.PLAYER_COUNT_OUT_OF_RANGE && it.isBlocking }
        )
    }

    @Test
    fun `R13 角色槽数量为负返回 BLOCKING`() {
        val issues = rules.validateSetup(
            config(
                playerCount = 8,
                roleSlots = listOf(
                    RoleSlotSpec(RoleId.MERLIN, 1),
                    RoleSlotSpec(RoleId.LOYAL_SERVANT, -1)
                )
            )
        )
        assertTrue(
            "负数量应报 ROLE_SLOT_NEGATIVE，实际=${issues.codes()}",
            issues.any { it.code == IssueCode.ROLE_SLOT_NEGATIVE && it.isBlocking }
        )
    }

    @Test
    fun `R13 角色总数与人数不符返回 BLOCKING`() {
        val slots = listOf(
            RoleSlotSpec(RoleId.MERLIN, 1),
            RoleSlotSpec(RoleId.LOYAL_SERVANT, 2),
            RoleSlotSpec(RoleId.ASSASSIN, 1)
        )
        val issues = rules.validateSetup(config(playerCount = 8, roleSlots = slots))
        assertTrue(
            "总数 4 != 8 应报 ROLE_COUNT_MISMATCH，实际=${issues.codes()}",
            issues.any { it.code == IssueCode.ROLE_COUNT_MISMATCH && it.isBlocking }
        )
    }

    @Test
    fun `R13 阵营配额不符时给出 WARNING 但不阻断`() {
        // 8 人但阵营为 6 好人 / 2 坏人（标准应为 5/3）
        val slots = listOf(
            RoleSlotSpec(RoleId.MERLIN, 1),
            RoleSlotSpec(RoleId.PERCIVAL, 1),
            RoleSlotSpec(RoleId.LOYAL_SERVANT, 4),
            RoleSlotSpec(RoleId.ASSASSIN, 1),
            RoleSlotSpec(RoleId.MINION, 1)
        )
        val issues = rules.validateSetup(config(playerCount = 8, roleSlots = slots))
        assertTrue(
            "阵营不符应报 ALIGNMENT_COUNT_MISMATCH，实际=${issues.codes()}",
            issues.any { it.code == IssueCode.ALIGNMENT_COUNT_MISMATCH }
        )
        assertFalse(
            "阵营不符只警告，不应阻断（ARCHITECTURE 9_3_3）",
            issues.any { it.code == IssueCode.ALIGNMENT_COUNT_MISMATCH && it.isBlocking }
        )
    }

    @Test
    fun `R13 重复的特殊角色返回 BLOCKING`() {
        val slots = listOf(
            RoleSlotSpec(RoleId.MERLIN, 2),
            RoleSlotSpec(RoleId.PERCIVAL, 1),
            RoleSlotSpec(RoleId.LOYAL_SERVANT, 3),
            RoleSlotSpec(RoleId.ASSASSIN, 1),
            RoleSlotSpec(RoleId.MORGANA, 1)
        )
        val issues = rules.validateSetup(config(playerCount = 8, roleSlots = slots))
        assertTrue(
            "两张梅林应报 ROLE_UNIQUENESS_VIOLATED，实际=${issues.codes()}",
            issues.any { it.code == IssueCode.ROLE_UNIQUENESS_VIOLATED && it.isBlocking }
        )
    }

    @Test
    fun `R13 标准局缺少刺客返回 BLOCKING`() {
        // 使用标准 presetId 但去掉刺客 → 必须被拦住
        val slots = listOf(
            RoleSlotSpec(RoleId.MERLIN, 1),
            RoleSlotSpec(RoleId.PERCIVAL, 1),
            RoleSlotSpec(RoleId.LOYAL_SERVANT, 3),
            RoleSlotSpec(RoleId.MORGANA, 1),
            RoleSlotSpec(RoleId.MORDRED, 1),
            RoleSlotSpec(RoleId.MINION, 1)
        )
        val issues = rules.validateSetup(
            config(playerCount = 8, roleSlots = slots, presetId = AvalonPreset.presetId(8))
        )
        assertTrue(
            "标准局必须恰有 1 名刺客，实际=${issues.codes()}",
            issues.any { it.code == IssueCode.ROLE_UNIQUENESS_VIOLATED && it.isBlocking }
        )
    }

    @Test
    fun `R13 首任队长座位越界返回 BLOCKING`() {
        val issues = rules.validateSetup(
            config(
                playerCount = 8,
                roleSlots = AvalonPreset.standardPreset(8),
                leaderSeat = 0
            )
        )
        assertTrue(
            "队长座位 0 应报 LEADER_SEAT_INVALID，实际=${issues.codes()}",
            issues.any { it.code == IssueCode.LEADER_SEAT_INVALID && it.isBlocking }
        )
    }

    @Test
    fun `R13 我的座位越界返回 BLOCKING`() {
        val issues = rules.validateSetup(
            config(
                playerCount = 8,
                roleSlots = AvalonPreset.standardPreset(8),
                mySeat = 9
            )
        )
        assertTrue(
            "我的座位 9 应报 MY_SEAT_INVALID，实际=${issues.codes()}",
            issues.any { it.code == IssueCode.MY_SEAT_INVALID && it.isBlocking }
        )
    }

    @Test
    fun `R13 我的座位可以为空`() {
        val issues = rules.validateSetup(
            config(
                playerCount = 8,
                roleSlots = AvalonPreset.standardPreset(8),
                mySeat = null
            )
        )
        assertTrue(
            "mySeat 为 null 是合法状态（旁观记录），实际=${issues.codes()}",
            issues.none { it.code == IssueCode.MY_SEAT_INVALID }
        )
    }

    @Test
    fun `R13 自定义变体允许没有刺客`() {
        // 非标准 presetId → 允许无刺客变体（对应 NO_ASSASSIN_PRESENT 出口）
        val slots = listOf(
            RoleSlotSpec(RoleId.MERLIN, 1),
            RoleSlotSpec(RoleId.PERCIVAL, 1),
            RoleSlotSpec(RoleId.LOYAL_SERVANT, 3),
            RoleSlotSpec(RoleId.MORGANA, 1),
            RoleSlotSpec(RoleId.MORDRED, 1),
            RoleSlotSpec(RoleId.MINION, 1)
        )
        val issues = rules.validateSetup(
            config(playerCount = 8, roleSlots = slots, presetId = "custom")
        )
        assertFalse(
            "自定义变体不应因缺少刺客而被阻断，实际=${issues.codes()}",
            issues.any { it.isBlocking }
        )
    }
}
