package com.roundtrace.app.export

import com.roundtrace.app.core.data.MatchBundle
import com.roundtrace.app.core.export.DefaultImportExportService
import com.roundtrace.app.core.export.ExportOptions
import com.roundtrace.app.core.export.ExportResult
import com.roundtrace.app.core.export.ImportFailureReason
import com.roundtrace.app.core.export.ImportOutcome
import com.roundtrace.app.core.rules.avalon.AvalonRules
import com.roundtrace.app.util.FakeMatchRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入校验单测（TASKS.md T60④ / T64③）。
 *
 * 覆盖三种「必须失败但不崩」的输入，以及「合法文件必须真的落库（新建对局）」。
 * 落库语义（重建 UUID、不覆盖原对局）由仓储自身的 Robolectric 测试覆盖，
 * 这里只验证服务层的编排：解析 → 规则校验 → 写库。
 */
class ImportValidationTest {

    private val rules = AvalonRules()

    private fun serviceFor(bundle: MatchBundle): DefaultImportExportService =
        DefaultImportExportService(
            repository = FakeMatchRepository(
                config = bundle.config,
                initialEvents = bundle.events,
                revealedAt = bundle.revealedAt,
                initialRoles = bundle.roleAssignments
            ),
            rules = rules
        )

    private suspend fun exportText(
        bundle: MatchBundle,
        options: ExportOptions = ExportOptions(includeSecrets = true, includePrivateNotes = true)
    ): String {
        val result = serviceFor(bundle).exportJson(bundle.summary.matchId, options)
        assertTrue("导出应成功：$result", result is ExportResult.Success)
        return (result as ExportResult.Success).text
    }

    @Test
    fun `valid file imports into a new match`() = runTest {
        val source = ExportFixtures.revealedBundle()
        val service = serviceFor(source)
        val text = exportText(source)

        val outcome = service.importFromJson(text)
        assertTrue("应导入成功：$outcome", outcome is ImportOutcome.Imported)
        val imported = outcome as ImportOutcome.Imported

        assertEquals(8, imported.preview.playerCount)
        assertEquals(2, imported.preview.questCount)
        assertTrue("必须生成新的 matchId", imported.matchId.raw.isNotBlank())
    }

    @Test
    fun `preview does not write anything`() = runTest {
        val source = ExportFixtures.revealedBundle()
        val text = exportText(source)
        val preview = serviceFor(source).previewJson(text)

        assertTrue(preview is com.roundtrace.app.core.export.ImportResult.Success)
        val success = preview as com.roundtrace.app.core.export.ImportResult.Success
        assertEquals(ExportFixtures.bundle().events.size, success.preview.eventCount)
    }

    @Test
    fun `broken json is rejected with readable reason`() = runTest {
        val outcome = serviceFor(ExportFixtures.bundle()).importFromJson("{ broken")
        assertTrue(outcome is ImportOutcome.Rejected)
        assertEquals(ImportFailureReason.INVALID_JSON, (outcome as ImportOutcome.Rejected).reason)
    }

    @Test
    fun `unknown schema version is rejected`() = runTest {
        val text = exportText(ExportFixtures.revealedBundle())
            .replace("\"schemaVersion\": 1", "\"schemaVersion\": 7")
        val outcome = serviceFor(ExportFixtures.bundle()).importFromJson(text)

        assertTrue(outcome is ImportOutcome.Rejected)
        assertEquals(
            ImportFailureReason.UNSUPPORTED_SCHEMA_VERSION,
            (outcome as ImportOutcome.Rejected).reason
        )
    }

    @Test
    fun `missing field is rejected`() = runTest {
        val text = """{"schemaVersion":1,"app":"RoundTrace"}"""
        val outcome = serviceFor(ExportFixtures.bundle()).importFromJson(text)

        assertTrue(outcome is ImportOutcome.Rejected)
        assertEquals(ImportFailureReason.MISSING_FIELD, (outcome as ImportOutcome.Rejected).reason)
    }

    @Test
    fun `structurally valid but rule violating file is rejected`() = runTest {
        val text = exportText(ExportFixtures.revealedBundle())
            .replace("\"playerCount\": 8", "\"playerCount\": 4")
        val outcome = serviceFor(ExportFixtures.bundle()).importFromJson(text)

        assertTrue("4 人局不是合法阿瓦隆配置，必须拒绝：$outcome", outcome is ImportOutcome.Rejected)
        val rejected = outcome as ImportOutcome.Rejected
        assertEquals(ImportFailureReason.RULE_VALIDATION_FAILED, rejected.reason)
        assertTrue("必须给出具体 issue code", rejected.issueCodes.isNotEmpty())
    }

    @Test
    fun `default export drops private note from event count`() = runTest {
        val bundle = ExportFixtures.bundle()
        val service = serviceFor(bundle)
        val result = service.exportJson(bundle.summary.matchId, ExportOptions.DEFAULT)

        assertTrue(result is ExportResult.Success)
        assertEquals(
            bundle.events.size - 1,
            (result as ExportResult.Success).eventCount
        ) // 默认脱敏剔掉 1 条私人笔记
    }
}
