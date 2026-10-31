package com.roundtrace.app.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.core.database.RoundTraceDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2：回填值的**不依赖 instrumentation** 验证（MigrationTestHelper 的冗余保障）。
 *
 * 与 T1 的分工：
 * - T1 用 `MigrationTestHelper` 验证「Room 能跑完迁移且 schema 校验通过」；
 * - T2 用「手工 v3 库 + Room 打开触发迁移」验证「回填值正确」，
 *   整条路径不碰 `InstrumentationRegistry`，因此 T1 万一在 JVM 上不可用，回填仍有守。
 *
 * v3 库由 [LegacyV3Database] 依据 `schemas/3.json` 的 DDL 反向生成，不手抄建表语句。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationBackfillManualTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var opened: RoundTraceDatabase? = null

    @After
    fun tearDown() {
        opened?.close()
        opened = null
    }

    @Test
    fun migratingLegacyFileBackfillsPlainEvents() {
        val name = "t2-plain.db"
        LegacyV3Database.create(context, name) { db ->
            LegacyV3Database.insertV3Match(db, "m1")
            LegacyV3Database.insertV3Event(db, "e1", "m1", 1)
            LegacyV3Database.insertV3Event(db, "e2", "m1", 2)
            LegacyV3Database.insertV3Event(db, "e3", "m1", 3)
        }

        val room = LegacyV3Database.openWithRoom(context, name)
        opened = room
        val db = room.openHelper.writableDatabase

        assertEquals("迁移后版本必须为 4", 4, LegacyV3Database.userVersion(db))
        assertEquals("行数不得变化", 3, LegacyV3Database.rowsOf(db, "match_events"))
        assertEquals("e1", 1 to 0, LegacyV3Database.slotOf(db, "e1"))
        assertEquals("e2", 2 to 0, LegacyV3Database.slotOf(db, "e2"))
        assertEquals("e3", 3 to 0, LegacyV3Database.slotOf(db, "e3"))
        assertEquals(
            "不得出现 logicalPosition = 0 的脏行（S1）",
            0,
            LegacyV3Database.count(db, "SELECT COUNT(*) FROM match_events WHERE logicalPosition = 0")
        )
    }

    @Test
    fun migratingLegacyFileBackfillsRevisionChain() {
        val name = "t2-chain.db"
        LegacyV3Database.create(context, name) { db ->
            LegacyV3Database.insertV3Match(db, "m1")
            LegacyV3Database.insertV3Event(db, "E1", "m1", 1, supersedesId = null, retracted = true)
            LegacyV3Database.insertV3Event(db, "E2", "m1", 6, supersedesId = "E1", retracted = true)
            LegacyV3Database.insertV3Event(db, "E3", "m1", 7, supersedesId = "E2", retracted = false)
        }

        val room = LegacyV3Database.openWithRoom(context, name)
        opened = room
        val db = room.openHelper.writableDatabase

        assertEquals("E1 位置/版本", 1 to 0, LegacyV3Database.slotOf(db, "E1"))
        assertEquals("E2 位置/版本", 1 to 1, LegacyV3Database.slotOf(db, "E2"))
        assertEquals("E3 位置/版本", 1 to 2, LegacyV3Database.slotOf(db, "E3"))
        assertEquals(
            "同一位置的 active 行必须只有 1 条（INV-1）",
            1,
            LegacyV3Database.count(
                db,
                "SELECT COUNT(*) FROM match_events WHERE retracted = 0 AND logicalPosition = 1"
            )
        )
    }

    @Test
    fun migratingOrphanChainDoesNotThrow() {
        val name = "t2-orphan.db"
        LegacyV3Database.create(context, name) { db ->
            LegacyV3Database.insertV3Match(db, "m1")
            LegacyV3Database.insertV3Event(db, "orphan", "m1", 4, supersedesId = "ghost")
        }

        val room = LegacyV3Database.openWithRoom(context, name)
        opened = room
        val db = room.openHelper.writableDatabase

        assertEquals("孤儿链回落到 occurredOrder / 0", 4 to 0, LegacyV3Database.slotOf(db, "orphan"))
    }

    @Test
    fun migratingEmptyLegacyFileWorks() {
        val name = "t2-empty.db"
        LegacyV3Database.create(context, name) { db ->
            LegacyV3Database.insertV3Match(db, "m1")
        }

        val room = LegacyV3Database.openWithRoom(context, name)
        opened = room
        val db = room.openHelper.writableDatabase

        assertEquals(4, LegacyV3Database.userVersion(db))
        assertEquals(0, LegacyV3Database.rowsOf(db, "match_events"))
    }
}
