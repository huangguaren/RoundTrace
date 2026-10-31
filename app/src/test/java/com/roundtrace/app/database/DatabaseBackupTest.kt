package com.roundtrace.app.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T3：迁移前备份文件的**可用性**（migration-plan §14-① 第 5 条）。
 *
 * ⚠️ 范围说明（必须如实记录）：
 * 本用例验证的是「把 v3 库文件另存为 `roundtrace.db.bak.v3` 之后，
 * 这个备份**能只读打开、版本号正确、行数与主库一致**」——
 * 也就是「真的出事时能不能靠它恢复」。
 *
 * **产品侧目前没有「迁移前自动备份」的钩子**（那需要改 Application 启动路径，
 * 超出 RT-003 Step 1 的 6 文件清单）。因此本用例证明的是备份产物的可恢复性，
 * 不是「App 会自动备份」。若要自动备份，需要单列任务。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseBackupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun v3BackupFileIsReadableAndMatchesSource() {
        val sourceName = "t3-source.db"
        val backupSuffix = ".bak.v3"

        // ① 造一个真实的 v3 库（含数据）
        val sourceFile = LegacyV3Database.create(context, sourceName) { db ->
            LegacyV3Database.insertV3Match(db, "m1")
            (1..5).forEach { index ->
                LegacyV3Database.insertV3Event(db, "e$index", "m1", index)
            }
        }

        // ② 迁移前复制成备份（App 侧的备份动作等价于这一行）
        val backupFile = File(sourceFile.absolutePath + backupSuffix)
        sourceFile.copyTo(backupFile, overwrite = true)

        assertTrue("备份文件必须存在", backupFile.exists())
        assertTrue("备份文件不得为空", backupFile.length() > 0)

        // ③ 备份仍停在 v3，且数据完整
        val helper = LegacyV3Database.openRaw(context, backupFile, version = 3)
        try {
            val db = helper.readableDatabase
            assertEquals(
                "备份必须仍是 v3（否则它就没法作为回退点）",
                3,
                LegacyV3Database.userVersion(db)
            )
            assertEquals(
                "备份的事件行数必须与主库一致",
                LegacyV3Database.rowsOf(db, "match_events"),
                5
            )
            assertEquals(
                "备份的 matches 行数必须与主库一致",
                LegacyV3Database.rowsOf(db, "matches"),
                1
            )
            // v3 结构里没有新列——这正是备份可用于回退的前提
            db.query("PRAGMA table_info(match_events)").use { cursor ->
                val columns = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                }
                assertTrue("v3 备份不得包含 logicalPosition", !columns.contains("logicalPosition"))
                assertTrue("v3 备份不得包含 revisionSeq", !columns.contains("revisionSeq"))
            }
        } finally {
            helper.close()
        }

        // ④ 主库迁移后，备份仍保持 v3（迁移不会回头改备份）
        val room = LegacyV3Database.openWithRoom(context, sourceName)
        try {
            assertEquals(
                "主库应已迁移到 v4",
                4,
                LegacyV3Database.userVersion(room.openHelper.writableDatabase)
            )
        } finally {
            room.close()
        }

        val verify = LegacyV3Database.openRaw(context, backupFile, version = 3)
        try {
            assertEquals(
                "主库迁移后备份仍必须是 v3（可回退）",
                3,
                LegacyV3Database.userVersion(verify.readableDatabase)
            )
        } finally {
            verify.close()
        }
    }
}
