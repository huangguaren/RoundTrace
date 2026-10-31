package com.roundtrace.app.feature.reveal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roundtrace.app.R
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.ui.roleDisplayName
import com.roundtrace.app.core.ui.theme.ButtonShape
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.LocalExtendedColors
import com.roundtrace.app.core.ui.theme.PanelShape

/**
 * 结局揭晓页（TASKS.md T56）。
 *
 * 硬要求：
 * - 逐座位填真实角色，可选「仍未知」（T56①）；
 * - 三次任务成功自动进入 `AWAITING_ASSASSINATION`，界面必须明确提示
 *   「不能直接认定好人获胜」（T56④）；
 * - 角色不完整时保存成功但高级结论区显示「信息不足」（T56⑤）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RevealScreen(
    state: RevealUiState,
    onAction: (RevealAction) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues()
) {
    val extended = LocalExtendedColors.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(
            start = Dimens.PageHorizontalPadding,
            end = Dimens.PageHorizontalPadding,
            top = Dimens.Spacing8,
            bottom = Dimens.Spacing32
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.Spacing12)
    ) {
        if (state.isAwaitingAssassination) {
            item {
                Surface(
                    shape = PanelShape,
                    color = extended.accentContainer,
                    contentColor = extended.onAccentContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.reveal_assassination_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(Dimens.Spacing12)
                    )
                }
            }
        }

        if (!state.rolesComplete) {
            item {
                Text(
                    text = stringResource(R.string.reveal_insufficient),
                    style = MaterialTheme.typography.bodySmall,
                    color = extended.accent
                )
            }
        }

        // 逐座位填角色
        val takenRoles = state.rows.filter { it.roleId != RoleId.UNKNOWN }.map { it.roleId }.toSet()
        items(state.rows.size) { index ->
            val row = state.rows[index]
            RevealRow(
                seat = row.seat,
                roleId = row.roleId,
                takenRoles = takenRoles - row.roleId,
                onRoleSelected = { onAction(RevealAction.SetSeatRole(row.seat, it)) }
            )
        }

        item {
            Button(
                onClick = { onAction(RevealAction.Reveal) },
                enabled = state.canReveal,
                shape = ButtonShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.MinTouchTarget)
            ) {
                Text(
                    text = if (state.isRevealed) {
                        stringResource(R.string.reveal_done)
                    } else {
                        stringResource(R.string.reveal_action)
                    }
                )
            }
        }

        if (state.isAwaitingAssassination) {
            // 刺校区两条硬要求（DESIGN_SPEC §5.1）：
            // ① 必须覆盖 1..playerCount 全部座位（7–10 人局 6/7/8 号也能选）；
            // ② 每个座位必须有明确选中态（点了有反馈）。
            item {
                Text(
                    text = stringResource(R.string.reveal_assassin_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
                    verticalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
                ) {
                    for (seat in 1..state.playerCount) {
                        SeatChoiceButton(
                            seat = seat,
                            selected = seat == state.assassinSeat,
                            onSelect = {
                                onAction(
                                    RevealAction.SetAssassinSeat(
                                        if (seat == state.assassinSeat) null else seat
                                    )
                                )
                            }
                        )
                    }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.reveal_target_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
                    verticalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
                ) {
                    for (seat in 1..state.playerCount) {
                        SeatChoiceButton(
                            seat = seat,
                            selected = seat == state.targetSeat,
                            onSelect = {
                                onAction(
                                    RevealAction.SetTargetSeat(
                                        if (seat == state.targetSeat) null else seat
                                    )
                                )
                            }
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = { onAction(RevealAction.SaveAssassination) },
                    enabled = state.canRecordAssassination,
                    shape = ButtonShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.MinTouchTarget)
                ) {
                    Text(text = stringResource(R.string.reveal_save_assassination))
                }
            }
        }
    }
}

@Composable
private fun RevealRow(
    seat: Int,
    roleId: RoleId,
    takenRoles: Set<RoleId>,
    onRoleSelected: (RoleId) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.Spacing12)
                .heightIn(min = Dimens.MinTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.reveal_seat_role, seat),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    shape = ButtonShape,
                    modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)
                ) {
                    Text(
                        text = if (roleId == RoleId.UNKNOWN) {
                            stringResource(R.string.reveal_role_unknown)
                        } else {
                            roleDisplayName(roleId)
                        }
                    )
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.reveal_role_unknown)) },
                        onClick = {
                            onRoleSelected(RoleId.UNKNOWN)
                            expanded = false
                        }
                    )
                    REVEALABLE_ROLES.forEach { role ->
                        val taken = role in takenRoles
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = roleDisplayName(role),
                                    color = if (taken) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            enabled = !taken,
                            onClick = {
                                onRoleSelected(role)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 可揭晓的角色集合（不含 UNKNOWN）。
 *
 * **不在 feature 层维护角色清单**（B27）：直接取 ruleset 的 `roleDisplayOrder`。
 * 揭晓页填的是「真实身份」，可选集合必须与规则层的角色空间严格一致。
 */
private val REVEALABLE_ROLES: List<RoleId> =
    com.roundtrace.app.core.rules.avalon.AvalonPreset.roleDisplayOrder

/**
 * 刺校区的单个座位选择钮（R9 修复）。
 *
 * 选中态 = `primaryContainer` 底 + 2dp `primary` 描边，与 SeatPicker 选中视觉一致；
 * 颜色 + 描边 + contentDescription 三重表达，不靠颜色单通道。
 */
@Composable
private fun SeatChoiceButton(
    seat: Int,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val desc = if (selected) {
        stringResource(R.string.seat_picker_desc_selected, seat)
    } else {
        stringResource(R.string.seat_picker_desc_unselected, seat)
    }
    OutlinedButton(
        onClick = onSelect,
        shape = ButtonShape,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) colorScheme.primary else colorScheme.outline
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) colorScheme.primaryContainer else colorScheme.surface,
            contentColor = if (selected) colorScheme.onPrimaryContainer else colorScheme.onSurface
        ),
        modifier = Modifier
            .heightIn(min = Dimens.MinTouchTarget)
            .semantics { contentDescription = desc }
    ) {
        Text(text = "$seat")
    }
}
