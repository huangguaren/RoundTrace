package com.roundtrace.app.feature.claim

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.roundtrace.app.R
import com.roundtrace.app.core.model.BeliefType
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.ui.component.ClaimQuickAdd
import com.roundtrace.app.core.ui.component.SaveStatusBar
import com.roundtrace.app.core.ui.component.SeatPicker
import com.roundtrace.app.core.ui.component.isClaimStyle
import com.roundtrace.app.core.ui.roleDisplayName
import com.roundtrace.app.core.ui.theme.ButtonShape
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.PillShape

/**
 * 发言/笔记录入页（TASKS.md T55）。
 *
 * 主路径：选标签 → 选发言者 →（可选）选目标 → 保存，**全程不需要键盘**。
 * 「跳身份」需要额外选一个「声称的身份」，并明确标注这只是 Claim。
 */
@Composable
fun ClaimScreen(
    state: ClaimUiState,
    onAction: (ClaimAction) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues()
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        if (state.isLoading) {
            Text(
                text = stringResource(R.string.state_loading),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Dimens.PageHorizontalPadding)
            )
            return@Column
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.PageHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.Spacing16)
        ) {
            ClaimQuickAdd(
                selectedTag = state.selectedTag,
                onTagSelected = { onAction(ClaimAction.SelectTag(it)) }
            )

            // 「跳身份」才需要的角色选择
            if (state.selectedTag?.isClaimStyle() == true) {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
                    Text(
                        text = stringResource(R.string.claim_claimed_role),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
                        CLAIMABLE_ROLES.take(4).forEach { role ->
                            RoleChip(
                                role = role,
                                selected = state.claimedRole == role,
                                onClick = {
                                    onAction(
                                        ClaimAction.SelectClaimedRole(
                                            if (state.claimedRole == role) null else role
                                        )
                                    )
                                }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
                        CLAIMABLE_ROLES.drop(4).forEach { role ->
                            RoleChip(
                                role = role,
                                selected = state.claimedRole == role,
                                onClick = {
                                    onAction(
                                        ClaimAction.SelectClaimedRole(
                                            if (state.claimedRole == role) null else role
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // 发言者
            Text(
                text = stringResource(R.string.claim_pick_speaker),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SeatPicker(
                playerCount = state.playerCount,
                selectedSeats = state.speakerSeat?.let { setOf(it) } ?: emptySet(),
                onSeatClick = { onAction(ClaimAction.SelectSpeaker(it)) },
                singleSelect = true
            )

            // 目标
            Text(
                text = stringResource(R.string.claim_pick_target),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SeatPicker(
                playerCount = state.playerCount,
                selectedSeats = state.targetSeats,
                onSeatClick = { onAction(ClaimAction.ToggleTarget(it)) },
                singleSelect = false
            )

            // 私人笔记模式
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.MinTouchTarget),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
            ) {
                Switch(
                    checked = state.privateMode,
                    onCheckedChange = { onAction(ClaimAction.SetPrivateMode(it)) }
                )
                Text(
                    text = stringResource(R.string.claim_private_mode),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (state.privateMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
                    BeliefType.entries.forEach { type ->
                        FilterChip(
                            selected = state.beliefType == type,
                            onClick = { onAction(ClaimAction.SetBeliefType(type)) },
                            label = {
                                Text(
                                    text = beliefLabel(type),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            shape = PillShape,
                            modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.text,
                onValueChange = { onAction(ClaimAction.UpdateText(it)) },
                label = { Text(text = stringResource(R.string.claim_note_optional)) },
                shape = ButtonShape,
                modifier = Modifier.fillMaxWidth()
            )

            if (state.errorMessage != null) {
                Text(
                    text = state.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        SaveStatusBar(status = state.saveStatus, onRetry = { onAction(ClaimAction.Save) })

        Button(
            onClick = { onAction(ClaimAction.Save) },
            enabled = state.canSave,
            shape = ButtonShape,
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.PageHorizontalPadding)
                .heightIn(min = Dimens.MinTouchTarget)
        ) {
            Text(text = stringResource(R.string.claim_save))
        }
    }
}

@Composable
private fun RoleChip(role: RoleId, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = roleDisplayName(role),
                style = MaterialTheme.typography.labelMedium
            )
        },
        shape = PillShape,
        modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)
    )
}

@Composable
private fun beliefLabel(type: BeliefType): String = when (type) {
    BeliefType.SUSPECT -> stringResource(R.string.claim_belief_suspect)
    BeliefType.TRUST -> stringResource(R.string.claim_belief_trust)
    BeliefType.ROLE_GUESS -> stringResource(R.string.claim_belief_role)
    BeliefType.OTHER -> stringResource(R.string.claim_belief_other)
}

/**
 * 可被「声称」的角色。
 *
 * **不在 feature 层维护角色清单**（B27）：直接取 ruleset 的 `roleDisplayOrder`，
 * 这样预设变化时声称选项会自动跟上，不会与真实角色空间脱节。
 */
private val CLAIMABLE_ROLES: List<RoleId> =
    com.roundtrace.app.core.rules.avalon.AvalonPreset.roleDisplayOrder
