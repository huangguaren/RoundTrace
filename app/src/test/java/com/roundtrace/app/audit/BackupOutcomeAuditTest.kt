package com.roundtrace.app.audit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.data.ProjectionRefreshCoordinator
import com.roundtrace.app.core.data.ProjectionRefreshResult
import com.roundtrace.app.core.database.BackupOutcome
import com.roundtrace.app.core.database.WalCheckpointResult
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
 * QA 阶段 D · §7 复验：**F3（备份 busy 判定）**。
 *
 * 本机 Robolectric 的 SQLite 是多连接共享句柄，`PRAGMA wal_checkpoint(TRUNCATE)` 永远返回
 * `busy=0`，**造不出真实的 busy**。F3 把 checkpoint 抽成了可注入参数，所以这里改成
 * 「钉分支」：直接注入各种 `WalCheckpointResult`，断言 `BackupOutcome` 的判定。
 *
 * 重点验主理人点名的那条边界：**拿不到 PRAGMA 结果时必须走保守分支（`Incomplete`），
 * 不能算 `Complete`**——否则等于又回到「误报成功」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupOutcomeAuditTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun seedV3(name: String): File = LegacyV3Database.create(context, name) { db ->
        LegacyV3Database.insertV3Match(db, "m1")
        LegacyV3Database.insertV3Event(db, "e1", "m1", 1)
    }

    // ————————————————————————————————————————————————————————
    // busy 分支
    // ————————————————————————————————————————————————————————

    @Test
    fun f3_busy_checkpoint_is_incomplete_not_complete() {
        val name = "audit-outcome-busy.db"
        seedV3(name)

        val outcome = backupBeforeMigration(context, name) {
            WalCheckpointResult(busy = 1, log = 7, checkpointed = 3)
        }

        assertTrue("busy=1 必须返回 Incomplete，实际=$outcome", outcome is BackupOutcome.Incomplete)
        assertTrue(
            "reason 必须带上 busy 值，便于定位：${(outcome as BackupOutcome.Incomplete).reason}",
            outcome.reason.contains("busy=1")
        )
        assertFalse("绝不能是 Complete", outcome is BackupOutcome.Complete)

        // 设计取舍：busy 时**照常复制**（有总比没有好），只是不得报「完成」
        val bak = File(context.getDatabasePath(name).absolutePath + ".bak.v3")
        assertTrue("busy 时备份文件仍应生成（降级但可用）", bak.exists())
        assertTrue("备份不得为空", bak.length() > 0)
    }

    /**
     * **主理人点名的边界**：`PRAGMA` 拿不到结果（无返回行 / 调用抛异常）时，
     * `WalCheckpointResult.UNKNOWN` 的 `busy = -1`，`isComplete` 为 false → 必须 `Incomplete`。
     */
    @Test
    fun f3_unknown_checkpoint_result_is_conservative() {
        val name = "audit-outcome-unknown.db"
        seedV3(name)

        val outcome = backupBeforeMigration(context, name) { WalCheckpointResult.UNKNOWN }

        assertTrue(
            "UNKNOWN(busy=-1) 必须走保守分支 Incomplete，实际=$outcome",
            outcome is BackupOutcome.Incomplete
        )
        assertEquals(
            "UNKNOWN 的 busy=-1",
            -1,
            (outcome as BackupOutcome.Incomplete).reason.substringAfter("busy=").substringBefore(")").toInt()
        )
    }

    /** checkpoint 直接抛异常（例如库被锁）→ 退化为 UNKNOWN → 仍是 Incomplete。 */
    @Test
    fun f3_checkpoint_throwing_degrades_to_incomplete() {
        val name = "audit-outcome-throw.db"
        seedV3(name)

        val outcome = backupBeforeMigration(context, name) {
            throw IllegalStateException("database is locked")
        }

        assertTrue(
            "checkpoint 抛异常时必须退化为 Incomplete（绝不 Complete），实际=$outcome",
            outcome is BackupOutcome.Incomplete
        )
    }

    // ————————————————————————————————————————————————————————
    // 正常分支（防止「一律报 Incomplete」这种反向假绿）
    // ————————————————————————————————————————————————————————

    @Test
    fun f3_successful_checkpoint_is_complete() {
        val name = "audit-outcome-ok.db"
        seedV3(name)

        val outcome = backupBeforeMigration(context, name) {
            WalCheckpointResult(busy = 0, log = 7, checkpointed = 7)
        }

        assertEquals("busy=0 必须是 Complete", BackupOutcome.Complete, outcome)
        val bak = File(context.getDatabasePath(name).absolutePath + ".bak.v3")
        assertTrue("Complete 时必须有备份文件", bak.exists())
    }

    @Test
    fun f3_missing_database_is_not_needed() {
        val name = "audit-outcome-missing.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.listFiles()?.filter { it.name.startsWith(name) }?.forEach { it.delete() }

        val outcome = backupBeforeMigration(context, name) {
            WalCheckpointResult(busy = 0, log = 0, checkpointed = 0)
        }

        assertEquals("库不存在时必须是 NotNeeded", BackupOutcome.NotNeeded, outcome)
        assertFalse("也不得凭空造库", file.exists())
    }

    // ————————————————————————————————————————————————————————
    // V12：投影标记纯函数（F5）
    // ————————————————————————————————————————————————————————

    @Test
    fun v12_projection_marker_truth_table() {
        // ① 是否需要刷新
        assertTrue("从未标记(0) < 要求(1) → 要刷新", ProjectionRefreshCoordinator.shouldRefresh(0, 1))
        assertFalse("已标记(1) 不再刷新", ProjectionRefreshCoordinator.shouldRefresh(1, 1))
        assertFalse("版本更高也不刷新", ProjectionRefreshCoordinator.shouldRefresh(2, 1))

        // ② 是否允许写标记
        val allOk = ProjectionRefreshResult(processed = 3, failed = emptyList())
        assertTrue(
            "全部成功且未标记 → 写",
            ProjectionRefreshCoordinator.shouldWriteProjectionMarker(allOk, alreadyMarked = false)
        )
        assertFalse(
            "已标记过 → 不重复写",
            ProjectionRefreshCoordinator.shouldWriteProjectionMarker(allOk, alreadyMarked = true)
        )

        val oneFailed = ProjectionRefreshResult(
            processed = 2,
            failed = listOf(MatchId("broken-match"))
        )
        assertFalse(
            "**任一局失败 → 绝不写标记**（写早了等于永久不再重试）",
            ProjectionRefreshCoordinator.shouldWriteProjectionMarker(oneFailed, alreadyMarked = false)
        )

        val emptyDb = ProjectionRefreshResult(processed = 0, failed = emptyList())
        assertTrue(
            "空库也算全部成功 → 写标记（否则每次启动都白扫）",
            ProjectionRefreshCoordinator.shouldWriteProjectionMarker(emptyDb, alreadyMarked = false)
        )
        assertFalse(
            "空库但已标记过 → 不重复写",
            ProjectionRefreshCoordinator.shouldWriteProjectionMarker(emptyDb, alreadyMarked = true)
        )
    }
}
