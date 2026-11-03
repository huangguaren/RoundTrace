package com.roundtrace.app.core.replay

import com.roundtrace.app.core.model.EventId

/**
 * 玩家行为时间线的展示模型（F05-01）。
 *
 * 一条 [PlayerTraceItem] = 「某玩家在某一时刻被记录下来的一个参与事实」。
 * 它**不是**结论、不是推测、更不是身份判定 —— 身份判定在证据区（`EvidenceCard`）里，
 * 两者物理分开，避免用户把「被提名上车」读成「实际参与任务」。
 *
 * @param sourceEventId 唯一来源事件（点击可跳转；同一事件可产出多条不同关系的记录）
 * @param occurredOrder 在时间线上的**定位点**。普通事件即 `occurredOrder`；
 *        私人笔记取 `availableFromOrder`（decisions B31 的权威可见时刻），
 *        否则回填笔记会指向一个晚于当前复盘位置的事件，点过去等于把时钟拨到未来。
 * @param questNo 所属任务；准备阶段为 `null`
 * @param category 记录类别（队长 / 被提名 / 任务队员 / 个人投票 / 发言 / 被提及 / 私人判断）
 * @param relationship 该记录与被查看玩家的**关系**（本人做的 / 本人被卷入的）
 * @param title 一行可扫读的摘要
 * @param detail 明细（含「不得推断」这类边界声明）
 * @param factLevel 事实等级，见 [PlayerTraceFactLevel]
 */
data class PlayerTraceItem(
    val sourceEventId: EventId,
    val occurredOrder: Int,
    val questNo: Int?,
    val category: PlayerTraceCategory,
    val relationship: PlayerTraceRelationship,
    val title: String,
    val detail: String,
    val factLevel: PlayerTraceFactLevel
)

/**
 * 记录类别。**类别与关系是两件事**：
 * 类别回答「这是哪类记录」，关系回答「和这个人是什么关系」。
 *
 * 之所以不合并成一个枚举：同一类别在不同关系下文案与排序都不同，
 * 例如「被提名上车」与「实际任务队员」都是队伍相关，但绝对不能混成一句话。
 */
enum class PlayerTraceCategory(val displayName: String) {
    LEADER("队长"),
    NOMINATED("被提名上车"),
    MISSION_MEMBER("任务队员"),
    PERSONAL_VOTE("个人投票"),
    SPEAKER("发言"),
    MENTIONED("被提及"),
    PRIVATE_NOTE("私人判断")
}

/** 记录与被查看玩家的关系（「谁做了什么」的方向）。 */
enum class PlayerTraceRelationship(val displayName: String) {
    /** 本人被记录为队长（开始任务）。 */
    LED_QUEST("本人开启任务"),

    /** 本人被记录为提案者（提出队伍）。 */
    PROPOSED_TEAM("本人提出队伍"),

    /** 本人被提议进入某次提案的队伍（≠ 实际执行任务）。 */
    ON_PROPOSED_TEAM("本人被提议上车"),

    /** 本人被记录为该任务的队员（可能未核实）。 */
    ON_MISSION_TEAM("本人记录为任务队员"),

    /** 本人的个人组队投票（仅逐人票可归属）。 */
    CAST_VOTE("本人投票"),

    /** 本人发言 / 声称。 */
    SPOKE("本人发言"),

    /** 本人被他人发言提及。 */
    NAMED_BY_OTHER("本人被提及"),

    /** 本人的私人判断（仅合法观察者可见）。 */
    OWN_BELIEF("本人私人判断")
}

/**
 * 事实等级 —— 决定「能不能当真」。
 *
 * - [RECORDED]：事件明确记录了该字段；
 * - [UNKNOWN]：事件存在，但该字段被记录为「未知」（例如逐人票选了「未知」）；
 * - [INFERRED]：从记录推导出来的东西。**当前实现永不产出**，
 *   保留这个枚举值是为了让「推测」在类型上无处遁形：任何想加推测的人必须显式选它，
 *   而 `PlayerTraceBuilderTest.builderNeverEmitsInferredItems` 会当场拦下。
 */
enum class PlayerTraceFactLevel(val displayName: String) {
    RECORDED("记录事实"),
    UNKNOWN("记录为未知"),
    INFERRED("推测")
}
