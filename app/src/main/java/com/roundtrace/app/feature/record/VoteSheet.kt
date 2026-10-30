package com.roundtrace.app.feature.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.roundtrace.app.R
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.TeamVoteChoice
import com.roundtrace.app.core.ui.component.VoteRecorder
import com.roundtrace.app.core.ui.theme.ButtonShape
import com.roundtrace.app.core.ui.theme.Dimens

/**
 * 投票录入 Sheet（TASKS.md T47）。
 *
 * 包装 [VoteRecorder]：本 Sheet 只负责「标题 + 保存 + 决议」，
 * 具体录入交互（逐人/汇总双模式）全部在组件里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoteSheet(
    questNo: Int,
    proposalNo: Int,
    playerCount: Int,
    votesBySeat: Map<Int, TeamVoteChoice>,
    approveCount: Int?,
    rejectCount: Int?,
    decision: ProposalDecision,
    halfCount: Int,
    exceedsPlayers: Boolean,
    onSeatVote: (Int, TeamVoteChoice) -> Unit,
    onSummaryChange: (Int?, Int?) -> Unit,
    onSaveVotes: () -> Unit,
    onDecision: (ProposalDecision) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.PageHorizontalPadding)
                .padding(bottom = Dimens.Spacing24),
            verticalArrangement = Arrangement.spacedBy(Dimens.Spacing16)
        ) {
            Text(
                text = stringResource(R.string.vote_sheet_title, questNo, proposalNo),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            VoteRecorder(
                playerCount = playerCount,
                votesBySeat = votesBySeat,
                approveCount = approveCount,
                rejectCount = rejectCount,
                decision = decision,
                halfCount = halfCount,
                exceedsPlayers = exceedsPlayers,
                onSeatVote = onSeatVote,
                onSummaryChange = onSummaryChange
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing12)
            ) {
                OutlinedButton(
                    onClick = onSaveVotes,
                    enabled = !exceedsPlayers,
                    shape = ButtonShape,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = Dimens.MinTouchTarget)
                ) {
                    Text(text = stringResource(R.string.vote_save))
                }
                Button(
                    onClick = { onDecision(ProposalDecision.APPROVED) },
                    enabled = !exceedsPlayers,
                    shape = ButtonShape,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = Dimens.MinTouchTarget)
                ) {
                    Text(text = stringResource(R.string.vote_decision_approved))
                }
                Button(
                    onClick = { onDecision(ProposalDecision.REJECTED) },
                    enabled = !exceedsPlayers,
                    shape = ButtonShape,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = Dimens.MinTouchTarget)
                ) {
                    Text(text = stringResource(R.string.vote_decision_rejected))
                }
            }

            Text(
                text = stringResource(R.string.vote_decision_label) + "：" +
                    when (decision) {
                        ProposalDecision.APPROVED -> stringResource(R.string.vote_decision_approved)
                        ProposalDecision.REJECTED -> stringResource(R.string.vote_decision_rejected)
                        ProposalDecision.UNKNOWN -> stringResource(R.string.badge_unknown)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
