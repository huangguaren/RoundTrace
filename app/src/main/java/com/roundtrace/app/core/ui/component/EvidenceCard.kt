package com.roundtrace.app.core.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.roundtrace.app.R
import com.roundtrace.app.core.model.EvidenceFinding
import com.roundtrace.app.core.model.EvidenceLevel
import com.roundtrace.app.core.ui.theme.CardShape
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.LocalExtendedColors
import com.roundtrace.app.core.ui.theme.PillShape

/**
 * 证据卡片（DESIGN_SPEC §4.10 / TASKS.md T58④）。
 *
 * 四段：**结论 → 确定性徽标 → 证据事件编号 → 展开解释**。
 *
 * 确定性徽标四档，`推测` 必须**显式带「推测」字样**且与 `规则确定` 不同色形：
 * | 等级 | 文案 | 颜色 |
 * |---|---|---|
 * | `RULE_CERTAIN` | 规则确定 | success |
 * | `OBSERVER_CERTAIN` | 本人可知 | primary |
 * | `INFERENCE` | 推测 | accent（且带虚线感的分隔） |
 * | `UNKNOWN` | 信息不足 | unknown |
 */
@Composable
fun EvidenceCard(
    finding: EvidenceFinding,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val extended = LocalExtendedColors.current

    val (badgeLabel, badgeColor, badgeContainer) = when (finding.level) {
        EvidenceLevel.RULE_CERTAIN -> Triple(
            stringResource(R.string.evidence_level_rule_certain),
            extended.success,
            extended.successContainer
        )

        EvidenceLevel.OBSERVER_CERTAIN -> Triple(
            stringResource(R.string.evidence_level_observer_certain),
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primaryContainer
        )

        EvidenceLevel.INFERENCE -> Triple(
            stringResource(R.string.evidence_level_inference),
            extended.accent,
            extended.accentContainer
        )

        EvidenceLevel.UNKNOWN -> Triple(
            stringResource(R.string.evidence_level_unknown),
            extended.unknown,
            extended.unknownContainer
        )
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .clickable { expanded = !expanded }
            .semantics {
                contentDescription = "$badgeLabel：${finding.label}"
            },
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = Dimens.CardElevation,
        shadowElevation = Dimens.CardElevation
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
        ) {
            // ① 结论
            Text(
                text = finding.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // ② 确定性徽标（颜色 + 文字双重表达，不靠颜色单通道）
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = badgeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = badgeColor,
                    modifier = Modifier
                        .background(badgeContainer, PillShape)
                        .padding(
                            horizontal = Dimens.Spacing8,
                            vertical = Dimens.Spacing4
                        )
                )
                Text(
                    text = ruleDisplayName(finding.ruleId),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(
                thickness = Dimens.DividerThickness,
                color = MaterialTheme.colorScheme.outline
            )

            // ③ 证据事件编号（R7：只显示条数，不把内部 EventId.raw 上屏）
            Text(
                text = if (finding.sourceEventIds.isEmpty()) {
                    stringResource(R.string.evidence_no_direct_source)
                } else {
                    stringResource(R.string.evidence_source_count, finding.sourceEventIds.size)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (finding.sourceEventIds.isEmpty()) extended.accent else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ④ 展开解释
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing4)) {
                    Text(
                        text = finding.explanation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (finding.relatedSeats.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.evidence_related_seats) + " " +
                                finding.relatedSeats.sorted().joinToString("、") { "$it 号" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 供外部复用徽标颜色（例如列表摘要）。 */
@Composable
fun evidenceLevelColor(level: EvidenceLevel): Color {
    val extended = LocalExtendedColors.current
    return when (level) {
        EvidenceLevel.RULE_CERTAIN -> extended.success
        EvidenceLevel.OBSERVER_CERTAIN -> MaterialTheme.colorScheme.primary
        EvidenceLevel.INFERENCE -> extended.accent
        EvidenceLevel.UNKNOWN -> extended.unknown
    }
}

/**
 * ruleId（如 `A05_TEAM_CONTAINS_EVIL`）→ 人类可读规则名（R6 修复）。
 *
 * 绝不把内部枚举串直接上屏；按 `Axx` 前缀映射到 strings.xml 的 `rule_a01..rule_a12`，
 * 未匹配到时用 `rule_unknown` 兜底，避免新增规则时漏配文案又露出 code。
 */
@Composable
internal fun ruleDisplayName(ruleId: String): String {
    val res = when (ruleId.take(3)) {
        "A01" -> R.string.rule_a01
        "A02" -> R.string.rule_a02
        "A03" -> R.string.rule_a03
        "A04" -> R.string.rule_a04
        "A05" -> R.string.rule_a05
        "A06" -> R.string.rule_a06
        "A07" -> R.string.rule_a07
        "A08" -> R.string.rule_a08
        "A09" -> R.string.rule_a09
        "A10" -> R.string.rule_a10
        "A11" -> R.string.rule_a11
        "A12" -> R.string.rule_a12
        else -> R.string.rule_unknown
    }
    return stringResource(res)
}
