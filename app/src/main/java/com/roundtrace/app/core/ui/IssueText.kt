package com.roundtrace.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.roundtrace.app.R
import com.roundtrace.app.core.model.IssueCode
import com.roundtrace.app.core.model.RuleIssue

/**
 * `IssueCode` → UI 文案（D6：领域层不拼 UI 文案，由本文件统一映射）。
 *
 * 全部走 `stringResource`，保证 i18n 与占位参数由资源文件统一管理。
 * `RuleIssue.args` 里的值是**字符串**（领域层不关心展示格式），这里负责取用与拼接。
 */
@Composable
fun issueText(issue: RuleIssue): String {
    val args = issue.args
    return when (issue.code) {
        IssueCode.PLAYER_COUNT_OUT_OF_RANGE -> stringResource(
            R.string.issue_player_count_out_of_range,
            args["min"] ?: "?",
            args["max"] ?: "?",
            args["actual"] ?: "?"
        )

        IssueCode.ROLE_COUNT_MISMATCH -> stringResource(
            R.string.issue_role_count_mismatch,
            args["expected"] ?: "?",
            args["actual"] ?: "?"
        )

        IssueCode.ALIGNMENT_COUNT_MISMATCH -> stringResource(
            R.string.issue_alignment_count_mismatch,
            args["expectedGood"] ?: "?",
            args["expectedEvil"] ?: "?",
            args["actualGood"] ?: "?",
            args["actualEvil"] ?: "?"
        )

        IssueCode.ROLE_UNIQUENESS_VIOLATED -> stringResource(
            R.string.issue_role_uniqueness_violated,
            roleLabel(args["role"] ?: "?"),
            args["count"] ?: "?"
        )

        IssueCode.ROLE_SLOT_NEGATIVE -> stringResource(
            R.string.issue_role_slot_negative,
            args["roles"] ?: "?"
        )

        IssueCode.LEADER_SEAT_INVALID -> stringResource(
            R.string.issue_leader_seat_invalid,
            args["seat"] ?: "?",
            args["max"] ?: "?"
        )

        IssueCode.MY_SEAT_INVALID -> stringResource(
            R.string.issue_my_seat_invalid,
            args["seat"] ?: "?",
            args["max"] ?: "?"
        )

        IssueCode.TEAM_SIZE_MISMATCH -> stringResource(
            R.string.issue_team_size_mismatch,
            args["expected"] ?: "?",
            args["actual"] ?: "?"
        )

        IssueCode.DUPLICATE_SEAT -> stringResource(
            R.string.issue_duplicate_seat,
            args["seats"] ?: "?"
        )

        IssueCode.SEAT_OUT_OF_RANGE -> stringResource(
            R.string.issue_seat_out_of_range,
            args["seats"] ?: "?"
        )

        IssueCode.PROPOSAL_NO_OUT_OF_ORDER -> stringResource(
            R.string.issue_proposal_no_out_of_order,
            args["proposalNo"] ?: "?",
            args["currentProposalNo"] ?: "?"
        )

        IssueCode.PROPOSAL_AFTER_RESOLVED -> stringResource(R.string.issue_proposal_after_resolved)

        IssueCode.VOTE_COUNT_EXCEEDS_PLAYERS -> stringResource(
            R.string.issue_vote_count_exceeds_players,
            args["approve"] ?: "?",
            args["reject"] ?: "?",
            args["players"] ?: "?"
        )

        IssueCode.VOTE_SUM_MISMATCH -> stringResource(R.string.issue_vote_sum_mismatch)

        IssueCode.DECISION_VOTE_CONFLICT -> stringResource(
            R.string.issue_decision_vote_conflict,
            args["recorded"] ?: "?",
            args["computed"] ?: "?"
        )

        IssueCode.VOTE_DECISION_UNDECIDABLE -> stringResource(R.string.issue_vote_decision_undecidable)

        IssueCode.MISSION_WITHOUT_APPROVED_PROPOSAL -> stringResource(
            R.string.issue_mission_without_approved_proposal
        )

        IssueCode.FAIL_COUNT_EXCEEDS_TEAM -> stringResource(
            R.string.issue_fail_count_exceeds_team,
            args["failCount"] ?: "?",
            args["teamSize"] ?: "?"
        )

        IssueCode.FAIL_COUNT_OUTCOME_CONFLICT -> stringResource(
            R.string.issue_fail_count_outcome_conflict,
            args["derived"] ?: "?",
            args["observed"] ?: "?"
        )

        IssueCode.MISSION_TEAM_MISMATCH -> stringResource(
            R.string.issue_mission_team_mismatch,
            args["approved"] ?: "?",
            args["recorded"] ?: "?"
        )

        IssueCode.MISSION_ALREADY_RESOLVED -> stringResource(R.string.issue_mission_already_resolved)

        IssueCode.LEADER_NOT_EXPECTED -> stringResource(
            R.string.issue_leader_not_expected,
            args["expected"] ?: "?",
            args["actual"] ?: "?"
        )

        IssueCode.REJECTION_STREAK_EXCEEDED -> stringResource(
            R.string.issue_rejection_streak_exceeded,
            args["count"] ?: "?"
        )

        IssueCode.ASSASSINATION_NOT_ALLOWED -> stringResource(R.string.issue_assassination_not_allowed)

        IssueCode.UNKNOWN_ROLE_FOR_OUTCOME -> stringResource(
            R.string.issue_unknown_role_for_outcome,
            args["seat"] ?: "?"
        )

        IssueCode.FINALIZED_CONFLICT -> stringResource(R.string.issue_finalized_conflict)

        IssueCode.QUEST_OUT_OF_ORDER -> stringResource(
            R.string.issue_quest_out_of_order,
            args["questNo"] ?: "?"
        )

        IssueCode.EVENT_AFTER_MATCH_FINISHED -> stringResource(R.string.issue_event_after_match_finished)

        IssueCode.DATA_INCOMPLETE -> stringResource(R.string.issue_data_incomplete)
    }
}

/** 角色名中文标签；未知角色回退到原始 key（不猜）。 */
@Composable
private fun roleLabel(raw: String): String = when (raw) {
    "MERLIN" -> stringResource(R.string.role_merlin)
    "PERCIVAL" -> stringResource(R.string.role_percival)
    "LOYAL_SERVANT" -> stringResource(R.string.role_loyal_servant)
    "MORGANA" -> stringResource(R.string.role_morgana)
    "ASSASSIN" -> stringResource(R.string.role_assassin)
    "MORDRED" -> stringResource(R.string.role_mordred)
    "MINION" -> stringResource(R.string.role_minion)
    "OBERON" -> stringResource(R.string.role_oberon)
    else -> raw
}

/** 角色名（供其它 UI 复用）。 */
@Composable
fun roleDisplayName(roleId: com.roundtrace.app.core.model.RoleId): String = roleLabel(roleId.name)
