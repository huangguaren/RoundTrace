package com.roundtrace.app.core.media

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** 验证生效的 XML 节点，避免只匹配到注释或只覆盖云备份、漏掉设备迁移。 */
class DataStoreBackupRulesTest {
    private fun includePaths(file: String, section: String): Set<Pair<String, String>> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder().parse(File("src/main/res/xml/$file"))
        val sections = document.getElementsByTagName(section)
        assertEquals("$file 中必须只有一个 $section", 1, sections.length)
        val includes = (sections.item(0) as Element).getElementsByTagName("include")
        return (0 until includes.length).map { index ->
            val item = includes.item(index) as Element
            item.getAttribute("domain") to item.getAttribute("path")
        }.toSet()
    }

    private fun assertExpectedScope(includes: Set<Pair<String, String>>) {
        assertTrue(includes.contains("database" to "."))
        assertTrue(includes.contains("sharedpref" to "."))
        assertEquals(setOf("file" to "datastore/"), includes.filter { it.first == "file" }.toSet())
    }

    @Test
    fun legacyBackupIncludesOnlyDataStoreWithinAppFiles() {
        assertExpectedScope(includePaths("backup_rules.xml", "full-backup-content"))
    }

    @Test
    fun cloudBackupIncludesOnlyDataStoreWithinAppFiles() {
        assertExpectedScope(includePaths("data_extraction_rules.xml", "cloud-backup"))
    }

    @Test
    fun deviceTransferIncludesOnlyDataStoreWithinAppFiles() {
        assertExpectedScope(includePaths("data_extraction_rules.xml", "device-transfer"))
    }
}
