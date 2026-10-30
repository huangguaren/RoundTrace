package com.roundtrace.app.core.rules.avalon

import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.RoleSlotSpec

/**
 * 阿瓦隆标准人数矩阵与预设角色构成。
 *
 * 两类数据严格分开：
 *  - **规则矩阵**（[STANDARD]，来自 TDD §4.1）：阵营人数 + 5 个任务的上车人数 —— 这是「规则」；
 *  - **预设角色构成**（[STANDARD_PRESETS]，来自官方规则手册）：每个座位该发什么角色 —— 这是「预设」。
 *
 * 为什么分开：8/9/10 人是否加莫甘娜/莫德雷德、10 人是否用奥伯伦，属于**预设立场**而不是规则判定。
 * 规则判定（[AvalonRules]）只读矩阵与阵营数；角色构成只被「创建对局」与「夜间知识」使用。
 * 因此这里用**常量表**表达，绝不把 `if (playerCount >= 8)` 之类的分支写进规则判定。
 *
 * ⚠️ 角色构成的权威来源（重要）：
 * 主理人 2026-10-30 裁决——PRD §6.1 只给了阵营人数、未定义角色构成，
 * ARCHITECTURE §9.3-4 与本任务原描述的「5–10 人固定含派西维尔、8 人以上加莫甘娜/莫德雷德」
 * **作废**，改为按《The Resistance: Avalon》官方规则手册补全，见 `docs/decisions.md` B17。
 *
 * 官方构成（与本文件 [STANDARD_PRESETS] 逐格一致）：
 * | 人数 | 好人 | 坏人 | 好人角色 | 坏人角色 |
 * |---|---|---|---|---|
 * | 5  | 3 | 2 | 梅林 1、忠臣 2 | 刺客 1、爪牙 1 |
 * | 6  | 4 | 2 | 梅林 1、忠臣 3 | 刺客 1、爪牙 1 |
 * | 7  | 4 | 3 | 梅林 1、派西维尔 1、忠臣 2 | 刺客 1、莫甘娜 1、爪牙 1 |
 * | 8  | 5 | 3 | 梅林 1、派西维尔 1、忠臣 3 | 刺客 1、莫甘娜 1、莫德雷德 1 |
 * | 9  | 6 | 3 | 梅林 1、派西维尔 1、忠臣 4 | 刺客 1、莫甘娜 1、莫德雷德 1 |
 * | 10 | 6 | 4 | 梅林 1、派西维尔 1、忠臣 4 | 刺客 1、莫甘娜 1、莫德雷德 1、奥伯伦 1 |
 *
 * 三条关键结论：
 * 1. **5、6 人局没有派西维尔**（派西维尔从 7 人局才开始出现）；
 * 2. 8 人局必须与 TDD §11 的测试夹具完全一致（1 梅林 / 2 忠臣 / 3 莫甘娜 / 4 莫德雷德 / 5 刺客 / 6 忠臣 / 7 派西维尔 / 8 忠臣）；
 * 3. **10 人局的第 4 个坏人是奥伯伦**（不是爪牙）——奥伯伦不参与坏人互认，梅林也看不见他。
 */
object AvalonPreset {

    /** 标准阿瓦隆规则集 id。 */
    const val RULESET_ID: String = "avalon.standard"

    /** 标准阿瓦隆规则集版本。 */
    const val RULESET_VERSION: Int = 1

    /** 展示名。 */
    const val DISPLAY_NAME: String = "阿瓦隆（标准 5–10 人）"

    const val MIN_PLAYERS: Int = 5
    const val MAX_PLAYERS: Int = 10

    /** 任务总数（固定 5 个）。 */
    const val QUEST_COUNT: Int = 5

    /** 五次连续否决即坏人胜。 */
    const val MAX_CONSECUTIVE_REJECTIONS: Int = 5

    /** 三次任务成功/失败触发终局判定。 */
    const val MISSIONS_TO_WIN: Int = 3

    /** 人数矩阵：阵营人数 + 5 个任务的上车人数。 */
    data class AvalonCounts(
        val good: Int,
        val evil: Int,
        val teamSizes: List<Int>
    ) {
        init {
            require(teamSizes.size == QUEST_COUNT) {
                "teamSizes 必须是 $QUEST_COUNT 项，实际 ${teamSizes.size} 项"
            }
        }

        /** 阵营总人数 */
        val total: Int
            get() = good + evil
    }

