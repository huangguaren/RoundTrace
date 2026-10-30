package com.roundtrace.app.feature.detail

import com.roundtrace.app.core.export.ShareLauncher
import com.roundtrace.app.core.model.MatchStatus

/**
 * 待确认的导出产物（TASKS.md T62④：分享前必须展示「将包含 / 不含」）。
 *
 * 之所以先「挂起」而不是直接写文件：分享是**对外动作**，
 * 必须先让用户看到本次导出的脱敏范围。
 */
data class PendingExport(
    val fileName: String,
    val mimeType: String,
    val content: String,
    val scope: ShareLauncher.ShareScope
)

/**
 * 对局详情页 UiState（TASKS.md T63③）。
 *
 * @param pendingExport 非空时 UI 必须弹「包含范围」确认框
 * @param message 一次性反馈（导入结果 / 导出失败 / 已删除），UI 展示后应清除
 */
data class MatchDetailUiState(
    val isLoading: Boolean = true,
    val title: String = "",
    val playerCount: Int = 0,
    val eventCount: Int = 0,
    val status: MatchStatus = MatchStatus.DRAFT,
    val revealedAt: Long? = null,
    /** 导出是否包含未揭晓的真实身份 */
    val includeSecrets: Boolean = false,
    /** 导出是否包含私人笔记 */
    val includePrivateNotes: Boolean = false,
    val isBusy: Boolean = false,
    val pendingExport: PendingExport? = null,
    val message: DetailMessage? = null,
    /** 对局已被删除，UI 应导航回列表 */
    val deleted: Boolean = false,
    val errorMessage: String? = null
) {

    /** 是否已揭晓（决定默认能否包含真实身份）。 */
    val isRevealed: Boolean
        get() = revealedAt != null
}

/** 一次性反馈（只放稳定 key，文案由 UI 用 `stringResource` 映射，D6）。 */
sealed interface DetailMessage {

    data object Renamed : DetailMessage

    data object Deleted : DetailMessage

    data class Exported(val fileName: String) : DetailMessage

    data class ExportFailed(val detail: String?) : DetailMessage

    data class Imported(val eventCount: Int, val playerCount: Int) : DetailMessage

    data class ImportRejected(val reason: String, val detail: String?) : DetailMessage
}

/** 详情页动作。 */
sealed interface MatchDetailAction {

    data class SetIncludeSecrets(val enabled: Boolean) : MatchDetailAction

    data class SetIncludePrivateNotes(val enabled: Boolean) : MatchDetailAction

    /** 生成 JSON 导出，结果进入 `pendingExport` 等待确认。 */
    data object RequestJsonExport : MatchDetailAction

    /** 生成 Markdown 导出，结果进入 `pendingExport` 等待确认。 */
    data object RequestMarkdownExport : MatchDetailAction

    /** 用户确认分享/保存后清除挂起项。 */
    data object ClearPendingExport : MatchDetailAction

    data class Rename(val title: String) : MatchDetailAction

    data object Delete : MatchDetailAction

    /** 导入文本（由 UI 从用户选择的文件读入后传入，ViewModel 不碰 Context）。 */
    data class ImportJson(val text: String) : MatchDetailAction

    data object ClearMessage : MatchDetailAction
}
