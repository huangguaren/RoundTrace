package com.roundtrace.app.core.export

import com.roundtrace.app.core.data.MatchRepository
import com.roundtrace.app.core.model.EvidenceFinding
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.RuleIssue
import com.roundtrace.app.core.model.UnknownItem
import com.roundtrace.app.core.rules.GameRules

/**
 * 导入 / 导出服务（TASKS.md T60 / ARCHITECTURE §8.10）。
 *
 * 职责边界：
 * - 编解码交给 [MatchJsonCodec]，脱敏交给 [VisibilityFilter]，事务交给 [MatchRepository]；
 * - 本类只做**编排 + 校验**，因此没有 Android 依赖，可用 `FakeMatchRepository` 直接单测。
 *
 * 三条不允许的做法（DESIGN_SYNC §5.5）：
 * 1. 不允许静默覆盖已有对局 —— 写入走 `importBundle`，仓储总是新建 `MatchId`；
 * 2. 不允许把解析异常抛到界面 —— 全部映射为 [ImportOutcome.Rejected] 的枚举原因；
 * 3. 不允许导入时补全缺失字段 —— 未知就留未知。
 */
interface ImportExportService {

    /** 导出 JSON（默认脱敏）。 */
    suspend fun exportJson(
        matchId: MatchId,
        options: ExportOptions = ExportOptions.DEFAULT
    ): ExportResult

    /**
     * 导出 Markdown 摘要（默认脱敏）。
     *
     * @param findings 已按视角裁剪的证据结论；不传则第 7 节写「暂无可用结论」
     * @param unknowns 信息不足项
     */
    suspend fun exportMarkdown(
        matchId: MatchId,
        options: ExportOptions = ExportOptions.DEFAULT,
        findings: List<EvidenceFinding> = emptyList(),
        unknowns: List<UnknownItem> = emptyList()
    ): ExportResult

    /** 两段式导入的第一步：只解析并给出摘要，不写库。 */
    fun previewJson(text: String): ImportResult

    /** 校验 + 事务写入。**总是新建对局**，不覆盖任何已有数据。 */
    suspend fun importFromJson(text: String): ImportOutcome
}

/** 导出结果。 */
sealed interface ExportResult {

    data class Success(
        val text: String,
        /** 实际进入文件的事件条数（脱敏后） */
        val eventCount: Int,
        val includeSecrets: Boolean,
        val includePrivateNotes: Boolean
    ) : ExportResult

    /** 对局不存在（可能已被删除） */
    data object MatchNotFound : ExportResult

    /** 其它失败（如编码异常），detail 为技术细节，界面需另配人话文案 */
    data class Failed(val detail: String?) : ExportResult
}

/** 导入结果（服务层，含校验与落库）。 */
sealed interface ImportOutcome {

    data class Imported(
        val matchId: MatchId,
        val preview: ImportPreview
    ) : ImportOutcome

    /**
     * @param reason 枚举原因，UI 映射文案（D6）
     * @param issueCodes 规则校验未通过时的 `IssueCode` 列表（同样由 UI 映射）
     */
    data class Rejected(
        val reason: ImportFailureReason,
        val detail: String? = null,
        val issueCodes: List<String> = emptyList()
    ) : ImportOutcome
}

/** [ImportExportService] 的默认实现。 */
class DefaultImportExportService(
    private val repository: MatchRepository,
    private val rules: GameRules,
    private val codec: MatchJsonCodec = MatchJsonCodec(),
    private val markdownExporter: MarkdownExporter = MarkdownExporter(rules)
) : ImportExportService {

    override suspend fun exportJson(matchId: MatchId, options: ExportOptions): ExportResult {
        val bundle = repository.getBundle(matchId) ?: return ExportResult.MatchNotFound
        return try {
            val text = codec.encode(bundle, options)
            val filtered = VisibilityFilter.apply(bundle, options)
            ExportResult.Success(
                text = text,
                eventCount = filtered.events.size,
                includeSecrets = options.includeSecrets,
                includePrivateNotes = options.includePrivateNotes
            )
        } catch (e: Exception) {
            ExportResult.Failed(e.message)
        }
    }

    override suspend fun exportMarkdown(
        matchId: MatchId,
        options: ExportOptions,
        findings: List<EvidenceFinding>,
        unknowns: List<UnknownItem>
    ): ExportResult {
        val bundle = repository.getBundle(matchId) ?: return ExportResult.MatchNotFound
        return try {
            val text = markdownExporter.export(
                bundle = bundle,
                options = options,
                findings = findings,
                unknowns = unknowns
            )
            val filtered = VisibilityFilter.apply(bundle, options)
            ExportResult.Success(
                text = text,
                eventCount = filtered.events.size,
                includeSecrets = options.includeSecrets,
                includePrivateNotes = options.includePrivateNotes
            )
        } catch (e: Exception) {
            ExportResult.Failed(e.message)
        }
    }

    override fun previewJson(text: String): ImportResult = codec.decode(text)

    override suspend fun importFromJson(text: String): ImportOutcome {
        val parsed = codec.decode(text)
        if (parsed is ImportResult.Failure) {
            return ImportOutcome.Rejected(parsed.reason, parsed.detail)
        }
        val success = parsed as ImportResult.Success

        // 结构合法 ≠ 规则合法：开局配置必须能通过规则校验才允许落库（T60④）
        val blocking: List<RuleIssue> = rules.validateSetup(success.bundle.config)
            .filter { it.isBlocking }
        if (blocking.isNotEmpty()) {
            return ImportOutcome.Rejected(
                reason = ImportFailureReason.RULE_VALIDATION_FAILED,
                detail = blocking.joinToString("; ") { it.code.name },
                issueCodes = blocking.map { it.code.name }.distinct()
            )
        }

        return try {
            val newMatchId = repository.importBundle(success.bundle)
            ImportOutcome.Imported(newMatchId, success.preview)
        } catch (e: Exception) {
            ImportOutcome.Rejected(ImportFailureReason.STORAGE_FAILED, e.message)
        }
    }
}
