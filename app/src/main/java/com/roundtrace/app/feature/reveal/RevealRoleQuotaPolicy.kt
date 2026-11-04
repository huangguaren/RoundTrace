package com.roundtrace.app.feature.reveal

import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.RoleSlotSpec

/**
 * 结局揭晓的**角色配额策略**（REVEAL-Q-01 / Phase D）。
 *
 * ## 修复的是什么
 * 旧实现用 `takenRoles: Set<RoleId>` 判断唯一性：只要有一个座位选了忠臣，
 * 其余座位的忠臣选项就被禁用。但 8 人标准局计划里有 **3 名忠臣** —— 计划是
 * 「每种角色可以有几份」，不是「每种角色只能选一次」。
 *
 * ## 三条不可让步的口径
 * 1. 配额**只来自本局 [slots]**（`GameConfig.roleSlots`），按 roleId **合计**多个
 *    [RoleSlotSpec.count]；不从人数硬编码，也不重建 `AvalonPreset.standardPreset()`。
 * 2. `UNKNOWN` 永不占额、永不自动补齐 —— 未揭晓就是未揭晓。
 * 3. **计划 ≠ 真实身份**（ARCHITECTURE §2.2 / §9.3-3，GameConfig 类注释）：
 *    超额只是「与开局计划不符」的**警告**，必须保留按真实记录保存的路径，
 *    绝不自动改写 `roleSlots`，也绝不篡改已录入的 `role_assignments`。
 *
 * 本类是纯 Kotlin：不依赖 Android / Room / Compose，也不写任何仓储。
 */
