package com.roundtrace.app.feature.create

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.roundtrace.app.R
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.ui.issueText
import com.roundtrace.app.core.ui.roleDisplayName
import com.roundtrace.app.core.ui.component.SeatPicker
import com.roundtrace.app.core.ui.theme.ButtonShape
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.LocalExtendedColors
import com.roundtrace.app.core.ui.theme.PillShape
import androidx.compose.ui.draw.clip
import kotlin.math.roundToInt

/**
 * 创建对局页（TASKS.md T40）。
 *
 * 规格：
 * - 分步表单：人数 → 角色构成 → 我的座位 → 首任队长 → 标题 → 创建；
 * - 角色构成实时显示「好人 X · 坏人 Y · 合计 Z」，非法时禁用创建并展示具体 `RuleIssue` 文案；
 * - 我的座位可不填（`mySeat = null` 合法）；
 * - 标题留空自动生成，**全流程不需要键盘**；
 * - 创建成功后由 `onCreated` 回调导航到记录页并携带 `matchId`。
 */
@Composable
fun CreateMatchScreen(
    state: CreateMatchUiState,
    onPlayerCountChange: (Int) -> Unit,
    onRoleCountChange: (RoleId, Int) -> Unit,
    onMySeatChange: (Int?) -> Unit,
    onLeaderChange: (Int) -> Unit,
    onTitleChange: (String) -> Unit,
    onSeatNameChange: (Int, String?) -> Unit,
    onSeatPositionChange: (Int, Float, Float) -> Unit,
    onSeatAvatarChange: (Int, String?) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    onCreated: (MatchId) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues()
) {
    LaunchedEffect(state.createdMatchId) {
        state.createdMatchId?.let(onCreated)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        StepIndicator(
            current = state.step,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.PageHorizontalPadding, vertical = Dimens.Spacing12)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.PageHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.Spacing16)
        ) {
            when (state.step) {
                CreateStep.PLAYER_COUNT -> PlayerCountStep(
                    playerCount = state.playerCount,
                    onChange = onPlayerCountChange
                )

                CreateStep.ROLES -> RolesStep(
                    state = state,
                    onRoleCountChange = onRoleCountChange
                )

                CreateStep.SEAT_LAYOUT -> SeatLayoutStep(
                    playerCount = state.playerCount,
                    seatNames = state.seatNames,
                    seatPositions = state.seatPositions,
                    seatAvatars = state.seatAvatars,
                    onSeatNameChange = onSeatNameChange,
                    onSeatPositionChange = onSeatPositionChange,
                    onSeatAvatarChange = onSeatAvatarChange
                )

                CreateStep.MY_SEAT -> MySeatStep(
                    playerCount = state.playerCount,
                    mySeat = state.mySeat,
                    onChange = onMySeatChange
                )

                CreateStep.LEADER -> LeaderStep(
                    playerCount = state.playerCount,
                    leaderSeat = state.initialLeaderSeat,
                    onChange = onLeaderChange
                )

                CreateStep.TITLE -> TitleStep(
                    title = state.title,
                    onTitleChange = onTitleChange,
                    errorMessage = state.errorMessage
                )
            }

            IssueList(state = state)
        }

        BottomBar(
            state = state,
            onBack = onBack,
            onNext = onNext,
            onConfirm = onConfirm
        )
    }
}

// ————————————————————————————————————————————————————————
// 步骤内容
// ————————————————————————————————————————————————————————

