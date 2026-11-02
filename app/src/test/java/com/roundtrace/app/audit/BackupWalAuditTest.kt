package com.roundtrace.app.audit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.core.database.backupBeforeMigration
import com.roundtrace.app.database.LegacyV3Database
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QA 阶段 D · V03：WAL 有近期写入时的迁移前备份。
 *
 * ## 这条为什么容易做成假绿
 *
 * 直接 `sourceFile.copyTo(bak)` 然后断言「bak 能打开、行数对」是**假绿**：
 * 此时 WAL 已经在上一次连接关闭时被 checkpoint 掉了，根本没有 pending frames。
 * 本用例的做法是先把 WAL **真的撑起来**，再用「不做 checkpoint 的朴素复制」做**对照**——
 * 对照必须丢数据，才能证明后面的备份逻辑里 checkpoint 这一步是**承重**的。
 *
 * ## 本环境做不到的部分（如实声明）
 *
 * `PRAGMA wal_checkpoint(TRUNCATE)` 在「另一连接持读锁」时应当返回 busy=1。
 * Robolectric 的 SQLite 是多连接共享同一底层句柄，无法制造 busy（探针实测 busy=0），
 * 因此「checkpoint 未完成」这一分支 **NOT RUN**，只能靠代码走查（见报告 P2-1）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupWalAuditTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun v03_backup_includes_committed_frames_that_only_exist_in_wal() {
        val name = "audit-v03.db"
        val source = LegacyV3Database.create(context, name) { db ->
            LegacyV3Database.insertV3Match(db, "m1")
            LegacyV3Database.insertV3Event(db, "e1", "m1", 1)
        }

        // ① 打开一个长期连接并切到 WAL，再提交第 2 行
        val helper = LegacyV3Database.openRaw(context, source, version = 3)
        try {
            val db = helper.writableDatabase
            db.query("PRAGMA journal_mode=WAL").use { it.moveToFirst() }
            db.query("PRAGMA journal_mode").use { cursor ->
                cursor.moveToFirst()
                assertEquals("前置条件：必须真的在用 WAL", "wal", cursor.getString(0))
            }

            LegacyV3Database.insertV3Event(db, "e2", "m1", 2)

            val wal = File(source.absolutePath + "-wal")
            assertTrue("前置条件：必须存在 -wal 文件", wal.exists())
            assertTrue("前置条件：-wal 必须非空（说明 e2 还没合并进主文件）", wal.length() > 0)
            println("[AUDIT-D] WAL 大小=${wal.length()}，主文件=${source.length()}")

            // ② 对照组：**不做 checkpoint** 的朴素复制 → 必然丢 e2
            val naive = File(source.absolutePath + ".naive-copy")
            source.copyTo(naive, overwrite = true)
            val naiveHelper = LegacyV3Database.openRaw(context, naive, version = 3)
            val naiveIds = try {
                idsOf(naiveHelper.readableDatabase)
            } finally {
                naiveHelper.close()
            }
            println("[AUDIT-D] 朴素复制（无 checkpoint）拿到的 ids=$naiveIds")
            assertEquals(
                "对照：不做 checkpoint 就该丢数据——这正是「备份看着成功但其实缺最近事务」的样子",
                listOf("e1"),
                naiveIds
            )

            // ③ 真正的迁移前备份
            backupBeforeMigration(context, name)

            val bak = File(source.absolutePath + ".bak.v3")
            assertTrue("必须产生 .bak.v3", bak.exists())
            assertTrue("备份不得为空", bak.length() > 0)

            val bakHelper = LegacyV3Database.openRaw(context, bak, version = 3)
            try {
                val raw = bakHelper.readableDatabase
                assertEquals(
                    "备份必须包含 checkpoint 后才合并进主文件的 e2",
                    listOf("e1", "e2"),
                    idsOf(raw)
                )
                assertEquals("备份必须仍停在 v3（可回退）", 3, LegacyV3Database.userVersion(raw))
                assertEquals("matches 也要在", 1, LegacyV3Database.rowsOf(raw, "matches"))

                val columns = raw.query("PRAGMA table_info(match_events)").use { cursor ->
                    val out = mutableListOf<String>()
                    while (cursor.moveToNext()) {
                        out += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    }
                    out
                }
                assertFalse("v3 备份不得含 v4 列 logicalPosition", columns.contains("logicalPosition"))
                assertFalse("v3 备份不得含 v4 列 revisionSeq", columns.contains("revisionSeq"))
            } finally {
                bakHelper.close()
            }
        } finally {
            helper.close()
        }
    }

    /**
     * 备份**不能**在库不存在时凭空造文件，也不能把 `.bak` 写坏成非法库。
     * （与 `DatabaseBackupTest` 的「库不存在」用例互补：这里额外断言不产生任何临时文件残留。）
     */
    @Test
    fun v03b_missing_database_produces_no_artifact() {
        val name = "audit-v03-missing.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.listFiles()
            ?.filter { it.name.startsWith(name) }
            ?.forEach { it.delete() }

        backupBeforeMigration(context, name)

        val leftovers = file.parentFile?.listFiles()?.filter { it.name.startsWith(name) } ?: emptyList()
        assertTrue("库不存在时不得留下任何产物，实际=${leftovers.map { it.name }}", leftovers.isEmpty())
    }

    // ————————————————————————————————————————————————————————

    private fun idsOf(db: androidx.sqlite.db.SupportSQLiteDatabase): List<String> =
        db.query("SELECT id FROM match_events ORDER BY id").use { cursor ->
            val out = mutableListOf<String>()
            while (cursor.moveToNext()) out += cursor.getString(0)
            out
        }
}
