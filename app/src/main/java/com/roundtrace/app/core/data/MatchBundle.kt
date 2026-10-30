package com.roundtrace.app.core.data

import com.roundtrace.app.core.model.GameConfig
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MatchSummary
import com.roundtrace.app.core.model.Participant
import com.roundtrace.app.core.model.ReconstructedMatch
import com.roundtrace.app.core.model.RoleAssignment
import com.roundtrace.app.core.model.SeatId

/**
 * 重放/展示所需的完整对局快照（ARCHITECTURE §3.1 / TASKS.md T34②）。
 *
 * 这是 **端口层** 的类型：不含任何 Room 注解或 Entity 引用，
 * ViewModel 只看得到它，因此 feature 层不会反向依赖数据层。
 *
 * @param events **仅 `retracted = false`**，按 `occurredOrder` 升序
 * @param revealedAt 身份揭晓时刻；`null` = 尚未揭晓（上帝视角不可用）
 */
data class MatchBundle(
    val summary: MatchSummary,
    val config: GameConfig,
    val participants: List<Participant>,
    val roleAssignments: Map<SeatId, RoleAssignment>,
    val events: List<MatchEvent>,
    val revealedAt: Long?
) {

    /** 直接映射为领域重放输入（T34②：不做任何加工）。 */
    fun toReconstructedMatch(): ReconstructedMatch = ReconstructedMatch(
        matchId = summary.matchId,
        config = config,
        participants = participants,
        roleAssignments = roleAssignments,
        events = events,
        revealedAt = revealedAt
    )

    /** 角色真值表（未填写的座位为 `UNKNOWN`）。 */
    fun roleTruth(): Map<SeatId, com.roundtrace.app.core.model.RoleId> =
        roleAssignments.mapValues { it.value.roleId }
}

/**
 * 导入用的整场数据（TASKS.md T34 / T35⑥）。
 *
 * 之所以单独定义而不是直接复用 `MatchBundle`：导入时 `MatchSummary` 的
 * `matchId` / `updatedAt` 由仓储重新生成，导入方不该提供这些值。
 * M5 的 `MatchJsonCodec` 会产出该类型。
 */
data class ImportBundle(
    val config: GameConfig,
    val participants: List<Participant>,
    val roleAssignments: List<RoleAssignment>,
    val events: List<MatchEvent>,
    val revealedAt: Long? = null
)