class RevealRoleQuotaPolicy private constructor(
    private val quota: Map<RoleId, Int>
) {

    /** 从本局 [GameConfig.roleSlots][*] 构造：重复槽位按 roleId 相加。 */
    constructor(slots: List<RoleSlotSpec>) : this(sumSlots(slots))

    companion object {

        /** 从已归一化的 UiState 配额表构造（草稿态重算差异用，不重新读计划）。 */
        fun fromQuotaMap(quotaMap: Map<RoleId, Int>): RevealRoleQuotaPolicy =
            RevealRoleQuotaPolicy(
                quotaMap.filterKeys { it != RoleId.UNKNOWN }.filterValues { it > 0 }
            )

        /** 显式循环求和，避开 `Grouping.fold` 的重载歧义，也让「合计」语义一眼可见。 */
        fun sumSlots(slots: List<RoleSlotSpec>): Map<RoleId, Int> {
            val result = LinkedHashMap<RoleId, Int>()
            slots.forEach { slot ->
                if (slot.roleId != RoleId.UNKNOWN && slot.count > 0) {
                    result[slot.roleId] = (result[slot.roleId] ?: 0) + slot.count
                }
            }
            return result
        }

        /** 纯 Kotlin 角色名（不调用 `@Composable` 的 `roleDisplayName`）。 */
        fun roleLabel(role: RoleId): String = com.roundtrace.app.core.replay.TimelineBuilder
            .roleLabel(role.name)
    }

    /** [*]: com.roundtrace.app.core.model.GameConfig */

    /** 计划中某角色的名额（UNKNOWN 与未列入计划的角色均为 0）。 */
    fun plannedQuota(role: RoleId): Int = quota[role] ?: 0

    /** 导出归一化后的配额表，供 UiState 展示「已选 n / 计划 m」。 */
    fun quotaMap(): Map<RoleId, Int> = quota.toMap()

    /** 永远不自动补齐 UNKNOWN。 */
    val hasAutofill: Boolean get() = false

    /** 兜底角色恒为 UNKNOWN：没有「把未知补成忠臣」这种逻辑。 */
    val suggestedFallbackRole: RoleId get() = RoleId.UNKNOWN

    /**
     * 除 [editingSeat] 之外，已分配给 [role] 的座位数。
     *
     * 排除当前座位是关键：用户重选自己已占用的角色时，不能被「1/1 已满」锁死。
     */
    fun usedByOtherSeats(
        rows: List<RevealSeatRow>,
        editingSeat: Int,
        role: RoleId
    ): Int = if (role == RoleId.UNKNOWN) {
        0
    } else {
        rows.count { it.seat != editingSeat && it.roleId == role }
    }

    /**
     * 评估「把 [editingSeat] 设为 [candidate]」这一步。
     *
     * @param canSelect 是否可直接选择（超额时为 false，但不是「禁止」——见 [requiresExplicitConfirmation]）
     * @param isOverPlan 是否已达到/超出计划名额
     * @param requiresExplicitConfirmation 超额时必须由用户二次确认「按实际身份记录」
     * @param warning 面向用户的差异说明；**只警告，不阻断、不改写数据**
     */
    data class Decision(
        val canSelect: Boolean,
        val used: Int,
        val planned: Int,
        val isOverPlan: Boolean,
        val requiresExplicitConfirmation: Boolean,
        val warning: String?
    ) {
        /** 「已选 n / 计划 m」；UNKNOWN 与无计划角色不显示配额。 */
        val usageLabel: String get() = "$used/$planned"

        /** 本策略永不修改开局计划。 */
        val wouldMutatePlan: Boolean get() = false

        /** 本策略永不修改已录入的真实身份。 */
        val wouldMutateTruth: Boolean get() = false
    }

    fun evaluate(
        rows: List<RevealSeatRow>,
        editingSeat: Int,
        candidate: RoleId
    ): Decision {
        // UNKNOWN 不限数量、不占额、不需要确认
        if (candidate == RoleId.UNKNOWN) {
            return Decision(
                canSelect = true,
                used = 0,
                planned = 0,
                isOverPlan = false,
                requiresExplicitConfirmation = false,
                warning = null
            )
        }

        val planned = plannedQuota(candidate)
        val used = usedByOtherSeats(rows, editingSeat, candidate)
        // 计划里没有这个角色（自定义配置可能不含梅林）：不按「0 名额」硬禁用，
        // 只给一条差异警告，因为真实身份可能确实与开局计划不同。
        val overPlan = planned <= 0 || used >= planned

        return Decision(
            canSelect = !overPlan,
            used = used,
            planned = planned,
            isOverPlan = overPlan,
            requiresExplicitConfirmation = overPlan,
            warning = if (overPlan) {
                val name = roleLabel(candidate)
                if (planned <= 0) {
                    "「$name」不在开局计划中。创建时的配置只是计划，真实身份可能不同；" +
                        "如需按实际结果记录，请确认后继续，开局计划不会被修改。"
                } else {
                    "「$name」已达到开局计划的 $planned 个名额（已选 $used）。" +
                        "计划不等于最终真相；若实际身份超出计划，请确认后按实际记录保存。"
                }
            } else {
                null
            }
        )
    }

    /**
     * 已录入的真实身份与开局计划的差异。
     *
     * 只产出**描述**，不做任何修正：调用方据此显示警告与独立确认。
     * 未填完（含 UNKNOWN）不视为差异，避免把「还没录完」误报成「计划与真相不符」。
     */
    data class Mismatch(
        val hasMismatch: Boolean,
        val description: String
    )

    fun planMismatch(rows: List<RevealSeatRow>): Mismatch {
        // 两种差异要分开处理（PRD §E）：
        // - **已超额**（实际 > 计划）：明确的「真实身份超出开局计划」，即使还没录完也要警告；
        // - **少于计划**（实际 < 计划）：很可能只是没录完，项目允许不完整揭晓
        //   （T56⑤ 高级结论区显示信息不足），此时不报差异，避免拦住既有揭晓路径。
        val fullyRecorded = rows.none { it.roleId == RoleId.UNKNOWN }
        val differences = (quota.keys + rows.map { it.roleId }
            .filter { it != RoleId.UNKNOWN })
            .distinct()
            .mapNotNull { role ->
                val planned = plannedQuota(role)
                val actual = rows.count { it.roleId == role }
                val isDifference = actual > planned || (fullyRecorded && actual < planned)
                if (isDifference) {
                    "${roleLabel(role)}：计划 $planned / 实际 $actual"
                } else {
                    null
                }
            }
        return Mismatch(
            hasMismatch = differences.isNotEmpty(),
            description = if (differences.isEmpty()) {
                ""
            } else {
                "真实身份与开局计划不一致（${differences.joinToString("；")}）。" +
                    "计划只是创建时的配置，按实际记录保存不会修改计划。"
            }
        )
    }
}
