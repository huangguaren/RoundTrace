package com.roundtrace.app.audit

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 静态护栏：防止「主线程同步解码头像」被写回别处（RT-010）。
 *
 * 审计发现同一段 `remember { BitmapFactory.decodeStream(...) }` 在三个文件里各写了一份。
 * 修完只靠「大家记得用新组件」是不够的——所以这里直接扫源码：
 * **除共享组件外，任何主源码文件都不允许再出现 `BitmapFactory.decodeStream`**。
 *
 * 另外顺手钉住 RT-009：选图必须用 `OpenDocument`（能拿到持久读取授权），
 * 不允许再出现 `GetContent`（它不保证可持久授权）。
 */
class AvatarDecodeGuardTest {

    private val sourceRoot: File = File("src/main/java/com/roundtrace/app")
    private val avatarComponent = File(sourceRoot, "core/ui/avatar/SeatAvatar.kt")

    private fun kotlinSources(): List<File> =
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    @Test
    fun `源码目录可读`() {
        assumeTrue(
            "源码目录不可读（工作目录异常）：${sourceRoot.absolutePath}",
            sourceRoot.isDirectory
        )
        assertTrue(kotlinSources().isNotEmpty())
    }

    @Test
    fun `除共享组件外不得再出现同步图片解码`() {
        assumeTrue("源码目录不可读", sourceRoot.isDirectory)
        val offenders = kotlinSources().filter { file ->
            file.absolutePath != avatarComponent.absolutePath &&
                stripComments(file.readText()).contains("BitmapFactory.decodeStream")
        }
        assertTrue(
            "这些文件又在主线程同步解码头像了，请改用 SeatAvatarImage：\n" +
                offenders.joinToString("\n") { it.relativeTo(sourceRoot).path },
            offenders.isEmpty()
        )
    }

    @Test
    fun `不得再使用 GetContent 选图`() {
        assumeTrue("源码目录不可读", sourceRoot.isDirectory)
        val offenders = kotlinSources().filter { file ->
            stripComments(file.readText()).contains("ActivityResultContracts.GetContent")
        }
        assertTrue(
            "GetContent 不保证可持久读取授权（RT-009），请改用 OpenDocument：\n" +
                offenders.joinToString("\n") { it.relativeTo(sourceRoot).path },
            offenders.isEmpty()
        )
    }

    @Test
    fun `共享组件确实承担了解码职责`() {
        assumeTrue("源码目录不可读", sourceRoot.isDirectory)
        assertTrue(
            "共享组件应包含解码实现",
            avatarComponent.readText().contains("BitmapFactory.decodeStream")
        )
        // 且必须放到后台线程
        val text = avatarComponent.readText()
        assertTrue(
            "共享组件应在 IO 线程解码",
            text.contains("Dispatchers.IO")
        )
    }

    /** 去掉行注释与块注释，避免把「文档里提到 decodeStream」误判成真实调用。 */
    private fun stripComments(source: String): String = source
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .replace(Regex("//[^\\n]*"), "")

    @Test
    fun `备份规则必须覆盖 DataStore 目录`() {
        val backupRules = File("src/main/res/xml/backup_rules.xml")
        val extractionRules = File("src/main/res/xml/data_extraction_rules.xml")
        assumeTrue("资源目录不可读", backupRules.isFile && extractionRules.isFile)
        assertTrue(
            "backup_rules.xml 漏了 DataStore（files/datastore/），换机后会丢主题与默认人数",
            backupRules.readText().contains("""path="datastore/"""")
        )
        val extractionText = extractionRules.readText()
        assertFalse(
            "data_extraction_rules.xml 漏了 DataStore",
            !extractionText.contains("""path="datastore/"""")
        )
    }
}
