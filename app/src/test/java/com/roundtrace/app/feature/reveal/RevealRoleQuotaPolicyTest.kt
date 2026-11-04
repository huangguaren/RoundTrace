package com.roundtrace.app.feature.reveal

import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.RoleSlotSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结局揭晓的角色配额纯函数（REVEAL-Q-01 / Phase D）。
 *
 * 覆盖 PRD ID-12～ID-17：
 * - 配额**只来自本局 config.roleSlots**，按 roleId **合计**多个 RoleSlotSpec.count；
 * - 8 人局忠臣 3 可分配给三个不同座位，梅林 1 只允许一个；
 * - 改选释放旧名额，当前座位自己占的额度不能把自己锁住；
 * - UNKNOWN 多名不占额、不自动补齐；
 * - 计划与真实身份不一致时给出明确 warning，**不自动修改计划或真相**。
 *
 * 纯 Kotlin：不依赖 Android / Room / Compose。
 */
class RevealRoleQuotaPolicyTest {

    /** 8 人标准局：梅林1 派西维尔1 忠臣3 刺客1 莫甘娜1 莫德雷德1。 */
    private fun eightPlayerSlots() = listOf(
        RoleSlotSpec(RoleId.MERLIN, 1),
        RoleSlotSpec(RoleId.PERCIVAL, 1),
        RoleSlotSpec(RoleId.LOYAL_SERVANT, 3),
        RoleSlotSpec(RoleId.ASSASSIN, 1),
        RoleSlotSpec(RoleId.MORGANA, 1),
        RoleSlotSpec(RoleId.MORDRED, 1)
    )

    private fun rows(vararg pairs: Pair<Int, RoleId>) =
        pairs.map { RevealSeatRow(it.first, it.second) }

    // ————————————————————————— ID-16 配额来源 —————————————————————————

    @Test
    fun quotaComesFromThisMatchRoleSlotsNotFromStandardPreset() {
        // 刻意做一份「非标准」配置：忠臣只有 1，且完全没有梅林
        val custom = listOf(
            RoleSlotSpec(RoleId.PERCIVAL, 1),
            RoleSlotSpec(RoleId.LOYAL_SERVANT, 1),
            RoleSlotSpec(RoleId.ASSASSIN, 1)
        )
        val policy = RevealRoleQuotaPolicy(custom)
        assertEquals(1, policy.plannedQuota(RoleId.LOYAL_SERVANT))
        assertEquals(0, policy.plannedQuota(RoleId.MERLIN))
        assertEquals(1, policy.plannedQuota(RoleId.ASSASSIN))
        // 不能按 8 人标准表重建出「忠臣 3 / 梅林 1」
        assertFalse(policy.plannedQuota(RoleId.LOYAL_SERVANT) == 3)
    }

    @Test
    fun repeatedRoleSlotsAreSummedNotOverwritten() {
        // 同一 roleId 被拆成多行：`associate { roleId to count }` 会覆盖成 1，必须合计为 5
        val split = listOf(
            RoleSlotSpec(RoleId.LOYAL_SERVANT, 2),
            RoleSlotSpec(RoleId.MERLIN, 1),
            RoleSlotSpec(RoleId.LOYAL_SERVANT, 3)
        )
        assertEquals(5, RevealRoleQuotaPolicy(split).plannedQuota(RoleId.LOYAL_SERVANT))
        assertEquals(1, RevealRoleQuotaPolicy(split).plannedQuota(RoleId.MERLIN))
    }

    @Test
    fun negativeOrEmptySlotsAreClampedToZero() {
        val policy = RevealRoleQuotaPolicy(
            listOf(RoleSlotSpec(RoleId.MERLIN, -2), RoleSlotSpec(RoleId.MINION, 0))
        )
        assertEquals(0, policy.plannedQuota(RoleId.MERLIN))
        assertEquals(0, policy.plannedQuota(RoleId.MINION))
        assertEquals(0, RevealRoleQuotaPolicy(emptyList()).plannedQuota(RoleId.MERLIN))
    }

    // ————————————————————————— ID-12 8 人局忠臣 3 —————————————————————————

    @Test
    fun threeLoyalServantsCanBeAssignedToThreeDifferentSeats() {
        val policy = RevealRoleQuotaPolicy(eightPlayerSlots())

        var current = rows(1 to RoleId.UNKNOWN, 2 to RoleId.UNKNOWN, 3 to RoleId.UNKNOWN, 4 to RoleId.UNKNOWN)
        for (seat in 1..3) {
            val decision = policy.evaluate(
                rows = current,
                editingSeat = seat,
                candidate = RoleId.LOYAL_SERVANT
            )
            assertTrue("座位 $seat 应可选忠臣：$decision", decision.canSelect)
            current = current.map { if (it.seat == seat) it.copy(roleId = RoleId.LOYAL_SERVANT) else it }
        }
        assertEquals(3, policy.usedByOtherSeats(current, 4, RoleId.LOYAL_SERVANT))
        // 第四位默认满额
        val fourth = policy.evaluate(current, 4, RoleId.LOYAL_SERVANT)
        assertFalse(fourth.canSelect)
        assertTrue(fourth.isOverPlan)
        assertEquals("3/3", fourth.usageLabel)
        assertTrue(fourth.warning != null)
    }

    @Test
    fun threeLoyalServantsAreNotBlockedBySetBasedUniqueness() {
        val policy = RevealRoleQuotaPolicy(eightPlayerSlots())
        val current = rows(1 to RoleId.LOYAL_SERVANT, 2 to RoleId.LOYAL_SERVANT)
        // 旧的 `takenRoles: Set<RoleId>` 会在这里直接禁用；新策略必须仍可选
        val decision = policy.evaluate(current, 3, RoleId.LOYAL_SERVANT)
        assertTrue(decision.canSelect)
        assertEquals("2/3", decision.usageLabel)
    }

