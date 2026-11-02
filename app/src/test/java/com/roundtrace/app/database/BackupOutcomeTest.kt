package com.roundtrace.app.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.core.database.BackupOutcome
import com.roundtrace.app.core.database.WalCheckpointResult
import com.roundtrace.app.core.database.backupBeforeMigration
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * F3：`wal_checkpoint` 未完成（busy）时**不得**报「备份完成」。
 *
 * ## 缺陷是什么
 *
 * 原来的实现把 `PRAGMA wal_checkpoint(TRUNCATE)` 的三元组整个丢掉，随后只复制主库文件并
 * `Log.i("迁移前备份完成")`。别的连接还持着读锁时 checkpoint 会返回 `busy=1` 且**不合并**，
 * 于是复制到的是「缺最近事务」的备份，却对外宣称成功 —— 这种备份比没有备份更危险。
 *
 * ## 如何制造 busy
 *
 * Robolectric 的 SQLite 共享同一底层句柄，探针实测无法真正触发 `busy=1`（见
 * `BackupWalAuditTest` 的说明）。所以这里把 checkpoint 动作做成**可注入参数**，
 * 用测试替身直接返回 `busy=1`，把「判断分支」而不是「SQLite 并发」钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupOutcomeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 库文件名 → 备份路径（`.bak.v3`） */
    private fun backupFileOf(name: String): File =
        File(context.getDatabasePath(name).absolutePath + ".bak.v3")

    private fun createV3Database(name: String) = LegacyV3Database.create(context, name) { db ->
        LegacyV3Database.insertV3Match(db, "m1")
        LegacyV3Database.insertV3Event(db, "e1", "m1", 1)
    }

    private fun backupLogMessages(): List<String> =
        ShadowLog.getLogsForTag("RoundTraceDb").map { it.msg }

    @Test
    fun completedCheckpointYieldsCompleteAndLogsSuccess() {
        val name = "f3-complete.db"
        createV3Database(name)
        ShadowLog.clear()

        val outcome = backupBeforeMigration(context, name)

        assertEquals("checkpoint 干净完成时必须是 Complete", BackupOutcome.Complete, outcome)
        assertTrue("必须真的产出备份文件", backupFileOf(name).exists())
        val messages = backupLogMessages()
        assertTrue(
            "成功时必须说明完成，实际日志=$messages",
            messages.any { it.contains("迁移前备份完成") }
        )
    }

    @Test
    fun busyCheckpointYieldsIncompleteAndNeverClaimsCompletion() {
        val name = "f3-busy.db"
        createV3Database(name)
        ShadowLog.clear()

        var invocations = 0
        val outcome = backupBeforeMigration(context, name) {
            invocations++
            WalCheckpointResult(busy = 1, log = 3, checkpointed = 0)
        }

        assertEquals("busy 时应重试一次（共调用两次）", 2, invocations)
        assertTrue("busy 必须降级为 Incomplete，实际=$outcome", outcome is BackupOutcome.Incomplete)
        assertTrue(
            "busy 时仍要留下备份文件（有总比没有好）",
            backupFileOf(name).exists()
        )
        val messages = backupLogMessages()
        assertTrue(
            "**不得**再打印「迁移前备份完成」，实际日志=$messages",
            messages.none { it.contains("迁移前备份完成") }
        )
        assertTrue(
            "必须明确警告可能缺最近事务，实际日志=$messages",
            messages.any { it.contains("busy") && it.contains("可能缺最近事务") }
        )
    }

    @Test
    fun laterAttemptSucceedsAfterBusyIsCleared() {
        val name = "f3-busy-then-ok.db"
        createV3Database(name)
        ShadowLog.clear()

        // 第一次 busy、重试时已恢复 → 仍是 Complete，且只调用两次
        var invocations = 0
        val outcome = backupBeforeMigration(context, name) {
            invocations++
            if (invocations == 1) {
                WalCheckpointResult(busy = 1, log = 2, checkpointed = 0)
            } else {
                WalCheckpointResult(busy = 0, log = 2, checkpointed = 2)
            }
        }

        assertEquals("重试成功时结果应为 Complete", BackupOutcome.Complete, outcome)
        assertEquals("首次 busy + 重试成功 = 两次调用", 2, invocations)
    }

    @Test
    fun missingDatabaseNeedsNoBackup() {
        val name = "f3-missing.db"
        assertTrue("前置条件：库文件不应存在", !context.getDatabasePath(name).exists())

        assertEquals(BackupOutcome.NotNeeded, backupBeforeMigration(context, name))
        assertTrue("库不存在时不得产生备份", !backupFileOf(name).exists())
    }
}
