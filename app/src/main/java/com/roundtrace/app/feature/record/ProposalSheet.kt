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
import com.roundtrace.app.core.ui.component.SeatPicker
import com.roundtrace.app.core.ui.theme.ButtonShape
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.LocalExtendedColors

/**
 * 提案录入 Sheet（TASKS.md T46）。
 *
 * 三条要求：
 * 1. 单选队长 + 多选队员，复用 [SeatPicker]（T46①）；
 * 2. 实时显示「已选 3/4 人」与任务人数规则（T46②）；
 * 3. 允许「只选部分成员后暂存」——暂存走草稿，**不产生非法确认事件**（T46⑤）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProposalSheet(
    questNo: Int,
    expectedTeamSize: Int,
    playerCount: Int,
    leaderSeat: Int?,
    teamSeats: Set<Int>,
    canSubmit: Boolean,
    onSelectLeader: (Int) -> Unit,
    onToggleTeamSeat: (Int) -> Unit,
    onSaveDraft: () -> Unit,
    onSubmit: () -> Unit,
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
                text = stringResource(R.string.proposal_sheet_title, questNo),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            // —— 队长 ——
            Text(
                text = stringResource(R.string.proposal_sheet_leader_section),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SeatPicker(
                playerCount = playerCount,
                selectedSeats = leaderSeat?.let { setOf(it) } ?: emptySet(),
                onSeatClick = onSelectLeader,
                singleSelect = true,
                badges = leaderSeat?.let { mapOf(it to stringResource(R.string.seat_role_leader)) }
                    ?: emptyMap()
            )

            // —— 队员 ——
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.proposal_sheet_team_section),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(
                        R.string.record_team_count,
                        teamSeats.size,
                        expectedTeamSize
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (teamSeats.size == expectedTeamSize) {
                        LocalExtendedColors.current.success
                    } else {
                        LocalExtendedColors.current.accent
                    }
                )
            }
            SeatPicker(
                playerCount = playerCount,
                selectedSeats = teamSeats,
                onSeatClick = onToggleTeamSeat,
                singleSelect = false
            )

            if (!canSubmit) {
                Text(
                    text = stringResource(R.string.proposal_sheet_blocked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing12)
            ) {
                OutlinedButton(
                    onClick = onSaveDraft,
                    shape = ButtonShape,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = Dimens.MinTouchTarget)
                ) {
                    Text(text = stringResource(R.string.proposal_sheet_save_draft))
                }
                Button(
                    onClick = onSubmit,
                    enabled = canSubmit,
                    shape = ButtonShape,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = Dimens.MinTouchTarget)
                ) {
                    Text(text = stringResource(R.string.proposal_sheet_submit))
                }
            }
        }
    }
}
