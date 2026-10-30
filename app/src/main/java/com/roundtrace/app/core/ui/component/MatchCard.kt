package com.roundtrace.app.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.roundtrace.app.R
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.ui.theme.CardShape
import com.roundtrace.app.core.ui.theme.Dimens

/**
 * 对局卡片（DESIGN_SPEC §4.12 / TASKS.md T41③）。
 *
 * 展示：标题、人数、当前任务进度（[QuestStepper] 迷你版）、状态徽标、更新时间。
 * 整卡可点，右侧 overflow 菜单（重命名 / 删除 / 导出 / 详情）。
 *
 * 纯展示组件：数据与行为全部由调用方传入。
 */
@Composable
fun MatchCard(
    title: String,
    playerCount: Int,
    questsStarted: Int,
    status: MatchStatus,
    updatedAtLabel: String,
    onClick: () -> Unit,
    onOverflowClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(
                            R.string.card_meta_line,
                            playerCount,
                            updatedAtLabel
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusBadge(state = status.toBadgeState())
                IconButton(
                    onClick = onOverflowClick,
                    modifier = Modifier.size(Dimens.MinTouchTarget)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.card_more_actions),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            QuestStepper(
                outcomes = emptyList(),
                currentQuestNo = questsStarted.takeIf { it > 0 },
                compact = true
            )
        }
    }
}
