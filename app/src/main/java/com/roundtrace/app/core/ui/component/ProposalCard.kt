package com.roundtrace.app.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.roundtrace.app.R
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.ui.theme.CardShape
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.LocalExtendedColors
import com.roundtrace.app.core.ui.theme.PillShape

/**
 * 提案卡片（DESIGN_SPEC §4.4 / TASKS.md T45②）。
 *
 * **最重要的一条**：组队投票区与任务牌结果区必须
 * ① 用 `HorizontalDivider` 分隔；② 视觉上明显不同（票数用「赞成/反对」，任务牌用「失败牌数」）。
 * 这是 PRD AC-02「反对票 ≠ 失败牌」在 UI 上的最后防线。
 *
 * @param approveCount 赞成票数（`null` = 未记录）
 * @param rejectCount 反对票数（`null` = 未记录）
 * @param decision 组队决议（`null` = 还没有决议）
 * @param failCount 任务失败牌数（`null` = 未记录）
 * @param missionOutcome 任务结果（`null` = 尚未结算）
 */
@Composable
fun ProposalCard(
    questNo: Int,
    proposalNo: Int,
    leaderSeat: Int?,
    teamSeats: List<Int>,
    approveCount: Int?,
    rejectCount: Int?,
    decision: ProposalDecision?,
    failCount: Int?,
    missionOutcome: MissionOutcome?,
    missionVerified: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
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
            // 一行摘要
            Text(
                text = stringResource(
                    R.string.proposal_card_summary,
                    questNo,
                    proposalNo,
                    leaderSeat ?: 0
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // 队伍座位胶囊
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (teamSeats.isEmpty()) {
                    Text(
                        text = stringResource(R.string.record_team_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalExtendedColors.current.unknown
                    )
                } else {
                    teamSeats.sorted().forEach { seat ->
                        SeatChip(seat = seat)
                    }
                }
            }

            HorizontalDivider(
                thickness = Dimens.DividerThickness,
                color = MaterialTheme.colorScheme.outline
            )

            // —— 区块 1：组队投票 ——
            VoteSection(
                approveCount = approveCount,
                rejectCount = rejectCount,
                decision = decision
            )

            HorizontalDivider(
                thickness = Dimens.DividerThickness,
                color = MaterialTheme.colorScheme.outline
            )

            // —— 区块 2：任务牌结果（与投票视觉不同） ——
            MissionSection(
                failCount = failCount,
                outcome = missionOutcome,
                verified = missionVerified
            )
        }
    }
}

@Composable
private fun SeatChip(seat: Int) {
    Surface(
        shape = PillShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            text = stringResource(R.string.seat_number, seat),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(
                horizontal = Dimens.Spacing8,
                vertical = Dimens.Spacing4
            ),
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

/** 组队投票区：只用「赞成 / 反对」两个词，绝不出现「失败」。 */
@Composable
private fun VoteSection(
    approveCount: Int?,
    rejectCount: Int?,
    decision: ProposalDecision?
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing4)) {
        Text(
            text = stringResource(R.string.vote_section_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResource(
                R.string.proposal_vote_counts,
                approveCount?.toString() ?: stringResource(R.string.unrecorded_short),
                rejectCount?.toString() ?: stringResource(R.string.unrecorded_short)
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        val badge = when (decision) {
            ProposalDecision.APPROVED -> BadgeState.PENDING
            ProposalDecision.REJECTED -> BadgeState.FAILURE
            ProposalDecision.UNKNOWN, null -> BadgeState.UNKNOWN
        }
        StatusBadge(
            state = badge,
            labelOverride = when (decision) {
                ProposalDecision.APPROVED -> stringResource(R.string.vote_decision_approved)
                ProposalDecision.REJECTED -> stringResource(R.string.vote_decision_rejected)
                else -> stringResource(R.string.badge_unknown)
            }
        )
    }
}

/** 任务牌区：只用「失败牌」，绝不出现「反对票」。 */
@Composable
private fun MissionSection(
    failCount: Int?,
    outcome: MissionOutcome?,
    verified: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing4)) {
        Text(
            text = stringResource(R.string.mission_section_title),
            style = MaterialTheme.typography.labelMedium,
            color = LocalExtendedColors.current.accent
        )
        Text(
            text = stringResource(
                R.string.proposal_fail_count_line,
                failCount?.toString() ?: stringResource(R.string.unrecorded_short)
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusBadge(state = outcome.toBadgeState())
            if (!verified) {
                Text(
                    text = stringResource(R.string.proposal_unverified),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalExtendedColors.current.accent
                )
            }
        }
    }
}
