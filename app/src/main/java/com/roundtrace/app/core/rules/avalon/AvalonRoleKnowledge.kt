package com.roundtrace.app.core.rules.avalon

import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.NightKnowledge
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.SeatId

/**
 * 阿瓦隆夜间知识规则（ARCHITECTURE §7.1 的 [NightKnowledge] 生成器，TASKS.md T19）。
 *
 * 规则要点（TDD §4.3）：
 * - 梅林：知道大多数坏人，但**不包括莫德雷德**，也不包括奥伯伦（本规则版本显式排除）；
 * - 派西维尔：知道梅林候选；存在莫甘娜时候选为 [梅林, 莫甘娜]，无法仅凭夜间信息区分真假；
 * - 坏人互认：**排除奥伯伦**与自身；
 * - 输入含 `UNKNOWN` 角色时返回空知识列表且**不抛异常**（T19④）。
 *
 * 输出是纯数据（[NightKnowledge]），不含任何 Android 依赖。
 * `notes` 里放稳定的 **规则键**（SCREAMING_SNAKE），由 UI 层映射为中文文案（D6）。
 */
object AvalonRoleKnowledge {

    /** 规则键：梅林可见坏人（排除莫德雷德/奥伯伦）。 */
    const val NOTE_MERLIN_EXCLUDES_MORDRED_AND_OBERON: String = "MERLIN_EXCLUDES_MORDRED_AND_OBERON"

    /** 规则键：派西维尔看到两名梅林候选且无法区分。 */
    const val NOTE_PERCIVAL_TWO_CANDIDATES: String = "PERCIVAL_TWO_CANDIDATES"

    /** 规则键：派西维尔只看到真梅林。 */
    const val NOTE_PERCIVAL_SINGLE_CANDIDATE: String = "PERCIVAL_SINGLE_CANDIDATE"

    /** 规则键：坏人互认排除奥伯伦。 */
    const val NOTE_EVIL_TEAMMATES_EXCLUDE_OBERON: String = "EVIL_TEAMMATES_EXCLUDE_OBERON"

    /** 规则键：奥伯伦不参与坏人互认。 */
    const val NOTE_OBERON_IS_SOLO: String = "OBERON_IS_SOLO"

    /** 规则键：该座位角色未知，夜间知识为空。 */
    const val NOTE_ROLE_UNKNOWN: String = "ROLE_UNKNOWN"

    /** 规则键：该角色没有额外夜间知识。 */
    const val NOTE_NO_EXTRA_KNOWLEDGE: String = "NO_EXTRA_KNOWLEDGE"

    /**
     * 某个座位在开局的夜间知识。
     *
     * @param seat 观察座位
     * @param roleId 该座位角色（可为 [RoleId.UNKNOWN]）
     * @param assignments 全部座位的真实角色
     */
    fun knowledgeFor(
        seat: SeatId,
        roleId: RoleId,
        assignments: Map<SeatId, RoleId>
    ): NightKnowledge {
        if (roleId == RoleId.UNKNOWN) {
            return NightKnowledge(
                observerSeat = seat,
                role = RoleId.UNKNOWN,
                visibleEvilSeats = emptyList(),
                merlinCandidates = emptyList(),
                evilTeammates = emptyList(),
                notes = listOf(NOTE_ROLE_UNKNOWN)
            )
        }

        val evilSeats = assignments
            .filterValues { it.alignment == Alignment.EVIL }
            .keys
            .sorted()

        return when (roleId) {
            RoleId.MERLIN -> NightKnowledge(
                observerSeat = seat,
                role = roleId,
                // 排除莫德雷德与奥伯伦（T19①）
                visibleEvilSeats = evilSeats.filter { evilSeat ->
                    val role = assignments[evilSeat]
                    role != RoleId.MORDRED && role != RoleId.OBERON
                },
                merlinCandidates = emptyList(),
                evilTeammates = emptyList(),
                notes = listOf(NOTE_MERLIN_EXCLUDES_MORDRED_AND_OBERON)
            )

            RoleId.PERCIVAL -> {
                val merlinSeats = assignments.filterValues { it == RoleId.MERLIN }.keys.sorted()
                val morganaSeats = assignments.filterValues { it == RoleId.MORGANA }.keys.sorted()
                val candidates = (merlinSeats + morganaSeats).distinct().sorted()
                NightKnowledge(
                    observerSeat = seat,
                    role = roleId,
                    visibleEvilSeats = emptyList(),
                    merlinCandidates = candidates,
                    evilTeammates = emptyList(),
                    notes = listOf(
                        if (morganaSeats.isEmpty()) {
                            NOTE_PERCIVAL_SINGLE_CANDIDATE
                        } else {
                            NOTE_PERCIVAL_TWO_CANDIDATES
                        }
                    )
                )
            }

            RoleId.OBERON -> NightKnowledge(
                observerSeat = seat,
                role = roleId,
                visibleEvilSeats = emptyList(),
                merlinCandidates = emptyList(),
                // 奥伯伦不参与坏人互认（T19③）
                evilTeammates = emptyList(),
                notes = listOf(NOTE_OBERON_IS_SOLO)
            )

            RoleId.MORGANA, RoleId.ASSASSIN, RoleId.MORDRED, RoleId.MINION -> NightKnowledge(
                observerSeat = seat,
                role = roleId,
                visibleEvilSeats = emptyList(),
                merlinCandidates = emptyList(),
                // 互认排除奥伯伦与自身
                evilTeammates = evilSeats.filter { it != seat && assignments[it] != RoleId.OBERON },
                notes = listOf(NOTE_EVIL_TEAMMATES_EXCLUDE_OBERON)
            )

            RoleId.LOYAL_SERVANT -> NightKnowledge(
                observerSeat = seat,
                role = roleId,
                visibleEvilSeats = emptyList(),
                merlinCandidates = emptyList(),
                evilTeammates = emptyList(),
                notes = listOf(NOTE_NO_EXTRA_KNOWLEDGE)
            )

            RoleId.UNKNOWN -> NightKnowledge(
                observerSeat = seat,
                role = RoleId.UNKNOWN,
                visibleEvilSeats = emptyList(),
                merlinCandidates = emptyList(),
                evilTeammates = emptyList(),
                notes = listOf(NOTE_ROLE_UNKNOWN)
            )
        }
    }

    /**
     * 整局夜间知识表（按座位升序）。
     *
     * 角色为 `UNKNOWN` 的座位**不进入**结果列表（无知识可言），
     * 从而保证「输入含 UNKNOWN 时返回空知识列表且不抛异常」（T19④）。
     */
    fun knowledgeTable(assignments: Map<SeatId, RoleId>): List<NightKnowledge> =
        assignments.entries
            .filter { it.value != RoleId.UNKNOWN }
            .sortedBy { it.key }
            .map { (seat, role) -> knowledgeFor(seat = seat, roleId = role, assignments = assignments) }
}
