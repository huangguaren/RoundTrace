package com.roundtrace.app.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.roundtrace.app.R
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.PillShape

/**
 * 发言/声称快速录入（DESIGN_SPEC §4.7 / TASKS.md T55①）。
 *
 * 三段式（标签 → 发言者 → 可选目标），主路径 **≤ 3 次触控**。
 *
 * 「跳身份」在视觉上必须与真实身份永久区分：本组件用**虚线边框 + 「声称」前缀**的
 * 文案表达，而真实身份由 `RevealScreen` 展示，两者不共用任何样式。
 */
@Composable
fun ClaimQuickAdd(
    selectedTag: ClaimType?,
    onTagSelected: (ClaimType) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
    ) {
        Text(
            text = stringResource(R.string.claim_pick_tag),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // 8 个预置标签，两行四列
        CLAIM_TAGS.chunked(4).forEach { rowTags ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
                rowTags.forEach { tag ->
                    FilterChip(
                        selected = selectedTag == tag,
                        onClick = { onTagSelected(tag) },
                        label = {
                            Text(
                                text = claimTagLabel(tag),
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        shape = PillShape,
                        modifier = Modifier.heightIn(min = Dimens.MinTouchTarget),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = if (tag.isClaimStyle()) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            selectedLabelColor = if (tag.isClaimStyle()) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            }
                        )
                    )
                }
            }
        }
        if (selectedTag != null && selectedTag.isClaimStyle()) {
            Text(
                text = stringResource(R.string.claim_is_not_fact),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 8 个预置标签的顺序（唯一来源，避免散落）。 */
val CLAIM_TAGS: List<ClaimType> = listOf(
    ClaimType.SUSPECT,
    ClaimType.PROTECT,
    ClaimType.ROLE_CLAIM,
    ClaimType.COUNTER_CLAIM,
    ClaimType.TEAM_RECOMMEND,
    ClaimType.STANCE_CHANGE,
    ClaimType.KEY_STATEMENT,
    ClaimType.OTHER
)

/** 是否是「声称身份」类标签（决定是否走虚线/「声称」前缀的视觉）。 */
fun ClaimType.isClaimStyle(): Boolean =
    this == ClaimType.ROLE_CLAIM || this == ClaimType.COUNTER_CLAIM

/** 标签中文文案。 */
@Composable
fun claimTagLabel(tag: ClaimType): String = when (tag) {
    ClaimType.SUSPECT -> stringResource(R.string.claim_tag_suspect)
    ClaimType.PROTECT -> stringResource(R.string.claim_tag_protect)
    ClaimType.ROLE_CLAIM -> stringResource(R.string.claim_tag_role)
    ClaimType.COUNTER_CLAIM -> stringResource(R.string.claim_tag_counter)
    ClaimType.TEAM_RECOMMEND -> stringResource(R.string.claim_tag_team)
    ClaimType.STANCE_CHANGE -> stringResource(R.string.claim_tag_stance)
    ClaimType.KEY_STATEMENT -> stringResource(R.string.claim_tag_key)
    ClaimType.OTHER -> stringResource(R.string.claim_tag_other)
}