    // ————————————————————————— ID-13 梅林 1 —————————————————————————

    @Test
    fun merlinHasOneSlotAndSecondSeatIsFull() {
        val policy = RevealRoleQuotaPolicy(eightPlayerSlots())
        val current = rows(1 to RoleId.MERLIN)
        val decision = policy.evaluate(current, 2, RoleId.MERLIN)
        assertFalse(decision.canSelect)
        assertEquals("1/1", decision.usageLabel)
        assertTrue(decision.isOverPlan)
    }

    // ————————————————————————— ID-14 改选释放配额 —————————————————————————

    @Test
    fun switchingRoleReleasesTheOldQuota() {
        val policy = RevealRoleQuotaPolicy(eightPlayerSlots())
        val before = rows(
            1 to RoleId.LOYAL_SERVANT,
            2 to RoleId.LOYAL_SERVANT,
            3 to RoleId.LOYAL_SERVANT
        )
        assertEquals(3, policy.usedByOtherSeats(before, 9, RoleId.LOYAL_SERVANT))

        // 3 号从忠臣改选派西维尔后，忠臣只剩 2 个占用，4 号又能选忠臣
        val after = before.map { if (it.seat == 3) it.copy(roleId = RoleId.PERCIVAL) else it }
        assertEquals(2, policy.usedByOtherSeats(after, 9, RoleId.LOYAL_SERVANT))
        assertTrue(policy.evaluate(after, 4, RoleId.LOYAL_SERVANT).canSelect)
    }

    @Test
    fun editingSeatsOwnRoleNeverLocksItselfOut() {
        val policy = RevealRoleQuotaPolicy(eightPlayerSlots())
        val current = rows(1 to RoleId.MERLIN)
        // 1 号当前就是梅林，重选梅林不能被「1/1 已满」锁死
        val decision = policy.evaluate(current, 1, RoleId.MERLIN)
        assertTrue(decision.canSelect)
        assertEquals(0, policy.usedByOtherSeats(current, 1, RoleId.MERLIN))
        assertEquals("0/1", decision.usageLabel)
    }

    // ————————————————————————— ID-15 UNKNOWN —————————————————————————

    @Test
    fun unknownIsAlwaysSelectableAndNeverConsumesQuota() {
        val policy = RevealRoleQuotaPolicy(eightPlayerSlots())
        val current = rows(
            1 to RoleId.UNKNOWN, 2 to RoleId.UNKNOWN, 3 to RoleId.UNKNOWN,
            4 to RoleId.UNKNOWN, 5 to RoleId.UNKNOWN
        )
        (1..5).forEach { seat ->
            assertTrue(policy.evaluate(current, seat, RoleId.UNKNOWN).canSelect)
        }
        assertEquals(0, policy.plannedQuota(RoleId.UNKNOWN))
        assertEquals(0, policy.usedByOtherSeats(current, 9, RoleId.UNKNOWN))
        // 不能自动把 UNKNOWN 补成忠臣
        assertFalse(policy.hasAutofill)
        assertEquals(RoleId.UNKNOWN, policy.suggestedFallbackRole)
    }

    // ————————————————————————— ID-17 计划 ≠ 真相 —————————————————————————

    @Test
    fun overPlanAssignmentIsAWarningNotABlockedWrite() {
        val policy = RevealRoleQuotaPolicy(eightPlayerSlots())
        val current = rows(1 to RoleId.MERLIN)
        val decision = policy.evaluate(current, 2, RoleId.MERLIN)

        assertFalse(decision.canSelect)
        assertTrue(decision.requiresExplicitConfirmation)
        assertTrue(decision.warning!!.contains("计划"))
        // 关键：策略层只产出警告，绝不改计划、也绝不改已填的真实身份
        assertEquals(1, policy.plannedQuota(RoleId.MERLIN))
        assertEquals(RoleId.MERLIN, current.single { it.seat == 1 }.roleId)
        assertFalse(decision.wouldMutatePlan)
        assertFalse(decision.wouldMutateTruth)
    }

    @Test
    fun planNeverOverridesRecordedTruth() {
        val policy = RevealRoleQuotaPolicy(eightPlayerSlots())
        // 真实记录里出现了 4 个忠臣（与计划 3 不符）
        val truth = rows(
            1 to RoleId.LOYAL_SERVANT, 2 to RoleId.LOYAL_SERVANT,
            3 to RoleId.LOYAL_SERVANT, 4 to RoleId.LOYAL_SERVANT
        )
        val mismatch = policy.planMismatch(truth)
        assertTrue(mismatch.hasMismatch)
        assertTrue(mismatch.description.contains("忠臣"))
        assertTrue(mismatch.description.contains("4"))
        assertTrue(mismatch.description.contains("3"))
        // 计划仍为 3，真相仍为 4，没有任何自动补齐或截断
        assertEquals(3, policy.plannedQuota(RoleId.LOYAL_SERVANT))
        assertEquals(4, truth.count { it.roleId == RoleId.LOYAL_SERVANT })
    }

    @Test
    fun completeTruthMatchingPlanHasNoMismatch() {
        val policy = RevealRoleQuotaPolicy(eightPlayerSlots())
        val truth = rows(
            1 to RoleId.MERLIN, 2 to RoleId.PERCIVAL,
            3 to RoleId.LOYAL_SERVANT, 4 to RoleId.LOYAL_SERVANT,
            5 to RoleId.LOYAL_SERVANT, 6 to RoleId.ASSASSIN,
            7 to RoleId.MORGANA, 8 to RoleId.MORDRED
        )
        assertFalse(policy.planMismatch(truth).hasMismatch)
    }
}
