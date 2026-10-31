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
    seatLabels: Map<Int, String?> = emptyMap(),
    seatAvatars: Map<Int, String?> = emptyMap(),
    onSelectLeader: (Int?) -> Unit,
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

            // —— 单网格选队长+队员：第一次点选队长，之后点其他人选队员 ——
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (leaderSeat == null) "点选队长" else "队长 ${leaderSeat} 号 · 再点选 ${expectedTeamSize - teamSeats.size} 名队员",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (leaderSeat != null) {
                    Text(
                        text = stringResource(R.string.record_team_count, teamSeats.size, expectedTeamSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (teamSeats.size == expectedTeamSize)
                            LocalExtendedColors.current.success
                        else
                            LocalExtendedColors.current.accent
                    )
                }
            }
            val combinedSelected = teamSeats  // 队长不高亮，只显示星标
            SeatPicker(
                playerCount = playerCount,
                selectedSeats = combinedSelected,
                onSeatClick = { seat ->
                    if (leaderSeat == null) {
                        onSelectLeader(seat)  // 第一次选队长
                    } else if (seat == leaderSeat) {
                        onSelectLeader(null)  // 点当前队长=取消
                    } else {
                        onToggleTeamSeat(seat)  // 点其他人=选队员
                    }
                },
                singleSelect = false,
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
