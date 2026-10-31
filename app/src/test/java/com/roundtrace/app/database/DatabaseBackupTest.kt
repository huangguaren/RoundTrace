package com.roundtrace.app.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.core.database.RoundTraceDatabase
import com.roundtrace.app.core.database.backupBeforeMigration
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
 * 覆盖两件事：
 * 1. **备份产物本身可恢复**（能用只读方式打开、版本正确、行数一致）；
 * 2. **`build()` 真的会在迁移前自动备份**（B2 钩子）——这是关键，
 *    只验证「自己手动复制一份能不能读」等于没测到钩子。
 *
 * 钩子的位置约束：必须在 `Room.databaseBuilder(...)` 之前调用。
 * `RoomDatabase.Callback.onOpen` 在迁移**之后**才触发，那时备份已经晚了。
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

    // ————————————————————————————————————————————————————————
    // B2：迁移前自动备份钩子
    // ————————————————————————————————————————————————————————

    /**
     * 核心用例：走真实 `RoundTraceDatabase.build()`，断言它在**迁移之前**就把 v3 库备份了。
     *
     * 注意断言顺序：备份必须在 `build()` 返回时就存在（不依赖后续 `.openHelper` 触发迁移），
     * 这样才能证明它没有挂在 `onOpen` 上。
     */
    @Test
    fun buildBacksUpLegacyDatabaseBeforeMigration() {
        val name = "b2-auto.db"
        val sourceFile = LegacyV3Database.create(context, name) { db ->
            LegacyV3Database.insertV3Match(db, "m1")
            (1..4).forEach { index ->
                LegacyV3Database.insertV3Event(db, "e$index", "m1", index)
            }
        }

        // 迁移前的行数（用于与备份比对）
        val sourceHelper = LegacyV3Database.openRaw(context, sourceFile, version = 3)
        val eventsBefore = try {
            LegacyV3Database.rowsOf(sourceHelper.readableDatabase, "match_events")
        } finally {
            sourceHelper.close()
        }
        assertEquals("夹具应有 4 条事件", 4, eventsBefore)

        // ★ 只调用 build()，不 open：备份必须在此时已产生
        val room = RoundTraceDatabase.build(context, name)
        val backupFile = File(sourceFile.absolutePath + ".bak.v3")
        assertTrue(
            "build() 必须在 databaseBuilder 之前完成备份（文件应已存在：${backupFile.name}）",
            backupFile.exists()
        )
        assertTrue("备份不得为空", backupFile.length() > 0)

        // 备份仍是 v3、行数与源库一致
        val backupHelper = LegacyV3Database.openRaw(context, backupFile, version = 3)
        try {
            val db = backupHelper.readableDatabase
            assertEquals(
                "备份必须停在 v3（否则无法作为回退点）",
                3,
                LegacyV3Database.userVersion(db)
            )
            assertEquals(
                "备份的事件行数必须与迁移前一致",
                eventsBefore,
                LegacyV3Database.rowsOf(db, "match_events")
            )
            assertEquals(
                "备份的 matches 行数必须与迁移前一致",
                1,
                LegacyV3Database.rowsOf(db, "matches")
            )
        } finally {
            backupHelper.close()
        }

        // 打开 Room 触发迁移，确认迁移本身没被备份钩子破坏
        assertEquals(
            "主库应被迁移到 v4",
            4,
            LegacyV3Database.userVersion(room.openHelper.writableDatabase)
        )
        room.close()

        // 迁移后备份仍在、仍是 v3（迁移不能回头改备份）
        val verify = LegacyV3Database.openRaw(context, backupFile, version = 3)
        try {
            assertEquals(3, LegacyV3Database.userVersion(verify.readableDatabase))
        } finally {
            verify.close()
        }
    }

    /**
     * 备份只保留**最新一份**，更早版本的 `.bak` 会被清掉。
     *
     * 为什么值得测：`deleteOlderBackups` 是唯一「会删用户目录文件」的逻辑，
     * 写错就是把用户唯一的回退点删了。而且它的触发路径（每次升级只保留一份）
     * 与「攒了多份备份撑爆存储」只差一个 filter 条件。
     */
    @Test
    fun backupPrunesOlderBackupVersions() {
        val name = "b2-prune.db"
        val sourceFile = LegacyV3Database.create(context, name) { db ->
            LegacyV3Database.insertV3Match(db, "m1")
            LegacyV3Database.insertV3Event(db, "e1", "m1", 1)
        }

        // 造一份「更早版本」的陈旧备份
        val stale = File(sourceFile.absolutePath + ".bak.v2")
        stale.writeText("stale backup", Charsets.UTF_8)
        assertTrue("前置条件：陈旧备份应存在", stale.exists())

        backupBeforeMigration(context, name)

        val fresh = File(sourceFile.absolutePath + ".bak.v3")
        assertTrue("必须生成当前版本的备份（v3）", fresh.exists())
        assertTrue("更早版本的备份必须被清理，实际仍存在：${stale.name}", !stale.exists())
    }

    @Test
    fun backupSkipsMissingDatabaseWithoutCrashing() {
        val missing = "b2-never-created.db"
        val file = context.getDatabasePath(missing)
        assertTrue("前置条件：库文件不应存在", !file.exists())

        // 不崩、不产生备份
        backupBeforeMigration(context, missing)
        assertTrue("库不存在时不得创建备份", !File(file.absolutePath + ".bak.v1").exists())
        assertTrue("也不得凭空创建库文件", !file.exists())
    }

    @Test
    fun inMemoryDatabaseNeverProducesBackup() {
        RoundTraceDatabase.buildInMemory(context).close()

        val stale = context.getDatabasePath("roundtrace.db").parentFile
            ?.listFiles()
            ?.filter { it.name.contains(".bak.v") }
            ?: emptyList()
        assertTrue("内存库不得产生备份，实际=${stale.map { it.name }}", stale.isEmpty())
    }
}
