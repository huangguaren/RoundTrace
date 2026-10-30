package com.roundtrace.app.core.knowledge

import com.roundtrace.app.core.model.SeatId

/**
 * 「这支队里至少有几个坏人」的上下界算法（ARCHITECTURE §7.4 / TASKS.md T52①）。
 *
 * 这是产品里**最容易被写错**的一个推导：失败牌数不能定位到具体座位，
 * 只能给出**下界/上界**。任何「4 号必然是坏人」的结论都不允许从这里产生。
 */
data class CountBounds(
    val minEvil: Int,
    val maxEvil: Int
) {

    /** 队伍里是否至少有 1 名坏人（K07 的核心结论）。 */
    val atLeastOneEvil: Boolean
        get() = minEvil >= 1

    /** 上界为 0 表示「这支队伍全是好人」（极少出现，需要足够强的已知信息）。 */
    val allGood: Boolean
        get() = maxEvil == 0
}

/**
 * 计算队伍中坏人数量的上下界。
 *
 * 推导（ARCHITECTURE §7.4，唯一正确形式）：
 * ```
 * teamSize          = |teamSeats|
 * knownEvilInTeam   = |teamSeats ∩ knownEvil|
 * knownGoodOutside  = |knownGood \ teamSeats|
 * maxGoodOnTeam     = min(goodCount - knownGoodOutside, teamSize - knownEvilInTeam)
 * minEvil           = max(knownEvilInTeam, teamSize - maxGoodOnTeam)
 * maxEvil           = min(evilCount, teamSize - |teamSeats ∩ knownGood|)
 * ```
 *
 * 自检（= K07）：8 人（good=5, evil=3），观察者 1 号（好人，不在车上），
 * `knownGood={1}`、`knownEvil={3,5}`、`team={2,4,6,7,8}`
 * → `knownEvilInTeam=0`、`knownGoodOutside=1`、`maxGoodOnTeam=min(4,5)=4`、`minEvil=max(0,5-4)=1` ✓
 *
 * @param teamSeats 队伍座位
 * @param goodCount 规则矩阵里的好人总数
 * @param evilCount 规则矩阵里的坏人总数
 * @param knownGood 该视角下**确定**的好人座位（含观察者自己，若其角色为好人）
 * @param knownEvil 该视角下**确定**的坏人座位
 */
fun bounds(
    teamSeats: List<SeatId>,
    goodCount: Int,
    evilCount: Int,
    knownGood: Set<SeatId>,
    knownEvil: Set<SeatId>
): CountBounds {
    val team = teamSeats.toSet()
    val teamSize = team.size
    if (teamSize == 0) return CountBounds(minEvil = 0, maxEvil = 0)

    val knownEvilInTeam = team.count { knownEvil.contains(it) }
    val knownGoodOutside = knownGood.count { !team.contains(it) }
    val knownGoodInTeam = team.count { knownGood.contains(it) }

    val maxGoodOnTeam = minOf(
        (goodCount - knownGoodOutside).coerceAtLeast(0),
        teamSize - knownEvilInTeam
    )
    val minEvil = maxOf(knownEvilInTeam, teamSize - maxGoodOnTeam)

    // 上界：队伍里排除「确定的好人」后，最多全是坏人；再受坏人总数约束
    val maxEvil = minOf(
        evilCount.coerceAtLeast(0),
        teamSize - knownGoodInTeam
    )

    // 逻辑自洽：上界不得小于下界（脏输入时以 minEvil 为准）
    return CountBounds(
        minEvil = minEvil,
        maxEvil = maxOf(minEvil, maxEvil)
    )
}
