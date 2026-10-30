package com.roundtrace.app.feature.detail

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.roundtrace.app.R
import com.roundtrace.app.core.export.ShareLauncher
import com.roundtrace.app.core.ui.component.ConfirmDialog
import com.roundtrace.app.core.ui.component.StatusBadge
import com.roundtrace.app.core.ui.component.toBadgeState
import com.roundtrace.app.core.ui.theme.ButtonShape
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.PanelShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 对局详情页（TASKS.md T63③）。
 *
 * 所有按钮都有真实行为：
 * - 分享 JSON / Markdown → 生成文本 → **先弹「包含范围」确认框** → 写 `cacheDir/exports/`
 *   → `FileProvider` + `ACTION_SEND` 分享；
 * - 导出到文件 → `ACTION_CREATE_DOCUMENT`；
 * - 导入 → `ACTION_OPEN_DOCUMENT` 读文本 → `ImportExportService`（失败零残留）；
 * - 重命名 / 删除（二次确认）。
 *
 * 全程**不申请存储权限**（T62③）。
 */
@Composable
fun MatchDetailScreen(
    state: MatchDetailUiState,
    onAction: (MatchDetailAction) -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var renameVisible by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(state.title) }
    var deleteVisible by remember { mutableStateOf(false) }

    // 导入：ACTION_OPEN_DOCUMENT（无需权限）
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()
                            ?.use { it.readText() }
                    }.getOrNull()
                }
                if (text != null) {
                    onAction(MatchDetailAction.ImportJson(text))
                }
            }
        }
    }

    // 导出到文件：ACTION_CREATE_DOCUMENT（无需权限）
    var pendingForDocument by remember { mutableStateOf<PendingExport?>(null) }
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(ShareLauncher.MIME_JSON)
    ) { uri ->
        val pending = pendingForDocument
        if (uri != null && pending != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(pending.content.toByteArray(Charsets.UTF_8))
                        }
                    }
                }
            }
        }
        pendingForDocument = null
        onAction(MatchDetailAction.ClearPendingExport)
    }

    LaunchedEffect(state.deleted) {
        if (state.deleted) onDeleted()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.PageHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.Spacing16)
    ) {
        // —— 元信息 ——
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = PanelShape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = Dimens.CardElevation
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
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    StatusBadge(state = state.status.toBadgeState())
                }
                Text(
                    text = stringResource(
                        R.string.detail_meta_line,
                        state.playerCount,
                        state.eventCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (state.isRevealed) {
                        stringResource(R.string.detail_revealed)
                    } else {
                        stringResource(R.string.detail_not_revealed)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // —— 编辑 ——
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
            OutlinedButton(
                onClick = {
                    renameText = state.title
                    renameVisible = true
                },
                shape = ButtonShape,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Dimens.MinTouchTarget)
            ) { Text(text = stringResource(R.string.action_rename)) }
            OutlinedButton(
                onClick = { deleteVisible = true },
                shape = ButtonShape,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Dimens.MinTouchTarget)
            ) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // —— 导出范围 ——
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = PanelShape,
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
                Text(
                    text = stringResource(R.string.detail_export_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                ScopeRow(
                    label = stringResource(R.string.detail_include_secrets),
                    hint = stringResource(R.string.detail_include_secrets_hint),
                    checked = state.includeSecrets,
                    enabled = state.isRevealed,
                    onCheckedChange = {
                        onAction(MatchDetailAction.SetIncludeSecrets(it))
                    }
                )
                ScopeRow(
                    label = stringResource(R.string.detail_include_private),
                    hint = stringResource(R.string.detail_include_private_hint),
                    checked = state.includePrivateNotes,
                    enabled = true,
                    onCheckedChange = {
                        onAction(MatchDetailAction.SetIncludePrivateNotes(it))
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
                    Button(
                        onClick = { onAction(MatchDetailAction.RequestJsonExport) },
                        enabled = !state.isBusy,
                        shape = ButtonShape,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Dimens.MinTouchTarget)
                    ) { Text(text = stringResource(R.string.detail_share_json)) }
                    Button(
                        onClick = { onAction(MatchDetailAction.RequestMarkdownExport) },
                        enabled = !state.isBusy,
                        shape = ButtonShape,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Dimens.MinTouchTarget)
                    ) { Text(text = stringResource(R.string.detail_share_markdown)) }
                }
            }
        }

        // —— 导入 ——
        OutlinedButton(
            onClick = { openDocumentLauncher.launch(arrayOf(ShareLauncher.MIME_JSON)) },
            enabled = !state.isBusy,
            shape = ButtonShape,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.MinTouchTarget)
        ) { Text(text = stringResource(R.string.settings_import_action)) }

        // —— 一次性反馈 ——
        state.message?.let { message ->
            Text(
                text = detailMessageText(message),
                style = MaterialTheme.typography.bodySmall,
                color = when (message) {
                    is DetailMessage.ExportFailed,
                    is DetailMessage.ImportRejected -> MaterialTheme.colorScheme.error

                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        state.errorMessage?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    // —— 分享范围确认（T62④）——
    state.pendingExport?.let { pending ->
        ConfirmDialog(
            title = stringResource(R.string.detail_share_confirm_title),
            message = shareScopeMessage(pending.scope),
            confirmLabel = stringResource(R.string.detail_share_confirm_action),
            onConfirm = {
                shareToSystem(context, pending)
                onAction(MatchDetailAction.ClearPendingExport)
            },
            onDismiss = { onAction(MatchDetailAction.ClearPendingExport) },
            destructive = false
        )
    }

    if (renameVisible) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameVisible = false },
            title = { Text(text = stringResource(R.string.card_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.card_rename_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAction(MatchDetailAction.Rename(renameText))
                        renameVisible = false
                    }
                ) { Text(text = stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameVisible = false }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (deleteVisible) {
        ConfirmDialog(
            title = stringResource(R.string.card_delete_title),
            message = stringResource(R.string.card_delete_message),
            onConfirm = {
                deleteVisible = false
                onAction(MatchDetailAction.Delete)
            },
            onDismiss = { deleteVisible = false }
        )
    }
}

/** 写缓存 + 拉起系统分享面板（不申请任何权限）。 */
private fun shareToSystem(context: Context, pending: PendingExport) {
    val file = ShareLauncher.writeToCache(context, pending.fileName, pending.content)
    val chooser = ShareLauncher.shareIntent(
        context = context,
        file = file,
        mimeType = pending.mimeType,
        subject = pending.fileName
    )
    context.startActivity(chooser)
}

@Composable
private fun ScopeRow(
    label: String,
    hint: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing12)
    ) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 分享范围文案（T62④：明确「将包含 / 不含」）。 */
@Composable
private fun shareScopeMessage(scope: ShareLauncher.ShareScope): String {
    val includes = buildList {
        add(stringResource(R.string.detail_scope_public_events))
        if (scope.includeSecrets) add(stringResource(R.string.detail_scope_secrets))
        if (scope.includePrivateNotes) add(stringResource(R.string.detail_scope_private_notes))
    }
    val excludes = buildList {
        if (!scope.includeSecrets) add(stringResource(R.string.detail_scope_secrets))
        if (!scope.includePrivateNotes) add(stringResource(R.string.detail_scope_private_notes))
    }
    return buildString {
        append(stringResource(R.string.detail_scope_include_prefix))
        append(includes.joinToString("、"))
        if (excludes.isNotEmpty()) {
            append("\n")
            append(stringResource(R.string.detail_scope_exclude_prefix))
            append(excludes.joinToString("、"))
        }
    }
}

/** 反馈文案映射（D6：领域/VM 不产中文）。 */
@Composable
private fun detailMessageText(message: DetailMessage): String = when (message) {
    DetailMessage.Renamed -> stringResource(R.string.detail_msg_renamed)
    DetailMessage.Deleted -> stringResource(R.string.detail_msg_deleted)
    is DetailMessage.Exported -> stringResource(R.string.detail_msg_exported, message.fileName)
    is DetailMessage.ExportFailed -> stringResource(R.string.detail_msg_export_failed)
    is DetailMessage.Imported -> stringResource(
        R.string.detail_msg_imported,
        message.playerCount,
        message.eventCount
    )

    is DetailMessage.ImportRejected -> stringResource(
        R.string.detail_msg_import_rejected,
        message.reason
    )
}
