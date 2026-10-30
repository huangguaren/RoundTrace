package com.roundtrace.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundtrace.app.core.data.SettingsRepository
import com.roundtrace.app.core.data.ThemeMode
import com.roundtrace.app.core.export.ImportExportService
import com.roundtrace.app.core.export.ImportOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 设置页 ViewModel（TASKS.md T63①）。
 *
 * 三项设置全部走 [SettingsRepository]（DataStore）：写入后 `Flow` 立即发射新值，
 * 因此「写 DataStore 后立即生效且重启保留」是数据流天然保证的，不需要额外刷新逻辑。
 *
 * 导入走 [ImportExportService]（与详情页同一入口）：导入总是新建对局，不覆盖已有数据。
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val importExport: ImportExportService
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                settingsRepository.themeMode,
                settingsRepository.defaultPlayerCount,
                settingsRepository.includePrivateByDefault
            ) { themeMode, playerCount, includePrivate ->
                Triple(themeMode, playerCount, includePrivate)
            }.collect { (themeMode, playerCount, includePrivate) ->
                _state.value = _state.value.copy(
                    isLoading = false,
                    themeMode = themeMode,
                    defaultPlayerCount = playerCount,
                    includePrivateByDefault = includePrivate,
                    errorMessage = null
                )
            }
        }
    }

    fun onAction(action: SettingsAction) {
        viewModelScope.launch {
            when (action) {
                is SettingsAction.SetThemeMode -> runCatching {
                    settingsRepository.setThemeMode(action.mode)
                }.onFailure { throwable ->
                    _state.value = _state.value.copy(
                        errorMessage = throwable.message ?: "保存设置失败"
                    )
                }

                is SettingsAction.SetDefaultPlayerCount -> runCatching {
                    settingsRepository.setDefaultPlayerCount(action.playerCount)
                }.onFailure { throwable ->
                    _state.value = _state.value.copy(
                        errorMessage = throwable.message ?: "保存设置失败"
                    )
                }

                is SettingsAction.SetIncludePrivateByDefault -> runCatching {
                    settingsRepository.setIncludePrivateByDefault(action.enabled)
                }.onFailure { throwable ->
                    _state.value = _state.value.copy(
                        errorMessage = throwable.message ?: "保存设置失败"
                    )
                }

                is SettingsAction.ImportJson -> importJson(action.text)

                SettingsAction.ClearImportMessage ->
                    _state.value = _state.value.copy(importMessage = null)
            }
        }
    }

    private fun importJson(text: String) {
        viewModelScope.launch {
            val outcome = runCatching { importExport.importFromJson(text) }
                .getOrElse { throwable ->
                    ImportOutcome.Rejected(
                        reason = com.roundtrace.app.core.export.ImportFailureReason.STORAGE_FAILED,
                        detail = throwable.message
                    )
                }
            val message = when (outcome) {
                is ImportOutcome.Imported ->
                    "已导入：${outcome.preview.playerCount} 人局 · ${outcome.preview.eventCount} 条事件"
                is ImportOutcome.Rejected ->
                    "导入被拒绝：${outcome.detail ?: outcome.reason.name}"
            }
            _state.value = _state.value.copy(importMessage = message)
        }
    }

    /** 供测试断言：当前主题模式。 */
    internal fun currentThemeMode(): ThemeMode = _state.value.themeMode
}
