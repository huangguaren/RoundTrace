package com.roundtrace.app.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.roundtrace.app.R
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.TeamVoteChoice
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.PillShape
import com.roundtrace.app.core.ui.theme.SeatShape

/** 投票录入模式（DESIGN_SPEC §4.5）。 */
enum class VoteRecorderMode {
    PER_SEAT,
    SUMMARY
}

/**
 * 组队投票录入（DESIGN_SPEC §4.5 / TASKS.md T47）。
 *
 * **这是「组队投票」组件，不是任务结果组件**：
 * 全组件内只出现「同意 / 反对 / 未知」三个词，绝不出现「失败牌」（T47⑤）。
 * 任务失败牌由 [MissionOutcomeSelector] 负责，两者视觉与措辞完全不同（PRD AC-02）。
 *
 * 判定结果 [decision] 由调用方用 `AvalonRules.decideProposal` 算好传入，
 * 本组件**不重新实现规则**。
 *
 * @param halfCount 半数票（`playerCount / 2`），用于展示「赞成 7 > 半数 4」
 * @param exceedsPlayers 赞成 + 反对是否已超过人数（超过则禁止保存）
 */
@Composable
fun VoteRecorder(
    playerCount: Int,
    votesBySeat: Map<Int, TeamVoteChoice>,
    approveCount: Int?,
    rejectCount: Int?,
    decision: ProposalDecision,
    halfCount: Int,
    exceedsPlayers: Boolean,
    onSeatVote: (Int, TeamVoteChoice) -> Unit,
    onSummaryChange: (Int?, Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    var mode by rememberSaveable { mutableStateOf(VoteRecorderMode.PER_SEAT) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.Spacing12)
    ) {
        // —— 模式切换 ——
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VoteRecorderMode.entries.forEach { candidate ->
                val label = when (candidate) {
                    VoteRecorderMode.PER_SEAT -> stringResource(R.string.vote_mode_per_seat)
                    VoteRecorderMode.SUMMARY -> stringResource(R.string.vote_mode_summary)
                }
                FilterChip(
                    selected = mode == candidate,
                    onClick = { mode = candidate },
                    label = { Text(text = label, style = MaterialTheme.typography.labelMedium) },
                    shape = PillShape,
                    modifier = Modifier.heightIn(min = Dimens.MinTouchTarget),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        when (mode) {
            VoteRecorderMode.PER_SEAT -> PerSeatVotes(
                playerCount = playerCount,
                votesBySeat = votesBySeat,
                onSeatVote = onSeatVote
            )

            VoteRecorderMode.SUMMARY -> SummaryVotes(
                playerCount = playerCount,
                approveCount = approveCount,
                rejectCount = rejectCount,
                onSummaryChange = onSummaryChange
            )
        }

        VoteLiveResult(
            approveCount = approveCount ?: votesBySeat.values.count { it == TeamVoteChoice.APPROVE },
            rejectCount = rejectCount ?: votesBySeat.values.count { it == TeamVoteChoice.REJECT },
            halfCount = halfCount,
            decision = decision
        )

        if (exceedsPlayers) {
            Text(
                text = stringResource(
                    R.string.vote_exceeds_players,
                    (approveCount ?: 0) + (rejectCount ?: 0),
                    playerCount
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** 逐人模式：座位网格 + 三态循环。 */
@Composable
private fun PerSeatVotes(
    playerCount: Int,
    votesBySeat: Map<Int, TeamVoteChoice>,
    onSeatVote: (Int, TeamVoteChoice) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
        (1..playerCount).chunked(SEATS_PER_ROW).forEach { rowSeats ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
            ) {
                rowSeats.forEach { seat ->
                    val vote = votesBySeat[seat] ?: TeamVoteChoice.UNKNOWN
                    VoteSeatCell(
                        seat = seat,
                        vote = vote,
                        onClick = { onSeatVote(seat, nextVote(vote)) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(SEATS_PER_ROW - rowSeats.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun VoteSeatCell(
    seat: Int,
    vote: TeamVoteChoice,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val label = when (vote) {
        TeamVoteChoice.APPROVE -> stringResource(R.string.vote_choice_approve)
        TeamVoteChoice.REJECT -> stringResource(R.string.vote_choice_reject)
        TeamVoteChoice.UNKNOWN -> stringResource(R.string.vote_choice_unknown)
    }
    val container = when (vote) {
        TeamVoteChoice.APPROVE -> scheme.primaryContainer
        TeamVoteChoice.REJECT -> scheme.errorContainer
        TeamVoteChoice.UNKNOWN -> scheme.surfaceVariant
    }
    val content = when (vote) {
        TeamVoteChoice.APPROVE -> scheme.onPrimaryContainer
        TeamVoteChoice.REJECT -> scheme.onErrorContainer
        TeamVoteChoice.UNKNOWN -> scheme.onSurfaceVariant
    }
    val description = stringResource(R.string.vote_seat_description, seat, label)

    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = Dimens.SeatMinSize)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        shape = SeatShape,
        color = container,
        contentColor = content
    ) {
        Column(
            modifier = Modifier.padding(Dimens.Spacing4),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = seat.toString(),
                style = MaterialTheme.typography.displaySmall,
                color = content
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = content
            )
        }
    }
}

/** 汇总模式：两个数字步进器，剩余自动算未知（T47①）。 */
@Composable
private fun SummaryVotes(
    playerCount: Int,
    approveCount: Int?,
    rejectCount: Int?,
    onSummaryChange: (Int?, Int?) -> Unit
) {
    val approve = approveCount ?: 0
    val reject = rejectCount ?: 0
    val unknown = (playerCount - approve - reject).coerceAtLeast(0)

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
        NumberStepper(
            label = stringResource(R.string.vote_summary_approve, approve),
            onDelta = { delta ->
                val next = (approve + delta).coerceIn(0, playerCount - reject)
                onSummaryChange(next, reject)
            },
            canDecrease = approve > 0,
            canIncrease = approve + reject < playerCount
        )
        NumberStepper(
            label = stringResource(R.string.vote_summary_reject, reject),
            onDelta = { delta ->
                val next = (reject + delta).coerceIn(0, playerCount - approve)
                onSummaryChange(approve, next)
            },
            canDecrease = reject > 0,
            canIncrease = approve + reject < playerCount
        )
        Text(
            text = stringResource(R.string.vote_summary_unknown, unknown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NumberStepper(
    label: String,
    onDelta: (Int) -> Unit,
    canDecrease: Boolean,
    canIncrease: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = { onDelta(-1) },
            enabled = canDecrease,
            modifier = Modifier.size(Dimens.MinTouchTarget)
        ) {
            Icon(
                imageVector = Icons.Filled.Remove,
                contentDescription = "减少",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(
            onClick = { onDelta(1) },
            enabled = canIncrease,
            modifier = Modifier.size(Dimens.MinTouchTarget)
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "增加",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 实时判定文案：只展示，不计算（结论来自 `AvalonRules.decideProposal`）。 */
@Composable
private fun VoteLiveResult(
    approveCount: Int,
    rejectCount: Int,
    halfCount: Int,
    decision: ProposalDecision
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing4)) {
        Text(
            text = stringResource(R.string.vote_half_line, approveCount, halfCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val (text, badge) = when (decision) {
            ProposalDecision.APPROVED -> stringResource(
                R.string.vote_result_approved,
                approveCount,
                halfCount
            ) to BadgeState.PENDING

            ProposalDecision.REJECTED -> {
                // 平票与「赞成不足」在文案上区分开：平票必须明确提示「视为否决」
                val label = if (approveCount == halfCount && rejectCount == halfCount) {
                    stringResource(R.string.vote_result_tie)
                } else {
                    stringResource(R.string.vote_result_rejected, approveCount, halfCount)
                }
                label to BadgeState.FAILURE
            }

            ProposalDecision.UNKNOWN -> stringResource(R.string.vote_result_unknown) to
                BadgeState.UNKNOWN
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusBadge(state = badge)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** 三态循环：同意 → 反对 → 未知 → 同意。 */
fun nextVote(current: TeamVoteChoice): TeamVoteChoice = when (current) {
    TeamVoteChoice.UNKNOWN -> TeamVoteChoice.APPROVE
    TeamVoteChoice.APPROVE -> TeamVoteChoice.REJECT
    TeamVoteChoice.REJECT -> TeamVoteChoice.UNKNOWN
}

/** 一行 5 列（与 `SeatPicker` 保持一致）。 */
private const val SEATS_PER_ROW = 5