@Composable
private fun PlayerCountStep(playerCount: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing12)) {
        SectionTitle(text = stringResource(R.string.create_step_player_count))
        // 6 个选项分两行，避免窄屏溢出
        (MIN_PLAYERS..MAX_PLAYERS).chunked(3).forEach { rowCounts ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
                verticalAlignment = Alignment.CenterVertically
            ) {
                rowCounts.forEach { count ->
                    val selected = count == playerCount
                    val label = if (selected) {
                        stringResource(R.string.create_player_count_label, count)
                    } else {
                        count.toString()
                    }
                    if (selected) {
                        Button(
                            onClick = { onChange(count) },
                            shape = PillShape,
                            modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)
                        ) {
                            Text(text = label, style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onChange(count) },
                            shape = PillShape,
                            modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)
                        ) {
                            Text(text = label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RolesStep(state: CreateMatchUiState, onRoleCountChange: (RoleId, Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing12)) {
        SectionTitle(text = stringResource(R.string.create_step_roles))
        Text(
            text = stringResource(
                R.string.create_role_summary,
                state.goodCount,
                state.evilCount,
                state.roleTotal
            ),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (state.roleTotal != state.playerCount) {
            Text(
                text = stringResource(
                    R.string.create_role_total_mismatch,
                    state.roleTotal,
                    state.playerCount
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        state.roleSlots.forEach { slot ->
            RoleRow(
                roleId = slot.roleId,
                count = slot.count,
                onDelta = { delta -> onRoleCountChange(slot.roleId, delta) }
            )
        }
    }
}

@Composable
private fun RoleRow(roleId: RoleId, count: Int, onDelta: (Int) -> Unit) {
    val roleName = roleDisplayName(roleId)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
    ) {
        Text(
            text = roleName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        StepperButton(
            icon = Icons.Filled.Remove,
            description = "$roleName 减少一个",
            enabled = count > 0,
            onClick = { onDelta(-1) }
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        StepperButton(
            icon = Icons.Filled.Add,
            description = "$roleName 增加一个",
            enabled = true,
            onClick = { onDelta(1) }
        )
    }
}

@Composable
private fun StepperButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(Dimens.MinTouchTarget)
            .semantics { contentDescription = description }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                LocalExtendedColors.current.unknown
            }
        )
    }
}

@Composable
private fun MySeatStep(playerCount: Int, mySeat: Int?, onChange: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing12)) {
        SectionTitle(text = stringResource(R.string.create_step_my_seat))
        Text(
            text = stringResource(R.string.create_seat_optional_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SeatPicker(
            playerCount = playerCount,
            selectedSeats = mySeat?.let { setOf(it) } ?: emptySet(),
            onSeatClick = { seat -> onChange(if (mySeat == seat) null else seat) },
            singleSelect = true,
            badges = mySeat?.let { mapOf(it to stringResource(R.string.seat_role_me)) } ?: emptyMap()
        )
        TextButton(
            onClick = { onChange(null) },
            modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)
        ) {
            Text(text = stringResource(R.string.action_clear_selection))
        }
    }
}

@Composable
private fun LeaderStep(playerCount: Int, leaderSeat: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing12)) {
        SectionTitle(text = stringResource(R.string.create_step_leader))
        SeatPicker(
            playerCount = playerCount,
            selectedSeats = setOf(leaderSeat),
            onSeatClick = onChange,
            singleSelect = true,
            badges = mapOf(leaderSeat to stringResource(R.string.seat_role_leader))
        )
    }
}

@Composable
private fun TitleStep(title: String, onTitleChange: (String) -> Unit, errorMessage: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing12)) {
        SectionTitle(text = stringResource(R.string.create_step_title))
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            singleLine = true,
            label = { Text(text = stringResource(R.string.create_title_label)) },
            shape = ButtonShape,
            modifier = Modifier.fillMaxWidth()
        )
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

// ————————————————————————————————————————————————————————
// 指示器 / 问题列表 / 底栏
// ————————————————————————————————————————————————————————

@Composable
private fun StepIndicator(current: CreateStep, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CreateStep.entries.forEach { step ->
            val active = step == current
            Box(
                modifier = Modifier
                    .size(if (active) 24.dp else 12.dp)
                    .background(
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = CircleShape
                    )
            )
        }
        Text(
            text = stepLabel(current),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Dimens.Spacing4)
        )
    }
}

@Composable
private fun stepLabel(step: CreateStep): String = when (step) {
    CreateStep.PLAYER_COUNT -> stringResource(R.string.create_step_player_count)
    CreateStep.ROLES -> stringResource(R.string.create_step_roles)
    CreateStep.SEAT_LAYOUT -> stringResource(R.string.create_step_seat_layout)
    CreateStep.MY_SEAT -> stringResource(R.string.create_step_my_seat)
    CreateStep.LEADER -> stringResource(R.string.create_step_leader)
    CreateStep.TITLE -> stringResource(R.string.create_step_title)
}

