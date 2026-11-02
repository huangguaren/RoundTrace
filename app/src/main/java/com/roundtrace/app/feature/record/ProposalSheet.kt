package com.roundtrace.app.feature.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
    isSaving: Boolean = false,
    isEditing: Boolean = false,
    seatLabels: Map<Int, String?> = emptyMap(),
    seatAvatars: Map<Int, String?> = emptyMap(),
    onSelectLeader: (Int?) -> Unit,
    onToggleTeamSeat: (Int) -> Unit,
    onSaveDraft: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    // 队长身份和上车名单相互独立。已有队长时，只有显式进入更换模式才改变身份。
    var changingLeader by rememberSaveable { mutableStateOf(false) }
    val selectingLeader = leaderSeat == null || changingLeader

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.PageHorizontalPadding)
                .padding(bottom = Dimens.Spacing24),
            verticalArrangement = Arrangement.spacedBy(Dimens.Spacing16)
        ) {
            Text(
                text = (if (isEditing) stringResource(R.string.record_mode_edit) + " · " else "") +
                    stringResource(R.string.proposal_sheet_title, questNo),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            // 星标表示队长，选中底色表示上车；更换队长不增删任何队员。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (leaderSeat == null) {
                        stringResource(R.string.record_leader_unset)
                    } else {
                        stringResource(R.string.record_leader_label, leaderSeat)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                if (leaderSeat != null) {
                    TextButton(
                        onClick = { changingLeader = !changingLeader },
                        enabled = !isSaving
                    ) {
                        Text(
                            text = stringResource(
                                if (changingLeader) R.string.proposal_sheet_cancel_change_leader
                                else R.string.proposal_sheet_change_leader
                            )
                        )
                    }
                }
            }
            Text(
                text = stringResource(
                    when {
                        changingLeader -> R.string.proposal_sheet_change_leader_hint
                        leaderSeat == null -> R.string.proposal_sheet_choose_leader_hint
                        else -> R.string.proposal_sheet_choose_team_hint
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.proposal_sheet_team_section),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    text = stringResource(R.string.record_team_count, teamSeats.size, expectedTeamSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (teamSeats.size == expectedTeamSize)
                        LocalExtendedColors.current.success
                    else
                        LocalExtendedColors.current.accent
                )
            }
            SeatPicker(
                playerCount = playerCount,
                selectedSeats = teamSeats,
                onSeatClick = { seat ->
                    if (selectingLeader) {
                        onSelectLeader(seat)
                        changingLeader = false
                    } else {
                        onToggleTeamSeat(seat)
                    }
                },
                singleSelect = false,
                enabled = !isSaving,
                seatLabels = seatLabels,
                seatAvatars = seatAvatars,
                badges = leaderSeat?.let { mapOf(it to stringResource(R.string.seat_role_leader)) }
                    ?: emptyMap()
            )

            Text(
                text = if (!canSubmit) stringResource(R.string.proposal_sheet_blocked) else " ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.heightIn(min = 20.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing12)
            ) {
                OutlinedButton(
                    onClick = onSaveDraft,
                    enabled = !isSaving,
                    shape = ButtonShape,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = Dimens.MinTouchTarget)
                ) {
                    Text(text = stringResource(R.string.proposal_sheet_save_draft))
                }
                Button(
                    onClick = onSubmit,
                    enabled = canSubmit && !isSaving && !selectingLeader,
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
