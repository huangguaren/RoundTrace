package com.roundtrace.app.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.roundtrace.app.core.database.RoundTraceDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/**
 * T1：v3 → v4 迁移（RT-003 Step 1 的主用例）。
 *
 * ## 为什么在 `androidTest` 而不是 `src/test`
 *
 * `MigrationTestHelper` 从 **instrumentation 的 assets** 读取 schema json。
 * 本机实测：Robolectric（纯 JVM）下 `isIncludeAndroidResources = true` 只会合并
 * **main** 的 assets，`sourceSets["test"].assets.srcDir(...)` 里的 schema 读不到，
 * 7 个用例全部抛 `FileNotFoundException: Cannot find the schema file in the assets folder`。
 *
 * 按 migration-plan §15.5 的应急方案：T1 下沉到仪器测试（真机/模拟器可跑），
 * JVM 侧的回填正确性由 [MigrationBackfillManualTest]（T2）承担，
 * 后者不碰 `InstrumentationRegistry`，走「手工 v3 夹具 + Room 打开触发迁移」。
 * 2026-11-02 已在 API 34 Android 模拟器运行 7 个用例并通过；报告见 `docs/TEST_REPORT.md` §13。
 * 这不替代 API 26 等实体设备上的迁移验证。
 *
 * 它验证的是 **Room 能跑完迁移且 schema 校验通过**——
 * 这是 `@ColumnInfo(defaultValue)` 写错（例如带引号、带空格、
 * 与 `ALTER TABLE` 的 DEFAULT 不一致）的唯一把关点：
 * Room 迁移后会比对 `TableInfo`，不一致直接抛
 * `IllegalStateException: Migration didn't properly handle match_events`。
 *
 * 每条用例用**独立库名**，避免互相污染。
 */
