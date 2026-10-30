package com.roundtrace.app.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundtrace.app.core.data.MatchRepository
import com.roundtrace.app.core.export.ExportOptions
import com.roundtrace.app.core.export.ExportResult
import com.roundtrace.app.core.export.ImportExportService
import com.roundtrace.app.core.export.ImportOutcome
import com.roundtrace.app.core.export.ShareLauncher
import com.roundtrace.app.core.model.MatchId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 对局详情 ViewModel（TASKS.md T63③）。
 *
 * 职责边界：
 * - 只产出「待确认的导出文本」与「导入结果」，**不写文件、不弹系统界面**
 *   （文件与 Intent 需要 Context，属于 UI 层；见 [ShareLauncher]）；
 * - 导入文本由 UI 从用户选择的文件读入后传入，因此本类**不持有 Context**（D7）。
 */
class MatchDetailViewModel(
    private val matchId: MatchId,
    private val repository: MatchRepository,
    private val importExport: ImportExportService
) : ViewModel() {

    private val _state = MutableStateFlow(MatchDetailUiState())
    val state: StateFlow<MatchDetailUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            val bundle = repository.getBundle(matchId)
            _state.value = if (bundle == null) {
                _state.value.copy(isLoading = false, errorMessage = "对局不存在")
            } else {
                _state.value.copy(
                    isLoading = false,
                    title = bundle.summary.title,
                    playerCount = bundle.summary.playerCount,
                    eventCount = bundle.events.size,
                    status = bundle.summary.status,
                    revealedAt = bundle.revealedAt,
                    errorMessage = null
                )
            }
        }
    }

    fun onAction(action: MatchDetailAction) {
        when (action) {
            is MatchDetailAction.SetIncludeSecrets ->
                _state.value = _state.value.copy(includeSecrets = action.enabled)

            is MatchDetailAction.SetIncludePrivateNotes ->
                _state.value = _state.value.copy(includePrivateNotes = action.enabled)

            MatchDetailAction.RequestJsonExport -> requestExport(markdown = false)
            MatchDetailAction.RequestMarkdownExport -> requestExport(markdown = true)
            MatchDetailAction.ClearPendingExport ->
                _state.value = _state.value.copy(pendingExport = null)

            is MatchDetailAction.Rename -> rename(action.title)
            MatchDetailAction.Delete -> delete()
            is MatchDetailAction.ImportJson -> importJson(action.text)
            MatchDetailAction.ClearMessage -> _state.value = _state.value.copy(message = null)
        }
    }

    private fun options(): ExportOptions = ExportOptions(
        includePrivateNotes = _state.value.includePrivateNotes,
        includeSecrets = _state.value.includeSecrets
    )

    private fun requestExport(markdown: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true, message = null)
            val options = options()
            val result = if (markdown) {
                importExport.exportMarkdown(matchId, options)
            } else {
                importExport.exportJson(matchId, options)
            }
            _state.value = when (result) {
                is ExportResult.Success -> _state.value.copy(
                    isBusy = false,
                    pendingExport = PendingExport(
                        fileName = fileNameFor(markdown),
                        mimeType = if (markdown) {
                            ShareLauncher.MIME_MARKDOWN
                        } else {
                            ShareLauncher.MIME_JSON
                        },
                        content = result.text,
                        scope = ShareLauncher.scopeOf(options)
                    )
                )

                ExportResult.MatchNotFound -> _state.value.copy(
                    isBusy = false,
                    message = DetailMessage.ExportFailed(detail = null)
                )

                is ExportResult.Failed -> _state.value.copy(
                    isBusy = false,
                    message = DetailMessage.ExportFailed(result.detail)
                )
            }
        }
    }

    private fun fileNameFor(markdown: Boolean): String {
        val suffix = if (markdown) "md" else "json"
        val safeTitle = ShareLauncher.sanitizeFileName(_state.value.title)
        return "roundtrace-$safeTitle-${matchId.raw.take(8)}.$suffix"
    }

    private fun rename(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty() || trimmed == _state.value.title) return
        viewModelScope.launch {
            runCatching { repository.renameMatch(matchId, trimmed) }
                .onSuccess {
                    _state.value = _state.value.copy(title = trimmed, message = DetailMessage.Renamed)
                }
                .onFailure { throwable ->
                    _state.value = _state.value.copy(errorMessage = throwable.message)
                }
        }
    }

    private fun delete() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true)
            runCatching { repository.deleteMatch(matchId) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        isBusy = false,
                        deleted = true,
                        message = DetailMessage.Deleted
                    )
                }
                .onFailure { throwable ->
                    _state.value = _state.value.copy(
                        isBusy = false,
                        errorMessage = throwable.message ?: "删除失败"
                    )
                }
        }
    }

    /**
     * 导入：解析 + 规则校验 + 事务写入全部在 [ImportExportService] 内完成。
     *
     * 这里只把结果翻译成 UI 可展示的 [DetailMessage]，**绝不抛异常到界面**。
     */
    private fun importJson(text: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true, message = null)
            val outcome = runCatching { importExport.importFromJson(text) }
                .getOrElse { throwable ->
                    ImportOutcome.Rejected(
                        reason = com.roundtrace.app.core.export.ImportFailureReason.STORAGE_FAILED,
                        detail = throwable.message
                    )
                }
            _state.value = when (outcome) {
                is ImportOutcome.Imported -> _state.value.copy(
                    isBusy = false,
                    message = DetailMessage.Imported(
                        eventCount = outcome.preview.eventCount,
                        playerCount = outcome.preview.playerCount
                    )
                )

                is ImportOutcome.Rejected -> _state.value.copy(
                    isBusy = false,
                    message = DetailMessage.ImportRejected(
                        reason = outcome.reason.name,
                        detail = outcome.detail
                    )
                )
            }
        }
    }
}
