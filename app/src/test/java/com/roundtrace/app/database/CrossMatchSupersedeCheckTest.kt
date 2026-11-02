package com.roundtrace.app.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.core.database.CrossMatchSupersede
import com.roundtrace.app.core.database.RoundTraceDatabase
import com.roundtrace.app.core.database.findCrossMatchSupersedes
import com.roundtrace.app.core.database.warnOnCrossMatchSupersedes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * F2 追加：跨局 `supersedesId` 链的启动自检。
 *
 * ## 这条自检要回答的问题
 *
 * F2 的 CTE 修复只保证「升级时不再算错位置」，**不修数据**：脏链还在库里。
 * 而 RT-002 修复前 `reviseEvent` 只按 `eventId` 查、`sameMatch = true` 是硬编码断言，
 * 所以真实旧库里**可能**已经有跨局链。这条自检把存量暴露出来供人工判断。
 *
 * ## 为什么查询与日志要拆开
 *
 * 只靠「读日志断言」会让用例依赖日志格式；拆成 `findCrossMatchSupersedes`（返回数据）
 * + `warnOnCrossMatchSupersedes`（只打日志）后，数据面可以直接断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrossMatchSupersedeCheckTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var opened: RoundTraceDatabase? = null

    @After
    fun tearDown() {
        opened?.close()
        opened = null
    }

    @Test
    fun findsCrossMatchLinkageInMigratedLegacyDatabase() {
        val name = "diag-cross.db"
        LegacyV3Database.create(context, name) { db ->
            LegacyV3Database.insertV3Match(db, "mA", title = "A 局")
            LegacyV3Database.insertV3Match(db, "mB", title = "B 局")
            LegacyV3Database.insertV3Event(db, "e1", "mA", 1)
            LegacyV3Database.insertV3Event(db, "f1", "mB", 1)
            // B 局的事件声称自己在修订 A 局的事件 —— 这就是要抓的脏链
            LegacyV3Database.insertV3Event(db, "f2", "mB", 2, supersedesId = "e1")
        }

        val room = LegacyV3Database.openWithRoom(context, name)
        opened = room
        val db = room.openHelper.writableDatabase

        val hits = findCrossMatchSupersedes(db)
        assertEquals("应恰好命中 1 条跨局链，实际=$hits", 1, hits.size)
        assertEquals(
            CrossMatchSupersede(matchId = "mB", eventId = "f2", supersedesId = "e1"),
            hits.single()
        )
    }

    @Test
    fun cleanDatabaseReturnsEmpty() {
        val name = "diag-clean.db"
        LegacyV3Database.create(context, name) { db ->
            LegacyV3Database.insertV3Match(db, "m1")
            // 同局内的正常修订链：E1 是根，E2 修订 E1
            LegacyV3Database.insertV3Event(db, "E1", "m1", 1)
            LegacyV3Database.insertV3Event(db, "E2", "m1", 2, supersedesId = "E1")
        }

        val room = LegacyV3Database.openWithRoom(context, name)
        opened = room
        val db = room.openHelper.writableDatabase

        assertTrue(
            "同局链不得被误报为跨局链，实际=${findCrossMatchSupersedes(db)}",
            findCrossMatchSupersedes(db).isEmpty()
        )
    }

    /**
     * 自检**只告警、绝不改数据**：这是主理人明确要求的边界
     * （自动改历史数据比不修更危险）。
     */
    @Test
    fun warningDoesNotModifyAnyData() {
        val name = "diag-readonly.db"
        LegacyV3Database.create(context, name) { db ->
            LegacyV3Database.insertV3Match(db, "mA")
            LegacyV3Database.insertV3Match(db, "mB")
            LegacyV3Database.insertV3Event(db, "e1", "mA", 1)
            LegacyV3Database.insertV3Event(db, "f2", "mB", 2, supersedesId = "e1")
        }

        val room = LegacyV3Database.openWithRoom(context, name)
        opened = room
        val db = room.openHelper.writableDatabase

        val rowsBefore = LegacyV3Database.rowsOf(db, "match_events")
        val linkageBefore = linkageOf(db)
        ShadowLog.clear()

        warnOnCrossMatchSupersedes(db)

        assertEquals("不得增删任何事件行", rowsBefore, LegacyV3Database.rowsOf(db, "match_events"))
        assertEquals("不得改写 supersedesId", linkageBefore, linkageOf(db))
        assertTrue(
            "必须留下告警日志，实际=${ShadowLog.getLogsForTag("RoundTraceDb").map { it.msg }}",
            ShadowLog.getLogsForTag("RoundTraceDb").any { it.msg.contains("跨局修订链") }
        )
    }

    /** 便于断言：`id -> supersedesId` 快照。 */
    private fun linkageOf(db: androidx.sqlite.db.SupportSQLiteDatabase): Map<String, String?> {
        val result = mutableMapOf<String, String?>()
        db.query("SELECT id, supersedesId FROM match_events ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.getString(0)] = cursor.getString(1)
            }
        }
        return result
    }
}
