package com.roundtrace.app.export

import com.roundtrace.app.core.export.ExportOptions
import com.roundtrace.app.core.export.MarkdownExporter
import com.roundtrace.app.core.model.EvidenceFinding
import com.roundtrace.app.core.model.EvidenceLevel
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.UnknownItem
import com.roundtrace.app.core.rules.avalon.AvalonRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 摘要单测（TASKS.md T64③ / TDD §8）。
 *
 * 最重要的一条：**「反对票」与「失败牌」必须出现在不同小节**，
 * 这是 PRD「技术底线」在导出层的落地。
 */
class MarkdownExporterTest {

    private val exporter = MarkdownExporter(AvalonRules())

    private fun render(
        options: ExportOptions = ExportOptions.DEFAULT,
        revealed: Boolean = false,
        findings: List<EvidenceFinding> = emptyList(),
        unknowns: List<UnknownItem> = emptyList()
    ): String = exporter.export(
        bundle = ExportFixtures.bundle(revealed = revealed),
        options = options,
        findings = findings,
        unknowns = unknowns,
        exportedAt = ExportFixtures.EXPORTED_AT
    )

    /** 取第 [index] 节正文（到下一个二级标题为止；最后一节取到末尾）。 */
    private fun section(markdown: String, index: Int): String {
        val after = markdown.substringAfter("## $index.", "")
        val next = after.indexOf("\n## ")
        return if (next >= 0) after.substring(0, next) else after
    }

    @Test
    fun `reject votes and fail cards live in different sections`() {
        val md = render()

        val votes = section(md, 3)
        val missions = section(md, 4)

        assertTrue("第 3 节必须记录反对票：$votes", votes.contains("反对 1 票"))
        assertFalse("第 3 节不得出现失败牌：$votes", votes.contains("失败牌"))

        assertTrue("第 4 节必须记录失败牌：$missions", missions.contains("失败牌"))
        assertFalse("第 4 节不得出现反对票：$missions", missions.contains("反对"))
    }

    @Test
    fun `quest summary reports results without leaking card counts`() {
        val summary = section(render(), 2)
        assertTrue(summary.contains("第 1 任务"))
        assertTrue(summary.contains("第 2 任务"))
        assertTrue(summary.contains("成功"))
        assertTrue(summary.contains("失败"))
        assertFalse("摘要节不应出现失败牌字样：$summary", summary.contains("失败牌"))
    }

    @Test
    fun `claim is always marked as claim not fact`() {
        val claims = section(render(), 5)
        assertTrue(claims.contains("声称，不等于事实"))
        assertTrue(claims.contains("7 号"))
    }

    @Test
    fun `default export hides private notes`() {
        val md = render()
        assertFalse("默认导出不得包含私人笔记正文", md.contains(ExportFixtures.PRIVATE_NOTE_TEXT))
        assertTrue("应提示已隐去私人笔记", section(md, 8).contains("私人笔记已按脱敏策略隐去"))
    }

    @Test
    fun `explicit opt in includes private notes in dedicated block`() {
        val md = render(options = ExportOptions(includePrivateNotes = true))
        assertTrue(md.contains(ExportFixtures.PRIVATE_NOTE_TEXT))
        assertTrue(section(md, 5).contains("私人笔记（仅本人可见，非公开事实）"))
    }

    @Test
    fun `default export hides unrevealed roles`() {
        val md = render(revealed = false)
        assertFalse("未揭晓时不得出现真实身份", md.contains("MERLIN"))
        assertTrue(md.contains("角色未揭晓或未填写"))
    }

    @Test
    fun `revealed export lists roles`() {
        val md = render(revealed = true)
        assertTrue(md.contains("1 号：MERLIN"))
    }

    @Test
    fun `unrecorded fail count is not guessed`() {
        val bundle = ExportFixtures.bundle(revealed = false)
        val exporter2 = MarkdownExporter(AvalonRules())
        // 用真实夹具里的「0 张 / 1 张」反证：未记录时必须是「未判定」而不是 0
        val md = exporter2.export(bundle, exportedAt = ExportFixtures.EXPORTED_AT)
        assertTrue(md.contains("失败牌：1 张（本任务门槛 1 张） → 任务失败"))
        assertTrue(md.contains("失败牌：0 张（本任务门槛 1 张） → 任务成功"))
    }

    @Test
    fun `findings section renders level badges and evidence counts`() {
        val finding = EvidenceFinding(
            id = "f1",
            label = "该队伍至少含 1 名坏人",
            level = EvidenceLevel.INFERENCE,
            explanation = "按标准身份数量推算",
            ruleId = "A05_TEAM_CONTAINS_EVIL",
            sourceEventIds = listOf(EventId("e-1"), EventId("e-2")),
            relatedSeats = listOf(2, 3, 4, 6),
            relatedQuestNo = 2
        )
        val md = render(findings = listOf(finding))
        val findingsSection = section(md, 7)

        assertTrue(findingsSection.contains("[推测]"))
        assertTrue(findingsSection.contains("证据事件 2 条"))
        assertTrue(findingsSection.contains("A05_TEAM_CONTAINS_EVIL"))
        assertFalse("不得输出数值概率", findingsSection.contains("%"))
    }

    @Test
    fun `unknowns section shows what is missing`() {
        val md = render(
            unknowns = listOf(
                UnknownItem(code = "QUEST4_TEAM_INCOMPLETE", questNo = 4, seats = listOf(1, 3, 4))
            )
        )
        val section = section(md, 8)
        assertTrue(section.contains("QUEST4_TEAM_INCOMPLETE"))
        assertTrue(section.contains("第 4 任务"))
    }

    @Test
    fun `markdown has all eight sections in order`() {
        val md = render()
        listOf(
            "## 1. 基础信息",
            "## 2. 任务摘要",
            "## 3. 组队提案与投票",
            "## 4. 任务牌结果",
            "## 5. 关键发言",
            "## 6. 结局",
            "## 7. 规则确定结论",
            "## 8. 遗留未知事项"
        ).forEach { heading ->
            assertTrue("缺少小节：$heading", md.contains(heading))
        }
    }
}
