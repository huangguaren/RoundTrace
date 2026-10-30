package com.roundtrace.app.util

import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.RoleId

/**
 * TDD §11 的真实角色表（测试夹具常量，TASKS.md T23①②）。
 *
 * 8 人局：1 梅林 / 2 忠臣 / 3 莫甘娜 / 4 莫德雷德 / 5 刺客 / 6 忠臣 / 7 派西维尔 / 8 忠臣。
 */
object RoleFixtures {

    const val MERLIN_SEAT: Int = 1
    const val LOYAL_SERVANT_2_SEAT: Int = 2
    const val MORGANA_SEAT: Int = 3
    const val MORDRED_SEAT: Int = 4
    const val ASSASSIN_SEAT: Int = 5
    const val LOYAL_SERVANT_6_SEAT: Int = 6
    const val PERCIVAL_SEAT: Int = 7
    const val LOYAL_SERVANT_8_SEAT: Int = 8

    /** 8 人 Merlin/Mordred 变体的座位 → 角色表（顺序固定，便于断言）。 */
    val EIGHT_PLAYER_MERLIN_MORDRED: Map<Int, RoleId> = linkedMapOf(
        1 to RoleId.MERLIN,
        2 to RoleId.LOYAL_SERVANT,
        3 to RoleId.MORGANA,
        4 to RoleId.MORDRED,
        5 to RoleId.ASSASSIN,
        6 to RoleId.LOYAL_SERVANT,
        7 to RoleId.PERCIVAL,
        8 to RoleId.LOYAL_SERVANT
    )

    /**
     * 6 人局角色表（用于 R07：6 人第 4 任务 1 张失败牌即失败）。
     *
     * 按官方规则手册：梅林 1、忠臣 3、刺客 1、爪牙 1 —— **6 人局没有派西维尔、没有莫甘娜**。
     */
    val SIX_PLAYER: Map<Int, RoleId> = linkedMapOf(
        1 to RoleId.MERLIN,
        2 to RoleId.LOYAL_SERVANT,
        3 to RoleId.LOYAL_SERVANT,
        4 to RoleId.LOYAL_SERVANT,
        5 to RoleId.ASSASSIN,
        6 to RoleId.MINION
    )

    /**
     * 10 人局角色表（用于验证奥伯伦的两条特殊规则）。
     *
     * 官方构成：梅林 1、派西维尔 1、忠臣 4、刺客 1、莫甘娜 1、莫德雷德 1、**奥伯伦 1**。
     */
    val TEN_PLAYER_OBERON: Map<Int, RoleId> = linkedMapOf(
        1 to RoleId.MERLIN,
        2 to RoleId.PERCIVAL,
        3 to RoleId.LOYAL_SERVANT,
        4 to RoleId.LOYAL_SERVANT,
        5 to RoleId.LOYAL_SERVANT,
        6 to RoleId.LOYAL_SERVANT,
        7 to RoleId.ASSASSIN,
        8 to RoleId.MORGANA,
        9 to RoleId.MORDRED,
        10 to RoleId.OBERON
    )

    /** 8 人局中确定的坏人座位（梅林可见：3、5；4 莫德雷德不可见）。 */
    val EVIL_SEATS_EIGHT_PLAYER: Set<Int> = setOf(3, 4, 5)

    /** 梅林夜间应看到的坏人（排除莫德雷德）。 */
    val MERLIN_VISIBLE_EVIL_EIGHT_PLAYER: List<Int> = listOf(3, 5)

    /** 派西维尔的梅林候选（存在莫甘娜时）。 */
    val PERCIVAL_CANDIDATES_EIGHT_PLAYER: List<Int> = listOf(1, 3)

    /** 反查：某角色在角色表中占哪些座位（按座位升序）。 */
    fun seatsOfRole(
        role: RoleId,
        rolesBySeat: Map<Int, RoleId> = EIGHT_PLAYER_MERLIN_MORDRED
    ): List<Int> = rolesBySeat.entries
        .filter { it.value == role }
        .map { it.key }
        .sorted()

    /** 反查：某阵营占哪些座位（按座位升序）。 */
    fun seatsOfAlignment(
        alignment: Alignment,
        rolesBySeat: Map<Int, RoleId> = EIGHT_PLAYER_MERLIN_MORDRED
    ): List<Int> = rolesBySeat.entries
        .filter { it.value.alignment == alignment }
        .map { it.key }
        .sorted()
}
