package com.roundtrace.app.core.model

/**
 * 规则校验问题（ARCHITECTURE §2.4）。
 *
 * 硬约束（D6）：领域层**不拼 UI 文案**。本对象只携带 [code] 与 [args] 占位参数，
 * 由 `core/ui/IssueText.kt` 映射为 `stringResource`。
 *
 * @param args 占位参数（如 `expected=4`、`actual=3`），UI 用 `stringResource` 填充
 */
data class RuleIssue(
    val code: IssueCode,
    val severity: Severity,
    val questNo: Int? = null,
    val proposalNo: Int? = null,
    val seats: List<SeatId> = emptyList(),
    val eventId: EventId? = null,
    val args: Map<String, String> = emptyMap()
) {

    /** 是否阻断「确认」动作（草稿仍可保存，见 ARCHITECTURE §9.3-3 与 R13）。 */
    val isBlocking: Boolean
        get() = severity == Severity.BLOCKING

    enum class Severity {
        BLOCKING,
        WARNING,
        INFO
    }
}

/**
 * 规则问题码全集（≥28 个，覆盖 ARCHITECTURE §2.4）。
 *
 * 命名分四组，前缀即归属域，便于 UI 分组展示与测试断言。
 */
enum class IssueCode {

    // —— 配置 ——
    PLAYER_COUNT_OUT_OF_RANGE,
    ROLE_COUNT_MISMATCH,
    ALIGNMENT_COUNT_MISMATCH,
    ROLE_UNIQUENESS_VIOLATED,
    ROLE_SLOT_NEGATIVE,
    LEADER_SEAT_INVALID,
    MY_SEAT_INVALID,

    // —— 提案 ——
    TEAM_SIZE_MISMATCH,
    DUPLICATE_SEAT,
    SEAT_OUT_OF_RANGE,
    PROPOSAL_NO_OUT_OF_ORDER,
    PROPOSAL_AFTER_RESOLVED,

    // —— 投票 ——
    VOTE_COUNT_EXCEEDS_PLAYERS,
    VOTE_SUM_MISMATCH,
    DECISION_VOTE_CONFLICT,
    VOTE_DECISION_UNDECIDABLE,

    // —— 任务 ——
    MISSION_WITHOUT_APPROVED_PROPOSAL,
    FAIL_COUNT_EXCEEDS_TEAM,
    FAIL_COUNT_OUTCOME_CONFLICT,
    MISSION_TEAM_MISMATCH,
    MISSION_ALREADY_RESOLVED,

    // —— 队长 / 否决 ——
    LEADER_NOT_EXPECTED,
    REJECTION_STREAK_EXCEEDED,

    // —— 刺杀 / 终局 ——
    ASSASSINATION_NOT_ALLOWED,
    UNKNOWN_ROLE_FOR_OUTCOME,
    FINALIZED_CONFLICT,

    // —— 通用 ——
    QUEST_OUT_OF_ORDER,
    EVENT_AFTER_MATCH_FINISHED,
    DATA_INCOMPLETE
}