@Composable
private fun IssueList(state: CreateMatchUiState) {
    if (state.issues.isEmpty()) return
    val extended = LocalExtendedColors.current
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing4)) {
        state.issues.forEach { issue ->
            Text(
                text = issueText(issue),
                style = MaterialTheme.typography.bodySmall,
                color = if (issue.isBlocking) MaterialTheme.colorScheme.error else extended.accent
            )
        }
    }
}

@Composable
private fun BottomBar(
    state: CreateMatchUiState,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onConfirm: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = Dimens.PageHorizontalPadding,
                vertical = Dimens.Spacing12
            ),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing12),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.step != CreateStep.PLAYER_COUNT) {
            OutlinedButton(
                onClick = onBack,
                shape = ButtonShape,
                modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)
            ) {
                Text(text = stringResource(R.string.action_back))
            }
        }
        Box(modifier = Modifier.weight(1f))
        if (state.step == CreateStep.TITLE) {
            Button(
                onClick = onConfirm,
                enabled = state.canCreate,
                shape = ButtonShape,
                modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)
            ) {
                Text(
                    text = if (state.isSaving) {
                        stringResource(R.string.create_saving)
                    } else {
                        stringResource(R.string.create_submit)
                    }
                )
            }
        } else {
            Button(
                onClick = onNext,
                enabled = state.canGoNext,
                shape = ButtonShape,
                modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)
            ) {
                Text(text = stringResource(R.string.action_next))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground
    )
}

private const val MIN_PLAYERS = 5
private const val MAX_PLAYERS = 10

// ————————————————————————————————————————————————————————
// 座位布置：自由拖拽 + 点击编辑名字
// ————————————————————————————————————————————————————————