@RunWith(AndroidJUnit4::class)
class MigrationV3ToV4Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RoundTraceDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun v3(name: String, seed: (SupportSQLiteDatabase) -> Unit): SupportSQLiteDatabase =
        helper.createDatabase(name, 3).also { db ->
            try {
                insertMatch(db)
                seed(db)
            } finally {
                db.close()
            }
        }

    /** v3 结构的父表行（迁移校验要求 `matches` 存在）。 */
    private fun insertMatch(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO matches (id, gameType, rulesetId, rulesetVersion, presetId, playerCount, " +
                "configJson, status, title, mySeat, initialLeaderSeat, createdAt, updatedAt, " +
                "revealedAt, schemaVersion) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                "m1", "AVALON", "avalon.standard", 1, "avalon.standard.8p", 8, "{}",
                "DRAFT", "迁移测试对局", 1, 1, 0L, 0L, null, 1
            )
        )
    }

    /** v3 结构的 `match_events` 行（无 logicalPosition / revisionSeq 列）。 */
    private fun insertEvent(
        db: SupportSQLiteDatabase,
        id: String,
        occurredOrder: Int,
        supersedesId: String? = null,
        retracted: Boolean = false
    ) {
        db.execSQL(
            "INSERT INTO match_events " +
                "(id, matchId, occurredOrder, phaseKey, eventType, payloadVersion, payloadJson, " +
                "visibility, recordedAt, supersedesId, retracted) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                id, "m1", occurredOrder, "QUEST_1", "QUEST_STARTED", 1,
                "{\"questNo\":1,\"leaderSeat\":1}", "PUBLIC", 1_700_000_000_000L,
                supersedesId, if (retracted) 1 else 0
            )
        )
    }

    private fun rowsOf(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun hasIndex(db: SupportSQLiteDatabase, name: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type=\'index\' AND name=?", arrayOf(name)).use {
            it.moveToFirst()
        }

    private fun slotOf(db: SupportSQLiteDatabase, id: String): Pair<Int, Int> =
        db.query(
            "SELECT logicalPosition, revisionSeq FROM match_events WHERE id = ?",
            arrayOf(id)
        ).use { cursor ->
            check(cursor.moveToFirst()) { "找不到事件：$id" }
            cursor.getInt(0) to cursor.getInt(1)
        }

    private fun migrate(name: String): SupportSQLiteDatabase =
        helper.runMigrationsAndValidate(name, 4, true, RoundTraceDatabase.MIGRATION_3_4)

    // ————————————————————————————————————————————————————————
    // T1-a：无修订的普通事件
    // ————————————————————————————————————————————————————————

    @Test
    fun t1a_plainEventsBackfillToTheirOccurredOrder() {
        v3("t1a.db") { db ->
            insertEvent(db, "e1", 1)
            insertEvent(db, "e2", 2)
            insertEvent(db, "e3", 3)
        }

        val db = migrate("t1a.db")
        try {
            assertEquals("迁移前后行数必须相等", 3, rowsOf(db, "match_events"))
            assertEquals("e1 位置 = 1", 1 to 0, slotOf(db, "e1"))
            assertEquals("e2 位置 = 2", 2 to 0, slotOf(db, "e2"))
            assertEquals("e3 位置 = 3", 3 to 0, slotOf(db, "e3"))

            // 老事件仍可读：payload / type / recordedAt / 可见性 / retracted 逐行与迁移前一致
            db.query(
                "SELECT eventType, payloadJson, visibility, recordedAt, retracted " +
                    "FROM match_events WHERE id = 'e2'"
            ).use { cursor ->
                assertTrue("e2 必须还在", cursor.moveToFirst())
                assertEquals("QUEST_STARTED", cursor.getString(0))
                assertEquals("{\"questNo\":1,\"leaderSeat\":1}", cursor.getString(1))
                assertEquals("PUBLIC", cursor.getString(2))
                assertEquals(1_700_000_000_000L, cursor.getLong(3))
                assertEquals(0, cursor.getInt(4))
            }
        } finally {
            db.close()
        }
    }

    // ————————————————————————————————————————————————————————
    // T1-b：两层修订链
    // ————————————————————————————————————————————————————————

    @Test
    fun t1b_revisionChainSharesLogicalPositionAndCountsRevisions() {
        v3("t1b.db") { db ->
            insertEvent(db, "E1", 1, supersedesId = null, retracted = true)
            insertEvent(db, "E2", 6, supersedesId = "E1", retracted = true)
            insertEvent(db, "E3", 7, supersedesId = "E2", retracted = false)
        }

        val db = migrate("t1b.db")
        try {
            assertEquals("E1：链根，位置=1、版本=0", 1 to 0, slotOf(db, "E1"))
            assertEquals("E2：第 1 次修订，位置仍 1、版本=1", 1 to 1, slotOf(db, "E2"))
            assertEquals("E3：第 2 次修订，位置仍 1、版本=2", 1 to 2, slotOf(db, "E3"))

            // retracted 值不得被迁移改写
            db.query("SELECT id, retracted FROM match_events ORDER BY occurredOrder").use { cursor ->
                val actual = mutableListOf<Pair<String, Int>>()
                while (cursor.moveToNext()) {
                    actual += cursor.getString(0) to cursor.getInt(1)
                }
                assertEquals(listOf("E1" to 1, "E2" to 1, "E3" to 0), actual)
            }
        } finally {
            db.close()
        }
    }

    // ————————————————————————————————————————————————————————
    // T1-c：孤儿链
    // ————————————————————————————————————————————————————————

    @Test
    fun t1c_orphanChainFallsBackWithoutThrowing() {
        v3("t1c.db") { db ->
            insertEvent(db, "orphan", 4, supersedesId = "ghost", retracted = false)
        }

        val db = migrate("t1c.db")
        try {
            assertEquals(
                "孤儿链必须回落到 occurredOrder / 0，且迁移不抛异常",
                4 to 0,
                slotOf(db, "orphan")
            )
        } finally {
            db.close()
        }
    }

    // ————————————————————————————————————————————————————————
    // T1-d：空库
    // ————————————————————————————————————————————————————————

    @Test
    fun t1d_emptyEventsTableMigratesCleanly() {
        helper.createDatabase("t1d.db", 3).close()

        val db = migrate("t1d.db")
        try {
            assertEquals(0, rowsOf(db, "match_events"))
        } finally {
            db.close()
        }
    }

    // ————————————————————————————————————————————————————————
    // T1-e / T1-f：索引
    // ————————————————————————————————————————————————————————

    @Test
    fun t1e_newIndexExists() {
        v3("t1e.db") { db -> insertEvent(db, "e1", 1) }
        val db = migrate("t1e.db")
        try {
            assertTrue(
                "新读路径索引必须存在",
                hasIndex(
                    db,
                    "index_match_events_matchId_logicalPosition_revisionSeq"
                )
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun t1f_oldIndicesSurvive() {
        v3("t1f.db") { db -> insertEvent(db, "e1", 1) }
        val db = migrate("t1f.db")
        try {
            listOf(
                "index_match_events_matchId_occurredOrder",
                "index_match_events_matchId_eventType",
                "index_match_events_supersedesId"
            ).forEach { index ->
                assertTrue("旧索引 $index 不得被误删", hasIndex(db, index))
            }
        } finally {
            db.close()
        }
    }

    // ————————————————————————————————————————————————————————
    // T1-g：回填幂等
    // ————————————————————————————————————————————————————————

    @Test
    fun t1g_backfillUpdatesAreIdempotent() {
        v3("t1g.db") { db ->
            insertEvent(db, "E1", 1, supersedesId = null, retracted = true)
            insertEvent(db, "E2", 6, supersedesId = "E1", retracted = false)
            insertEvent(db, "plain", 9)
        }

        val db = migrate("t1g.db")
        try {
            val before = listOf("E1", "E2", "plain").associateWith { slotOf(db, it) }

            // 再执行一次 ②③ 两条 UPDATE（与 MIGRATION_3_4 内的 SQL 逐字相同；
            // 改迁移 SQL 时必须同步改这里，否则幂等性就失去意义）
            db.execSQL(BACKFILL_LOGICAL_POSITION_SQL)
            db.execSQL(BACKFILL_REVISION_SEQ_SQL)

            val after = listOf("E1", "E2", "plain").associateWith { slotOf(db, it) }
            assertEquals("重复回填不得改变任何值（幂等）", before, after)
            assertEquals(
                "重复回填后行数也不变",
                3,
                rowsOf(db, "match_events")
            )
        } finally {
            db.close()
        }
    }

    private companion object {
        /** 与 `RoundTraceDatabase.MIGRATION_3_4` 的 ② 逐字相同。 */
        val BACKFILL_LOGICAL_POSITION_SQL =
            "WITH RECURSIVE chain(id, rootOrder) AS (\n" +
                "    SELECT id, occurredOrder FROM `match_events` WHERE supersedesId IS NULL\n" +
                "    UNION ALL\n" +
                "    SELECT child.id, chain.rootOrder\n" +
                "      FROM `match_events` AS child\n" +
                "      JOIN chain ON child.supersedesId = chain.id\n" +
                ")\n" +
                "UPDATE `match_events`\n" +
                "   SET logicalPosition = COALESCE(\n" +
                "         (SELECT rootOrder FROM chain WHERE chain.id = `match_events`.id),\n" +
                "         occurredOrder)"

        /** 与 `RoundTraceDatabase.MIGRATION_3_4` 的 ③ 逐字相同。 */
        val BACKFILL_REVISION_SEQ_SQL =
            "WITH RECURSIVE chain(id, depth) AS (\n" +
                "    SELECT id, 0 FROM `match_events` WHERE supersedesId IS NULL\n" +
                "    UNION ALL\n" +
                "    SELECT child.id, chain.depth + 1\n" +
                "      FROM `match_events` AS child\n" +
                "      JOIN chain ON child.supersedesId = chain.id\n" +
                ")\n" +
                "UPDATE `match_events`\n" +
                "   SET revisionSeq = COALESCE(\n" +
                "         (SELECT depth FROM chain WHERE chain.id = `match_events`.id), 0)"
    }
}
