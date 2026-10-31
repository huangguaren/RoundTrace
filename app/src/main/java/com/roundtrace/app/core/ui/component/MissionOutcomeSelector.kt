package com.roundtrace.app.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.roundtrace.app.R
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.ui.theme.CardShape
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.LocalExtendedColors
import com.roundtrace.app.core.ui.theme.PillShape

/**
 * 任务牌结果录入（DESIGN_SPEC §4.6 / TASKS.md T48）。
 *
 * **与投票组件彻底分离**（PRD AC-02）：
 * - 标题明确写「任务牌（不是投票）」；
 * - 控件是「失败牌数分段选择器」，不是票数；
 * - 下方实时展示规则判定文案，判定结果 [derivedOutcome] 由调用方用
 *   `AvalonRules.isMissionFailed` 算好传入，本组件不重算。
 *
 * 失败牌数未知（`failCount == null`）时结果区必须显示「结果未知」，**不得自动判定**（T48④）。
 *
 * @param derivedOutcome 由失败牌数与规则门槛推导出的结果；`null` 表示无法推导
 */
@Composable
fun MissionOutcomeSelector(
    questNo: Int,
    failThreshold: Int,
    failCount: Int?,
    observedOutcome: MissionOutcome?,
    derivedOutcome: MissionOutcome?,
    onFailCountChange: (Int?) -> Unit,
    onObservedOutcomeChange: (MissionOutcome?) -> Unit,
    modifier: Modifier = Modifier
) {
    val extended = LocalExtendedColors.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = Dimens.CardElevation
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.Spacing12)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
                Text(
                    text = stringResource(R.string.mission_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = extended.accent,
                    modifier = Modifier.fillMaxWidth()
                )

                // —— 失败牌数：0 / 1 / 2 / 3 / 未知 ——
                Text(
                    text = stringResource(R.string.mission_fail_count_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (count in 0..MAX_FAIL_COUNT_OPTION) {
                        FilterChip(
                            selected = failCount == count,
                            onClick = { onFailCountChange(count) },
                            label = {
                                Text(
                                    text = count.toString(),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            },
                            shape = PillShape,
                            modifier = Modifier.heightIn(min = Dimens.MinTouchTarget),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        )
                    }
                    FilterChip(
                        selected = failCount == null,
                        onClick = { onFailCountChange(null) },
                        label = {
                            Text(
                                text = stringResource(R.string.mission_fail_count_unknown),
                                style = MaterialTheme.typography.labelLarge
                            )
                        },
                        shape = PillShape,
                        modifier = Modifier.heightIn(min = Dimens.MinTouchTarget),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            selectedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            // —— 实时规则判定文案（T48③）——
            val ruleText = when (derivedOutcome) {
                MissionOutcome.SUCCESS -> stringResource(
                    R.string.mission_rule_line_success,
                    questNo,
                    failThreshold
                )

                MissionOutcome.FAILED -> stringResource(
                    R.string.mission_rule_line_failed,
                    questNo,
                    failThreshold
                )

                MissionOutcome.UNKNOWN, null -> stringResource(R.string.mission_rule_unknown)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(state = derivedOutcome.toBadgeState())
                Text(
                    text = ruleText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // —— 用户直接观察到的结果（可选，允许与失败牌数冲突以暴露矛盾）——
            Text(
                text = stringResource(R.string.mission_observed_label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ObservedChip(
                    label = stringResource(R.string.mission_rule_success),
                    selected = observedOutcome == MissionOutcome.SUCCESS,
                    onClick = {
                        onObservedOutcomeChange(
                            if (observedOutcome == MissionOutcome.SUCCESS) null else MissionOutcome.SUCCESS
                        )
                    }
                )
                ObservedChip(
                    label = stringResource(R.string.mission_rule_failed),
                    selected = observedOutcome == MissionOutcome.FAILED,
                    onClick = {
                        onObservedOutcomeChange(
                            if (observedOutcome == MissionOutcome.FAILED) null else MissionOutcome.FAILED
                        )
                    }
                )
                ObservedChip(
                    label = stringResource(R.string.mission_fail_count_unknown),
                    selected = observedOutcome == null,
                    onClick = { onObservedOutcomeChange(null) }
                )
            }

            Text(
                text = stringResource(R.string.mission_note_vote_is_not_fail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ObservedChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label, style = MaterialTheme.typography.labelMedium) },
        shape = PillShape,
        modifier = Modifier.heightIn(min = Dimens.MinTouchTarget),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

/** 失败牌分段最多到 3（队伍最多 5 人，3 张已足够表达所有可能的门槛判定）。 */
private const val MAX_FAIL_COUNT_OPTION = 3