@Composable
private fun SeatLayoutStep(
    playerCount: Int,
    seatNames: Map<Int, String?>,
    seatPositions: Map<Int, Pair<Float, Float>>,
    seatAvatars: Map<Int, String?>,
    onSeatNameChange: (Int, String?) -> Unit,
    onSeatPositionChange: (Int, Float, Float) -> Unit,
    onSeatAvatarChange: (Int, String?) -> Unit
) {
    val context = LocalContext.current
    var editingSeat by remember { mutableStateOf<Int?>(null) }
    var editingText by remember { mutableStateOf("") }
    val avatarPicker = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            editingSeat?.let { s -> onSeatAvatarChange(s, it.toString()) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
        SectionTitle(stringResource(R.string.create_step_seat_layout))
        Text(
            text = stringResource(R.string.create_seat_layout_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = MaterialTheme.shapes.large
                )
        ) {
            val canvasW = maxWidth
            val canvasH = maxHeight
            val cols = 4
            val rows = 4
            val padding = 16f
            val gap = 12f
            val cellW = (canvasW.value - padding * 2 - gap * (cols - 1)) / cols
            val cellH = (canvasH.value - padding * 2 - gap * (rows - 1)) / rows
            val cellSize = androidx.compose.ui.unit.Dp(cellW.coerceAtMost(cellH))

            // 画 4x4 网格背景
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    androidx.compose.foundation.Canvas(modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (padding + c * (cellW + gap)).dp.roundToPx(),
                                y = (padding + r * (cellH + gap)).dp.roundToPx()
                            )
                        }
                        .size(androidx.compose.ui.unit.Dp(cellW), androidx.compose.ui.unit.Dp(cellH))
                    ) {
                        drawRoundRect(
                            color = androidx.compose.ui.graphics.Color.LightGray.copy(alpha = 0.3f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx(), 12.dp.toPx())
                        )
                    }
                }
            }

            fun snapToGrid(x: Float, y: Float): Pair<Float, Float> {
                val col = ((x - padding + gap / 2) / (cellW + gap)).toInt().coerceIn(0, cols - 1)
                val row = ((y - padding + gap / 2) / (cellH + gap)).toInt().coerceIn(0, rows - 1)
                val sx = padding + col * (cellW + gap)
                val sy = padding + row * (cellH + gap)
                return Pair(sx, sy)
            }

            (1..playerCount).forEach { seat ->
                val saved = seatPositions[seat]
                // 从归一化坐标恢复格子位置：saved.first = col/(cols-1), saved.second = row/(rows-1)
                val initCol = saved?.let { (it.first * (cols - 1)).toInt().coerceIn(0, cols - 1) }
                    ?: ((seat - 1) % cols)
                val initRow = saved?.let { (it.second * (rows - 1)).toInt().coerceIn(0, rows - 1) }
                    ?: ((seat - 1) / cols)
                var offsetX by remember(seat, saved) {
                    mutableStateOf(padding + initCol * (cellW + gap))
                }
                var offsetY by remember(seat, saved) {
                    mutableStateOf(padding + initRow * (cellH + gap))
                }
                val avatarUri = seatAvatars[seat]

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = offsetX.dp.roundToPx(),
                                y = offsetY.dp.roundToPx()
                            )
                        }
                        .size(cellSize)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .pointerInput(seat, canvasW, canvasH, cellW, cellH, cellSize) {
                            val density = this
                            detectDragGestures(
                                onDragStart = {
                                    // 手指按下时在方块内的偏移（px）
                                },
                                onDragEnd = {
                                    val (sx, sy) = snapToGrid(offsetX, offsetY)
                                    offsetX = sx
                                    offsetY = sy
                                    val col = ((sx - padding) / (cellW + gap)).toInt()
                                    val row = ((sy - padding) / (cellH + gap)).toInt()
                                    onSeatPositionChange(seat, col / (cols - 1).toFloat(), row / (rows - 1).toFloat())
                                }
                            ) { change, drag ->
                                change.consume()
                                // drag.x/drag.y 是 px，转成 dp 值再加到 offsetX/offsetY（dp）
                                val dx = with(density) { drag.x.toDp().value }
                                val dy = with(density) { drag.y.toDp().value }
                                offsetX = (offsetX + dx).coerceIn(padding, canvasW.value - padding - cellW)
                                offsetY = (offsetY + dy).coerceIn(padding, canvasH.value - padding - cellH)
                            }
                        }
                        .clickable {
                            editingSeat = seat
                            editingText = seatNames[seat] ?: ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (avatarUri != null) {
                        val bmp = remember(avatarUri) {
                            runCatching {
                                context.contentResolver.openInputStream(android.net.Uri.parse(avatarUri))?.use {
                                    android.graphics.BitmapFactory.decodeStream(it)
                                }
                            }.getOrNull()
                        }
                        if (bmp != null) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                androidx.compose.foundation.Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                                // 右下角数字角标
                                Text(
                                    text = seat.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), androidx.compose.foundation.shape.CircleShape)
                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        } else {
                            SeatLabel(seat, seatNames[seat])
                        }
                    } else {
                        SeatLabel(seat, seatNames[seat])
                    }
                }
            }
        }

        editingSeat?.let { seat ->
            AlertDialog(
                onDismissRequest = { editingSeat = null },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                title = { Text(stringResource(R.string.create_seat_name_title, seat)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
                        OutlinedTextField(
                            value = editingText,
                            onValueChange = { editingText = it },
                            singleLine = true,
                            placeholder = { Text("$seat 号") }
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { avatarPicker.launch("image/*") }) {
                                Text(stringResource(R.string.create_seat_pick_avatar))
                            }
                            if (seatAvatars[seat] != null) {
                                TextButton(onClick = { onSeatAvatarChange(seat, null) }) {
                                    Text(stringResource(R.string.create_seat_clear_avatar))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        onSeatNameChange(seat, editingText.ifBlank { null })
                        editingSeat = null
                    }) { Text(stringResource(R.string.action_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { editingSeat = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun SeatLabel(seat: Int, name: String?) {
    val display = name?.takeIf { it.isNotBlank() } ?: "$seat 号"
    Text(
        text = display,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer
    )
}

/** 按 5 列网格算默认位置（dp）。 */
private fun defaultGridPosition(
    seat: Int,
    playerCount: Int,
    canvasW: androidx.compose.ui.unit.Dp,
    canvasH: androidx.compose.ui.unit.Dp,
    cellSize: androidx.compose.ui.unit.Dp
): androidx.compose.ui.geometry.Offset {
    val cols = 5
    val gap = 12f
    val totalGap = gap * (cols - 1)
    val cellW = (canvasW.value - totalGap - 32f) / cols  // 32 = padding
    val row = (seat - 1) / cols
    val col = (seat - 1) % cols
    val x = 16f + col * (cellW + gap)
    val y = 16f + row * (cellSize.value + gap)
    return androidx.compose.ui.geometry.Offset(x, y)
}