    /** 标准矩阵（TDD §4.1）。 */
    private val STANDARD: Map<Int, AvalonCounts> = mapOf(
        5 to AvalonCounts(good = 3, evil = 2, teamSizes = listOf(2, 3, 2, 3, 3)),
        6 to AvalonCounts(good = 4, evil = 2, teamSizes = listOf(2, 3, 4, 3, 4)),
        7 to AvalonCounts(good = 4, evil = 3, teamSizes = listOf(2, 3, 3, 4, 4)),
        8 to AvalonCounts(good = 5, evil = 3, teamSizes = listOf(3, 4, 4, 5, 5)),
        9 to AvalonCounts(good = 6, evil = 3, teamSizes = listOf(3, 4, 4, 5, 5)),
        10 to AvalonCounts(good = 6, evil = 4, teamSizes = listOf(3, 4, 4, 5, 5))
    )

    /** 只读的完整矩阵（测试直接比对用）。 */
    val standardMatrix: Map<Int, AvalonCounts>
        get() = STANDARD

    /** 支持的人数集合（升序）。 */
    val supportedPlayerCounts: List<Int>
        get() = STANDARD.keys.sorted()

    // ——————————————————————————————————————————————————————————
    // 预设角色构成（常量表，不是 if-else 分支）
    // ——————————————————————————————————————————————————————————

    /**
     * 标准预设角色构成表（官方规则手册）。
     *
     * 顺序固定为「好人特殊角色 → 忠臣 → 坏人特殊角色 → 爪牙/奥伯伦」，便于 UI 与测试按序展示/断言。
     * 每行都必须满足：角色总数 == 人数，且好/坏阵营数 == [STANDARD] 矩阵值（由 [standardPreset] 断言）。
     */
    private val STANDARD_PRESETS: Map<Int, List<RoleSlotSpec>> = mapOf(
        // 5 人：3 好 / 2 坏 —— 无派西维尔
        5 to listOf(
            RoleSlotSpec(RoleId.MERLIN, 1),
            RoleSlotSpec(RoleId.LOYAL_SERVANT, 2),
            RoleSlotSpec(RoleId.ASSASSIN, 1),
            RoleSlotSpec(RoleId.MINION, 1)
        ),
        // 6 人：4 好 / 2 坏 —— 无派西维尔
        6 to listOf(
            RoleSlotSpec(RoleId.MERLIN, 1),
            RoleSlotSpec(RoleId.LOYAL_SERVANT, 3),
            RoleSlotSpec(RoleId.ASSASSIN, 1),
            RoleSlotSpec(RoleId.MINION, 1)
        ),
        // 7 人：4 好 / 3 坏 —— 派西维尔出现，莫甘娜出现
        7 to listOf(
            RoleSlotSpec(RoleId.MERLIN, 1),
            RoleSlotSpec(RoleId.PERCIVAL, 1),
            RoleSlotSpec(RoleId.LOYAL_SERVANT, 2),
            RoleSlotSpec(RoleId.ASSASSIN, 1),
            RoleSlotSpec(RoleId.MORGANA, 1),
            RoleSlotSpec(RoleId.MINION, 1)
        ),
        // 8 人：5 好 / 3 坏 —— 与 TDD §11 夹具一致
        8 to listOf(
            RoleSlotSpec(RoleId.MERLIN, 1),
            RoleSlotSpec(RoleId.PERCIVAL, 1),
            RoleSlotSpec(RoleId.LOYAL_SERVANT, 3),
            RoleSlotSpec(RoleId.ASSASSIN, 1),
            RoleSlotSpec(RoleId.MORGANA, 1),
            RoleSlotSpec(RoleId.MORDRED, 1)
        ),
        // 9 人：6 好 / 3 坏
        9 to listOf(
            RoleSlotSpec(RoleId.MERLIN, 1),
            RoleSlotSpec(RoleId.PERCIVAL, 1),
            RoleSlotSpec(RoleId.LOYAL_SERVANT, 4),
            RoleSlotSpec(RoleId.ASSASSIN, 1),
            RoleSlotSpec(RoleId.MORGANA, 1),
            RoleSlotSpec(RoleId.MORDRED, 1)
        ),
        // 10 人：6 好 / 4 坏 —— 第 4 个坏人是奥伯伦（官方推荐），不是爪牙
        10 to listOf(
            RoleSlotSpec(RoleId.MERLIN, 1),
            RoleSlotSpec(RoleId.PERCIVAL, 1),
            RoleSlotSpec(RoleId.LOYAL_SERVANT, 4),
            RoleSlotSpec(RoleId.ASSASSIN, 1),
            RoleSlotSpec(RoleId.MORGANA, 1),
            RoleSlotSpec(RoleId.MORDRED, 1),
            RoleSlotSpec(RoleId.OBERON, 1)
        )
    )

