package com.roundtrace.app.core.model

/**
 * 信息视角模型（ARCHITECTURE §7.1 / TASKS.md T50）。
 *
 * 这一层是「反剧透」的**数据契约**：三个视角（公开 / 当时 / 上帝）能拿到什么，
 * 由这里的类型表达能力决定，而不是靠 UI 层的自觉。
 *
 * 纯 Kotlin：禁止 import android.* / androidx.*。
 */

/**
 * 开局夜间知识（规则产物，纯数据，M1 已有）。
 *
 * @param observerSeat 观察座位
 * @param role 该座位角色
 * @param visibleEvilSeats 梅林可见的坏人座位（不含莫德雷德 / 奥伯伦）
 * @param merlinCandidates 派西维尔的梅林候选
 * @param evilTeammates 坏人互认结果（不含奥伯伦与自身）
 * @param notes 稳定的规则键，由 UI 映射为文案（D6：领域层不拼 UI 文案）
 */
data class NightKnowledge(
    val observerSeat: SeatId,
    val role: RoleId,
    val visibleEvilSeats: List<SeatId>,
    val merlinCandidates: List<SeatId>,
    val evilTeammates: List<SeatId>,
    val notes: List<String> = emptyList()
) {

    /** 是否有任何夜间知识。 */
    val isEmpty: Boolean
        get() = visibleEvilSeats.isEmpty() && merlinCandidates.isEmpty() && evilTeammates.isEmpty()
}

/**
 * 某个时间点 + 某个视角下的完整信息快照。
 *
 * **硬约束**：`privateRoleFacts` / `subjectiveBeliefs` / `seatKnowledge` 的内容
 * 由 [mode] 与 [at] 共同决定，调用方不得在拿到快照后自行「补全」真相。
 *
 * @param observerRole 该视角下观察者的角色（可为 `UNKNOWN`）
 * @param observerRoleIsReconstructed `true` = 角色是**结局后补录**的，
 *        当前知识是「按规则重建的初始信息」，而不是「本人当时真的知道」。
 *        **UI 必须把这两种表述区分开**（TDD §5.2 第 3 条）。
 * @param canOpenGodView 是否允许进入上帝视角（数据条件，非状态条件）
 */
data class KnowledgeSnapshot(
    val at: GamePosition,
    val mode: Perspective,
    val observerSeat: SeatId?,
    val observerRole: RoleId?,
    val observerRoleIsReconstructed: Boolean,
    val publicFacts: List<PublicFact>,
    val privateRoleFacts: List<PrivateFact>,
    val subjectiveBeliefs: List<Belief>,
    val seatKnowledge: Map<SeatId, SeatKnowledge>,
    val findings: List<EvidenceFinding>,
    val unknowns: List<UnknownItem>,
    val canOpenGodView: Boolean,
    /**
     * 规则矩阵快照（分析器算「队伍里至少几个坏人」需要）。
     *
     * 之所以放进快照：`ReplayAnalyzer.analyze(snapshot, history)` 的签名由
     * ARCHITECTURE §3 固定，没有 `config` 参数；而 bounds 推导必须知道
     * 该人数下的好人/坏人总数。放这里比让分析器持有可变状态安全。
     */
    val playerCount: Int = 0,
    val goodCount: Int = 0,
    val evilCount: Int = 0
) {

    /** 该视角下是否存在「信息不足」项（UI 显示「信息不足」）。 */
    val hasUnknowns: Boolean
        get() = unknowns.isNotEmpty()
}

/** 公开事实（谁都能看到、且当时确实发生过的事）。 */
data class PublicFact(
    val id: String,
    val kind: FactKind,
    val questNo: Int?,
    val proposalNo: Int?,
    val occurredOrder: Int,
    /** 结构化摘要（含座位号 / 票数 / 失败牌数），UI 直接展示 */
    val summary: String,
    val sourceEventIds: List<EventId>
)

/** 公开事实的种类。 */
enum class FactKind {
    TEAM_ANNOUNCED,
    VOTE_RESULT,
    MISSION_RESULT,
    CLAIM,
    ASSASSINATION,
    OTHER
}

/**
 * 私有关系事实：**只有 [subjectSeat] 这个人**能掌握的信息。
 *
 * 注意这里是「关系」而不是「结论」：例如派西维尔拿到的是
 * `MERLIN_CANDIDATE`（两个候选），而不是「1 号就是梅林」。
 */
