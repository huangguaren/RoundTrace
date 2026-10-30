package com.roundtrace.app.feature.replay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.roundtrace.app.R
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.ui.component.EvidenceCard
import com.roundtrace.app.core.ui.component.PerspectiveSwitcher
import com.roundtrace.app.core.ui.component.TimelineEventCard
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.PanelShape

/**
 * 复盘页（TASKS.md T58）。
 *
 * 结构：视角切换 → **常驻的当前视角说明** → 上帝视角横幅（仅上帝）→
 * 证据区（四档徽标）→ 时间线。
 *
 * 反剧透的 UI 表现：
 * - 「当前视角：1 号梅林，截至第 N 个事件」必须常驻（T58②）；
 * - 重建角色时有独立横幅，明确「按规则重建的初始信息」（TDD §5.2-3）；
 * - 上帝视角有 `accentContainer` 顶部横幅（T58③）；
 * - 空态「暂无可用分析」+ 按钮（T58⑤）。
 */
@Composable
fun ReplayScreen(
    state: ReplayUiState,
    onAction: (ReplayAction) -> Unit,
    onBackToRecord: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues()
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            state.errorMessage != null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.errorMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Dimens.PageHorizontalPadding,
                    end = Dimens.PageHorizontalPadding,
                    top = Dimens.Spacing8,
                    bottom = Dimens.Spacing32
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.Spacing12)
            ) {
                item {
                    PerspectiveSwitcher(
                        selected = state.mode,
                        godViewEnabled = state.canOpenGodView,
                        onSelect = { mode -> onAction(ReplayAction.SelectPerspective(mode)) },
                        onGodViewBlocked = {
                            onAction(ReplayAction.SelectPerspective(Perspective.GOD_AFTER_REVEAL))
                        }
                    )
                }
                item {
                    PerspectiveBanner(state = state)
                }
                item {
                    Text(
                        text = stringResource(R.string.evidence_section_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                if (state.hasNoFindings) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
                            Text(
                                text = stringResource(R.string.replay_no_findings),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            androidx.compose.material3.OutlinedButton(
                                onClick = onBackToRecord,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(text = stringResource(R.string.review_empty_action))
                            }
                        }
                    }
                }
                state.findings.forEach { finding ->
                    item { EvidenceCard(finding = finding) }
                }
                item {
                    Text(
                        text = stringResource(R.string.timeline_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                state.timeline.forEach { entry ->
                    item { TimelineEventCard(entry = entry) }
                }
            }
        }
    }
}

@Composable
private fun PerspectiveBanner(state: ReplayUiState) {
    val snapshot = state.snapshot
    val extended = com.roundtrace.app.core.ui.theme.LocalExtendedColors.current

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
        // 常驻说明：当前视角 + 位置（T58②）
        val currentLine = when (state.mode) {
            Perspective.PUBLIC -> stringResource(R.string.perspective_current_public)
            Perspective.OBSERVER_AT_TIME -> {
                val seat = state.observerSeat ?: 0
                val roleName = com.roundtrace.app.core.ui.roleDisplayName(
                    snapshot?.observerRole ?: com.roundtrace.app.core.model.RoleId.UNKNOWN
                )
                stringResource(R.string.perspective_current_observer, seat, roleName)
            }

            Perspective.GOD_AFTER_REVEAL -> stringResource(R.string.perspective_current_god)
        }
        Text(
            text = stringResource(R.string.perspective_current_line, currentLine, state.at.occurredOrder),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 上帝视角横幅（T58③）
        if (state.mode == Perspective.GOD_AFTER_REVEAL) {
            Surface(
                shape = PanelShape,
                color = extended.accentContainer,
                contentColor = extended.onAccentContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.perspective_god_banner),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(Dimens.Spacing12)
                )
            }
        }

        // 重建角色横幅（TDD §5.2-3：必须区分「当时真的知道」与「按规则重建」）
        if (state.mode == Perspective.OBSERVER_AT_TIME && snapshot?.observerRoleIsReconstructed == true) {
            Surface(
                shape = PanelShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.perspective_reconstructed_banner),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(Dimens.Spacing12)
                )
            }
        }

        // 信息不足（T58⑤ / K10）
        if (snapshot != null && snapshot.hasUnknowns) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
                Text(
                    text = stringResource(R.string.replay_unknowns_title) + "：" +
                        snapshot.unknowns.joinToString("、") { it.code },
                    style = MaterialTheme.typography.labelSmall,
                    color = extended.unknown
                )
            }
        }
    }
}
