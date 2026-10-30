package com.roundtrace.app.feature.settings

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.roundtrace.app.BuildConfig
import com.roundtrace.app.R
import com.roundtrace.app.core.data.ThemeMode
import com.roundtrace.app.core.export.ShareLauncher
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.ui.theme.ButtonShape
import com.roundtrace.app.core.ui.theme.Dimens
import com.roundtrace.app.core.ui.theme.PanelShape
import com.roundtrace.app.core.ui.theme.PillShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页（TASKS.md T63①）。
 *
 * 四个区块：主题 / 隐私 / 默认预设 / 数据与关于。
 * 每个控件都绑定真实行为——没有「规划中」的可点假入口。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 导入：ACTION_OPEN_DOCUMENT（无需存储权限，T62③）
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
                    onAction(SettingsAction.ImportJson(text))
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.PageHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.Spacing16)
    ) {
        // —— 主题 ——
        SettingsSection(title = stringResource(R.string.settings_theme_title)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.themeMode == mode,
                        onClick = { onAction(SettingsAction.SetThemeMode(mode)) },
                        label = {
                            Text(
                                text = themeModeLabel(mode),
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        shape = PillShape,
                        modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)
                    )
                }
            }
        }

        // —— 隐私 ——
        SettingsSection(title = stringResource(R.string.settings_privacy_title)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.MinTouchTarget),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing12)
            ) {
                Switch(
                    checked = state.includePrivateByDefault,
                    onCheckedChange = {
                        onAction(SettingsAction.SetIncludePrivateByDefault(it))
                    }
                )
                Column {
                    Text(
                        text = stringResource(R.string.settings_include_private_label),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.settings_include_private_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // —— 默认预设（用人数表达，与 ruleset 的 avalon.standard.Np 一一对应）——
        SettingsSection(title = stringResource(R.string.settings_default_preset_title)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
                state.supportedPlayerCounts.forEach { count ->
                    FilterChip(
                        selected = state.defaultPlayerCount == count,
                        onClick = { onAction(SettingsAction.SetDefaultPlayerCount(count)) },
                        label = {
                            Text(
                                text = "$count",
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        shape = PillShape,
                        modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)
                    )
                }
            }
            Text(
                text = stringResource(
                    R.string.settings_default_preset_value,
                    state.defaultPresetId
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // —— 数据与关于 ——
        SettingsSection(title = stringResource(R.string.settings_data_title)) {
            OutlinedButton(
                onClick = { openDocumentLauncher.launch(arrayOf(ShareLauncher.MIME_JSON)) },
                shape = ButtonShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.MinTouchTarget)
            ) {
                Text(text = stringResource(R.string.settings_import_action))
            }
            Text(
                text = stringResource(R.string.settings_about_line, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    R.string.settings_about_ruleset,
                    AvalonPreset.RULESET_ID,
                    AvalonPreset.RULESET_VERSION
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.settings_about_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (state.importMessage != null) {
            Text(
                text = state.importMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.errorMessage != null) {
            Text(
                text = state.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
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
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            content()
        }
    }
}

@Composable
private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
    ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
    ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
}