    /** 只读的完整预设表（测试逐行比对用）。 */
    val standardPresetTable: Map<Int, List<RoleSlotSpec>>
        get() = STANDARD_PRESETS

    /**
     * 角色在 UI 上的**展示顺序**（唯一来源）。
     *
     * 放在 ruleset 而不是 UI：否则 feature 层又要自己维护一份「角色清单」，
     * 一旦预设变化（例如 10 人局换成奥伯伦）UI 顺序就会与规则脱节。
     */
    val roleDisplayOrder: List<RoleId> = listOf(
        RoleId.MERLIN,
        RoleId.PERCIVAL,
        RoleId.LOYAL_SERVANT,
        RoleId.ASSASSIN,
        RoleId.MORGANA,
        RoleId.MORDRED,
        RoleId.OBERON,
        RoleId.MINION
    )

    /**
     * 取某个人数档的矩阵。
     *
     * @throws IllegalArgumentException 人数不在 5..10（TASKS.md T17③）
     */
    fun counts(playerCount: Int): AvalonCounts {
        return STANDARD[playerCount]
            ?: throw IllegalArgumentException(
                "不支持的人数：$playerCount；阿瓦隆标准局仅支持 " +
                    "$MIN_PLAYERS..$MAX_PLAYERS 人"
            )
    }

    /** 预设标识，如 `avalon.standard.8p`。 */
    fun presetId(playerCount: Int): String = "$RULESET_ID.${playerCount}p"

    /** 判断某个 presetId 是否等于该人数的标准预设。 */
    fun isStandardPreset(playerCount: Int, candidatePresetId: String): Boolean {
        if (playerCount !in MIN_PLAYERS..MAX_PLAYERS) {
            return candidatePresetId.startsWith("$RULESET_ID.")
        }
        return candidatePresetId == presetId(playerCount)
    }

    /**
     * 标准预设的角色构成（按官方规则手册，逐人数从 [STANDARD_PRESETS] 取值）。
     *
     * 本方法只做「取表 + 自洽断言」，不含任何按人数分支的 if-else。
     *
     * @throws IllegalArgumentException 人数不在 5..10
     */
    fun standardPreset(playerCount: Int): List<RoleSlotSpec> {
        val counts = counts(playerCount)
        val slots = STANDARD_PRESETS[playerCount]
            ?: throw IllegalArgumentException("未定义 $playerCount 人标准预设")

        val total = slots.sumOf { it.count }
        require(total == playerCount) {
            "标准预设角色总数必须等于人数：playerCount=$playerCount, total=$total"
        }

        val goodUsed = slots.sumOf { if (it.roleId.alignment == Alignment.GOOD) it.count else 0 }
        val evilUsed = slots.sumOf { if (it.roleId.alignment == Alignment.EVIL) it.count else 0 }
        require(goodUsed == counts.good) {
            "标准预设好人数量与矩阵不一致：playerCount=$playerCount, " +
                "expected=${counts.good}, actual=$goodUsed"
        }
        require(evilUsed == counts.evil) {
            "标准预设坏人数量与矩阵不一致：playerCount=$playerCount, " +
                "expected=${counts.evil}, actual=$evilUsed"
        }
        require(slots.none { it.roleId == RoleId.UNKNOWN }) {
            "标准预设不得包含 UNKNOWN 角色：playerCount=$playerCount"
        }
        require(slots.all { it.count > 0 }) {
            "标准预设不得出现数量为 0 的角色槽：playerCount=$playerCount"
        }
        require(slots.map { it.roleId }.distinct().size == slots.size) {
            "标准预设同一角色不得拆成多个槽：playerCount=$playerCount"
        }
        return slots
    }

    /** 标准预设的阵营统计，方便 UI 与测试直接取。 */
    fun standardAlignmentCounts(playerCount: Int): Pair<Int, Int> {
        val counts = counts(playerCount)
        return counts.good to counts.evil
    }
}
