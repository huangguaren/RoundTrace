package com.roundtrace.app.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roundtrace.app.R
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.LocalExtendedColors
import com.roundtrace.app.core.ui.theme.PillShape

/**
 * 状态四态徽标（DESIGN_SPEC §1.3）。
 *
 * **铁律**：任何任务结果、投票结果、任务牌结果都必须「颜色 + 图标 + 文字」三重表达，
 * 禁止只靠绿/红区分状态（色盲可访问性硬要求）。
 */
enum class BadgeState {
    /** 成功 / 已完成 —— success 色 + CheckCircle */
    SUCCESS,

    /** 失败 —— error 色 + Cancel */
    FAILURE,

    /** 未知 / 未记录 —— unknown 色 + HelpOutline */
    UNKNOWN,

    /** 待处理 / 进行中 —— primary 色 + RadioButtonChecked */
    PENDING
}

/** 内部视觉映射，避免在 Composable 里写多段 when。 */
private data class BadgeVisual(
    val container: Color,
    val content: Color,
    val icon: ImageVector,
    val labelRes: Int
)

/**
 * 对局状态 → 徽标四态的映射（UI 层职责，规则层不感知颜色）。
 */
fun com.roundtrace.app.core.model.MatchStatus.toBadgeState(): BadgeState =
    when (this) {
        com.roundtrace.app.core.model.MatchStatus.DRAFT -> BadgeState.PENDING
        com.roundtrace.app.core.model.MatchStatus.IN_PROGRESS -> BadgeState.PENDING
        com.roundtrace.app.core.model.MatchStatus.AWAITING_ASSASSINATION -> BadgeState.PENDING
        com.roundtrace.app.core.model.MatchStatus.FINISHED_GOOD -> BadgeState.SUCCESS
        com.roundtrace.app.core.model.MatchStatus.FINISHED_EVIL -> BadgeState.FAILURE
        com.roundtrace.app.core.model.MatchStatus.ABANDONED -> BadgeState.UNKNOWN
    }

/**
 * 任务结果 → 徽标四态的映射。
 *
 * `null` 表示「尚未结算」，必须显式映射成 [BadgeState.UNKNOWN]（不是空白、不是 0）。
 */
fun com.roundtrace.app.core.model.MissionOutcome?.toBadgeState(): BadgeState = when (this) {
    com.roundtrace.app.core.model.MissionOutcome.SUCCESS -> BadgeState.SUCCESS
    com.roundtrace.app.core.model.MissionOutcome.FAILED -> BadgeState.FAILURE
    com.roundtrace.app.core.model.MissionOutcome.UNKNOWN, null -> BadgeState.UNKNOWN
}

/**
 * 渲染一个状态徽标。
 *
 * @param state 四态之一
 * @param modifier 外部修饰符
 * @param labelOverride 覆写文案（一般不用；文案走 strings.xml）
 */
@Composable
fun StatusBadge(
    state: BadgeState,
    modifier: Modifier = Modifier,
    labelOverride: String? = null
) {
    val extended = LocalExtendedColors.current
    val scheme = MaterialTheme.colorScheme

    val visual: BadgeVisual = when (state) {
        BadgeState.SUCCESS -> BadgeVisual(
            container = extended.successContainer,
            content = extended.success,
            icon = Icons.Filled.CheckCircle,
            labelRes = R.string.badge_success
        )

        BadgeState.FAILURE -> BadgeVisual(
            container = extended.errorContainer,
            content = extended.error,
            icon = Icons.Filled.Cancel,
            labelRes = R.string.badge_failure
        )

        BadgeState.UNKNOWN -> BadgeVisual(
            container = extended.unknownContainer,
            content = extended.unknown,
            icon = Icons.AutoMirrored.Outlined.HelpOutline,
            labelRes = R.string.badge_unknown
        )

        BadgeState.PENDING -> BadgeVisual(
            container = scheme.primaryContainer,
            content = scheme.primary,
            icon = Icons.Filled.RadioButtonChecked,
            labelRes = R.string.badge_pending
        )
    }

    val label = labelOverride ?: stringResource(visual.labelRes)

    Row(
        modifier = modifier
            .background(visual.container, PillShape)
            .padding(horizontal = Dimens.Spacing8, vertical = Dimens.Spacing4)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing4)
    ) {
        Icon(
            imageVector = visual.icon,
            contentDescription = null,
            tint = visual.content,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = visual.content
        )
    }
}
