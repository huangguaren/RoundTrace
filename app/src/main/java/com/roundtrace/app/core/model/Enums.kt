package com.roundtrace.app.core.model

/**
 * 全部领域枚举（ARCHITECTURE §2.1 逐项实现）。
 *
 * 纯 Kotlin：禁止 import android.* / androidx.*。
 */

/** 游戏类型。`WEREWOLF` 仅占位，MVP 无实现（为 P1 扩展保留接口形状）。 */
enum class GameType {
    AVALON,
    WEREWOLF
}

/** 阵营。`UNKNOWN` 是一等公民：未知就是未知，绝不用人数规则反推填补。 */
enum class Alignment {
    GOOD,
    EVIL,
    UNKNOWN
}

/**
 * 角色 ID。
 *
 * [alignment] 是角色→阵营的唯一映射来源（`RoleAssignment.alignment` 委托它，不重复判断）。
 */
enum class RoleId(val alignment: Alignment) {
    MERLIN(Alignment.GOOD),
    PERCIVAL(Alignment.GOOD),
    LOYAL_SERVANT(Alignment.GOOD),
    MORGANA(Alignment.EVIL),
    ASSASSIN(Alignment.EVIL),
    MORDRED(Alignment.EVIL),
    MINION(Alignment.EVIL),
    OBERON(Alignment.EVIL),
    UNKNOWN(Alignment.UNKNOWN);

    companion object {

        /**
         * 从持久化字符串解析。
         *
         * 硬约束（TASKS.md T11③）：`null` 或未知字符串必须返回 [UNKNOWN]，**不得抛异常**——
         * 旧版本数据/手工编辑的 JSON 不能让 App 崩溃。
         */
        fun fromPersisted(raw: String?): RoleId {
            if (raw == null) return UNKNOWN
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = false) } ?: UNKNOWN
        }

        /** 标准局必须恰好 1 张的角色（梅林、刺客）。 */
        val REQUIRED_SINGLETON_IN_STANDARD: List<RoleId> = listOf(MERLIN, ASSASSIN)

        /** 任何配置下都至多 1 张的角色（含可选变体）。 */
        val AT_MOST_ONE: List<RoleId> = listOf(PERCIVAL, MORGANA, MORDRED, OBERON)
    }
}

/**
 * 对局生命周期状态（持久化到 `matches.status`）。
 *
 * 刻意**不包含** `INCOMPLETE`：数据完整性是正交维度，用 [IntegrityStatus] 表达
 * （ARCHITECTURE §9.3-2）。混进状态机会导致穷举分支爆炸。
 */
enum class MatchStatus {
    /** 已创建，尚无任何 QUEST_STARTED */
    DRAFT,
    IN_PROGRESS,

    /** 三次任务成功、刺客未结算 */
    AWAITING_ASSASSINATION,
    FINISHED_GOOD,
    FINISHED_EVIL,
    ABANDONED
}

/** reducer 内部推进阶段（不持久化，由事件推导）。 */
enum class RecordPhase {
    /** 等待第 1 次 QUEST_STARTED */
    NOT_STARTED,

    /** 等待/可编辑队伍提案 */
    PROPOSAL_OPEN,

    /** 已有提案，等待投票 */
    VOTING,

    /** 提案已通过，等待任务牌 */
    MISSION_PENDING,

    /** 本任务已结算，等待下一次 QUEST_STARTED */
    QUEST_RESOLVED,
    ASSASSINATION,
    ENDED
}

/** 数据完整性（与状态机正交）。 */
enum class IntegrityStatus {
    OK,
    NEEDS_REVIEW,
    INCOMPLETE
}

/** 组队投票的单人选择。 */
enum class TeamVoteChoice {
    APPROVE,
    REJECT,
    UNKNOWN
}

/** 组队提案的最终决议。 */
enum class ProposalDecision {
    APPROVED,
    REJECTED,
    UNKNOWN
}

/** 任务牌结果（与组队投票是**不同字段**，永不共用）。 */
enum class MissionOutcome {
    SUCCESS,
    FAILED,
    UNKNOWN
}

/** 终局原因。 */
enum class WinReason {
    /** 3 次任务失败 */
    THREE_MISSIONS_FAILED,

    /** 同一任务 5 次连续否决 */
    FIVE_CONSECUTIVE_REJECTS,

    /** 刺客命中梅林 */
    ASSASSIN_HIT_MERLIN,

    /** 刺客刺错 */
    ASSASSIN_MISSED,

    /** 规则包无刺客，3 次成功后好人直接胜（ARCHITECTURE §9.3-5） */
    NO_ASSASSIN_PRESENT,
    ABANDONED,
    UNKNOWN
}

/** 公开发言 / 声称的类型。Claim 永远不是事实。 */
enum class ClaimType {
    /** 怀疑 */
    SUSPECT,

    /** 保护 / 信任 */
    PROTECT,

    /** 跳身份（= Claim，不是事实） */
    ROLE_CLAIM,

    /** 对跳 */
    COUNTER_CLAIM,

    /** 推荐组队 */
    TEAM_RECOMMEND,

    /** 改站边 */
    STANCE_CHANGE,

    /** 关键言论 */
    KEY_STATEMENT,
    OTHER
}

/** 主观判断类型。 */
enum class BeliefType {
    SUSPECT,
    TRUST,
    ROLE_GUESS,
    OTHER
}

/** 事件可见性。`PRIVATE_OBSERVER` 仅记录者本人可见。 */
enum class EventVisibility {
    PUBLIC,
    PRIVATE_OBSERVER
}

/** 复盘视角。 */
enum class Perspective {
    PUBLIC,
    OBSERVER_AT_TIME,
    GOD_AFTER_REVEAL
}

/** 结论的确定性等级。 */
enum class EvidenceLevel {
    RULE_CERTAIN,
    OBSERVER_CERTAIN,
    INFERENCE,
    UNKNOWN
}

/** 事件类型（与 sealed 子类一一对应，供 DAO 列与 UI 分派）。 */
enum class EventType {
    QUEST_STARTED,
    TEAM_PROPOSED,
    TEAM_VOTE_RECORDED,
    TEAM_PROPOSAL_RESOLVED,
    MISSION_RESOLVED,
    PLAYER_CLAIMED,
    PRIVATE_NOTE_ADDED,
    ASSASSINATION_RECORDED,
    MATCH_ABANDONED,
    MATCH_FINALIZED
}
