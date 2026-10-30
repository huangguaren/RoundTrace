package com.roundtrace.app.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roundtrace.app.R
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.SeatShape

/**
 * 座位网格（DESIGN_SPEC §4.2 / TASKS.md T39）。
 *
 * 规格（逐条对应验收标准）：
 * - 5–10 个座位，**一行 5 列**（10 人时两行 5 列），间距 8dp；
 * - 单格最小 **56×56dp**，数字居中 **20sp**；
 * - 未选中 = `surfaceVariant` 底 + `onSurface` 字；
 *   选中 = `primaryContainer` 底 + **2dp `primary` 描边** + `onPrimaryContainer` 字；
 * - 单选/多选共用同一组件（`singleSelect` 参数切换）；
 * - 右上角可挂徽标（≤14dp，**图标不用文字**，用 `semantics` 补文案）；
 * - `contentDescription`：「4 号座位，当前未选中」/「4 号座位，已选中，队长」。
 *
 * @param playerCount 座位总数（5..10）
 * @param selectedSeats 已选座位
 * @param onSeatClick 点击座位
 * @param singleSelect true = 单选（选队长/选自己），false = 多选（选上车队伍）
 * @param badges 座位 → 徽标文案（如「队长」「我」）；只在选中时显示图标
 * @param enabled false 时整体 40% 透明且不可点
 */
@Composable
fun SeatPicker(
    playerCount: Int,
    selectedSeats: Set<Int>,
    onSeatClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    singleSelect: Boolean = false,
    badges: Map<Int, String> = emptyMap(),
    enabled: Boolean = true
) {
    val rows = (1..playerCount).chunked(SEATS_PER_ROW)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
    ) {
        rows.forEach { rowSeats ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
            ) {
                rowSeats.forEach { seat ->
                    SeatCell(
                        seat = seat,
                        selected = selectedSeats.contains(seat),
                        badgeLabel = badges[seat],
                        singleSelect = singleSelect,
                        enabled = enabled,
                        onClick = { onSeatClick(seat) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // 最后一行不足 5 个时补空位，保持列宽一致
                repeat(SEATS_PER_ROW - rowSeats.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SeatCell(
    seat: Int,
    selected: Boolean,
    badgeLabel: String?,
    singleSelect: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (selected) scheme.primaryContainer else scheme.surfaceVariant
    val content = if (selected) scheme.onPrimaryContainer else scheme.onSurface
    val description = when {
        selected && badgeLabel != null -> stringResource(
            R.string.seat_picker_desc_selected_role,
            seat,
            badgeLabel
        )

        selected -> stringResource(R.string.seat_picker_desc_selected, seat)
        else -> stringResource(R.string.seat_picker_desc_unselected, seat)
    }
    val alpha = if (enabled) 1f else DISABLED_ALPHA

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = Dimens.SeatMinSize)
            .aspectRatio(1f)
            .semantics {
                contentDescription = description
                role = if (singleSelect) Role.RadioButton else Role.Checkbox
            }
            .then(Modifier.padding(0.dp)),
        shape = SeatShape,
        color = container.copy(alpha = alpha),
        contentColor = content.copy(alpha = alpha),
        border = if (selected) {
            BorderStroke(Dimens.SelectedStroke, scheme.primary)
        } else {
            null
        }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = seat.toString(),
                style = MaterialTheme.typography.displaySmall,
                color = content.copy(alpha = alpha)
            )
            if (selected && badgeLabel != null) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = badgeLabel,
                    tint = scheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(Dimens.BadgeIconSize)
                )
            }
        }
    }
}

/** 一行固定 5 列（DESIGN_SPEC §4.2）。 */
private const val SEATS_PER_ROW = 5

/** 禁用态透明度。 */
private const val DISABLED_ALPHA = 0.4f