data class PrivateFact(
    val id: String,
    /** 掌握该信息的人 */
    val subjectSeat: SeatId,
    /** 信息指向谁 */
    val targetSeat: SeatId,
    val relation: PrivateRelation,
    val basis: KnowledgeBasis,
    val sourceEventIds: List<EventId> = emptyList()
)

/** 私有关系的种类。 */
enum class PrivateRelation {
    /** 确定是坏人（梅林夜间所见） */
    KNOWN_EVIL,

    /** 确定自己是好人（仅对本人） */
    KNOWN_GOOD_SELF,

    /** 梅林候选（派西维尔：可能是梅林，也可能是莫甘娜） */
    MERLIN_CANDIDATE,

    /** 坏人同伴（互认） */
    EVIL_TEAMMATE
}

/**
 * 知识的**来源**（TASKS.md T51 / 主理人「知识来源边界」）。
 *
 * 这个枚举是「区分表述」的机器可读依据：
 * UI 用 [RULESET_INITIAL] / [OBSERVER_OWN_ROLE] 说明「你当时会知道」，
 * 用 [RECONSTRUCTED_INITIAL] 说明「这是按规则重建的初始信息」。
 */
enum class KnowledgeBasis {
    /** 开局夜间规则直接给的（角色当时已知） */
    RULESET_INITIAL,

    /** 观察者自己的角色（自己当然知道自己） */
    OBSERVER_OWN_ROLE,

    /** 从公开事件推出的 */
    PUBLIC_EVENT,

    /** 角色是结局后补录的 —— 只能「按规则重建」当时本该看到什么 */
    RECONSTRUCTED_INITIAL
}

/** 主观判断（私人笔记）。**永远不是事实**，即使后来猜对了。 */
data class Belief(
    val id: String,
    val observerSeat: SeatId,
    val beliefType: BeliefType,
    val targetSeats: List<SeatId>,
    val text: String?,
    /** 该判断从哪个位置开始可见（回填历史时控制「不提前显示」） */
    val availableFromOrder: Int,
    val sourceEventIds: List<EventId>
)

/**
 * 该视角下对某个座位的认知。
 *
 * @param knownAlignment 该视角下**确定**的阵营；`null` = 未知
 *        （刻意不用 `UNKNOWN` 表示未知，避免与「已知是 UNKNOWN 阵营」混淆）
 * @param visibleRole 该视角下可见/已揭晓的角色
 * @param candidateRoles 候选角色（派西维尔的梅林候选等），**不代表确定**
 */
data class SeatKnowledge(
    val seat: SeatId,
    val knownAlignment: Alignment?,
    val visibleRole: RoleId?,
    val candidateRoles: List<RoleId>,
    val labels: Set<KnowledgeLabel>,
    val evidenceIds: List<EventId> = emptyList()
) {

    /** 是否完全未知（UI 显示「信息不足」）。 */
    val isUnknown: Boolean
        get() = knownAlignment == null && visibleRole == null && candidateRoles.isEmpty()
}

/** 座位标签（来自公开声称，不代表真实身份）。 */
enum class KnowledgeLabel {
    SELF,
    CLAIMED_MERLIN,
    CLAIMED_PERCIVAL,
    SUSPECTED,
    TRUSTED
}

/** 尚不确定的事项（UI 显示「信息不足」+ 具体缺什么）。 */
data class UnknownItem(
    /** 稳定的规则键，UI 映射为文案 */
    val code: String,
    val questNo: Int? = null,
    val seats: List<SeatId> = emptyList()
)

/**
 * 一条证据结论（ARCHITECTURE §7.1 / §7.5）。
 *
 * 硬约束（主理人「输出纪律」）：
 * 1. [level] 只能是四档之一，**「推测」必须与「规则确定」在 UI 上不同色形**；
 * 2. [ruleId] 必须有（如 `A05_TEAM_CONTAINS_EVIL`）；
 * 3. [sourceEventIds] 为空时只能是 [EvidenceLevel.INFERENCE]，且 explanation 必须说明「无直接证据」；
 * 4. **禁止输出数值概率**。
 */
data class EvidenceFinding(
    val id: String,
    /** 一句话结论 */
    val label: String,
    val level: EvidenceLevel,
    /** 展开解释（含数字与座位号） */
    val explanation: String,
    val ruleId: String,
    val sourceEventIds: List<EventId>,
    val relatedSeats: List<SeatId> = emptyList(),
    val relatedQuestNo: Int? = null
) {

    /** 是否属于「纠错类」结论（防止用户从公开事实推出过度结论）。 */
    val isCorrection: Boolean
        get() = ruleId.startsWith("A04") || ruleId.startsWith("A07") || ruleId.startsWith("A08")
}
