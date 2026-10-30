package com.roundtrace.app.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roundtrace.app.R
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.LocalExtendedColors

/**
 * 单个任务的展示槽位。
 *
 * @param outcome `null` = 尚未结算（**不是 0、不是失败**）
 * @param verified `false` 表示数据不完整，UI 需要额外提示
 */
data class QuestSlot(
    val questNo: Int,
    val outcome: MissionOutcome?,
    val verified: Boolean = false
)

/**
 * 5 任务进度条（DESIGN_SPEC §4.3 / TASKS.md T45①）。
 *
 * 规则：
 * - 5 个节点固定；当前任务放大并标文字「第 N 任务」（`compact = true` 时不标文字）；
 * - 已完成任务按成功/失败着色 + **图标**（不能只靠颜色）；
 * - 未开始用 `unknown` 色。
 *
 * @param outcomes 已有结论的任务（顺序无关，按 questNo 匹配）
 * @param currentQuestNo 当前任务号；`null` = 还没有任何任务
 * @param compact 迷你版（首页卡片用）
 */
@Composable
fun QuestStepper(
    outcomes: List<QuestSlot>,
    currentQuestNo: Int?,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val extended = LocalExtendedColors.current
    val scheme = MaterialTheme.colorScheme

    val byQuestNo = outcomes.associateBy { it.questNo }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "任务进度：共 5 个任务，当前第 ${currentQuestNo ?: 0} 个"
            },
        horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (questNo in 1..TOTAL_QUESTS) {
            val slot = byQuestNo[questNo]
            val isCurrent = currentQuestNo == questNo
            val nodeSize = when {
                compact -> 12.dp
                isCurrent -> 32.dp
                else -> 20.dp
            }
            val background = when (slot?.outcome) {
                MissionOutcome.SUCCESS -> extended.success
                MissionOutcome.FAILED -> extended.error
                MissionOutcome.UNKNOWN -> extended.unknown
                null -> if (isCurrent) scheme.primary else extended.unknownContainer
            }
            val icon = when (slot?.outcome) {
                MissionOutcome.SUCCESS -> Icons.Filled.Check
                MissionOutcome.FAILED -> Icons.Filled.Close
                else -> null
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(nodeSize)
                        .background(background, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = scheme.surface,
                            modifier = Modifier.size(nodeSize / 2)
                        )
                    } else if (!compact) {
                        Text(
                            text = questNo.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isCurrent) scheme.onPrimary else scheme.onSurfaceVariant
                        )
                    }
                }
                if (!compact && isCurrent) {
                    Text(
                        text = stringResource(R.string.quest_stepper_current, questNo),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.primary,
                        modifier = Modifier.padding(top = Dimens.Spacing4)
                    )
                }
            }

            if (questNo != TOTAL_QUESTS && !compact) {
                Box(
                    modifier = Modifier
                        .height(Dimens.DividerThickness)
                        .weight(1f)
                        .background(scheme.outline)
                )
            }
        }
    }
}

/** 固定 5 个任务。 */
private const val TOTAL_QUESTS = 5
