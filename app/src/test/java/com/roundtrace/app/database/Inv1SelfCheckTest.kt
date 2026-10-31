package com.roundtrace.app.database

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.core.database.RoundTraceDatabase
import com.roundtrace.app.core.database.findDuplicateActivePositions
import com.roundtrace.app.core.database.warnOnDuplicateActivePositions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B3：INV-1 启动自检（RT-003 / migration-plan §14-②）。
 *
 * 不变量：**同一 `matchId` 下，每个 `logicalPosition` 至多一条 `retracted = 0` 的事件**。
 *
 * 三条设计约束都被用例钉住：
 * 1. **只记录不修复**：`warnOnDuplicateActivePositions` 命中时不抛异常（它跑在 `onOpen` 里，
 *    抛异常等于让用户彻底打不开 App——脏数据比不能启动好处理）；
 * 2. **被撤销的历史版本共享位置是正常的**：查询必须带 `retracted = 0`，否则每次修订都会被误报；
 * 3. **有上限**：`LIMIT 20`，异常库不该把日志刷爆。
 *
 * 这里用 raw SQL 直接造「两条 active 同位置」的坏数据——正常写入路径不可能造出来
 * （`reviseWithSlot` 会先撤销旧行），所以只能绕过 DAO。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Inv1SelfCheckTest {

    private lateinit var database: RoundTraceDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RoundTraceDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun rawDb(): SupportSQLiteDatabase = database.openHelper.writableDatabase

    private fun insertMatch(db: SupportSQLiteDatabase, id: String) {
        db.execSQL(
            "INSERT INTO matches (id, gameType, rulesetId, rulesetVersion, presetId, playerCount, " +
                "configJson, status, title, mySeat, initialLeaderSeat, createdAt, updatedAt, " +
                "revealedAt, schemaVersion) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                id, "AVALON", "avalon.standard", 1, "avalon.standard.8p", 8, "{}",
                "DRAFT", "INV-1 测试对局", 1, 1, 0L, 0L, null, 1
            )
        )
    }

    private fun insertEvent(
        db: SupportSQLiteDatabase,
        id: String,
        matchId: String,
        logicalPosition: Int,
        occurredOrder: Int = logicalPosition,
        retracted: Boolean = false
    ) {
        db.execSQL(
            "INSERT INTO match_events (id, matchId, occurredOrder, logicalPosition, revisionSeq, " +
                "phaseKey, eventType, payloadVersion, payloadJson, visibility, recordedAt, " +
                "supersedesId, retracted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                id, matchId, occurredOrder, logicalPosition, 0,
                "QUEST_1", "QUEST_STARTED", 1, "{\"questNo\":1,\"leaderSeat\":1}",
                "PUBLIC", 1_700_000_000_000L, null, if (retracted) 1 else 0
            )
        )
    }

    @Test
    fun healthyDatabaseHasNoDuplicates() {
        val db = rawDb()
        insertMatch(db, "m1")
        insertEvent(db, "e1", "m1", logicalPosition = 1)
        insertEvent(db, "e2", "m1", logicalPosition = 2)

        assertTrue(
            "正常库不得报告重复位置，实际=${findDuplicateActivePositions(db)}",
            findDuplicateActivePositions(db).isEmpty()
        )
    }

    @Test
    fun detectsTwoActiveEventsSharingPosition() {
        val db = rawDb()
        insertMatch(db, "m1")
        // 绕过 DAO 造坏数据：同一位置两条 active
        insertEvent(db, "e1", "m1", logicalPosition = 5, occurredOrder = 5)
        insertEvent(db, "e2", "m1", logicalPosition = 5, occurredOrder = 9)

        val duplicates = findDuplicateActivePositions(db)
        assertEquals("应恰好命中 1 条记录", 1, duplicates.size)
        assertEquals("m1", duplicates.first().matchId)
        assertEquals("位置应为 5", 5, duplicates.first().logicalPosition)
        assertEquals("该位置 active 行数应为 2", 2, duplicates.first().count)
    }

    @Test
    fun retractedVersionsSharingPositionAreNotDuplicates() {
        val db = rawDb()
        insertMatch(db, "m1")
        // 真实的修订形态：旧版本 retracted=1、新版本 retracted=0，两者共享位置 5
        insertEvent(db, "old", "m1", logicalPosition = 5, occurredOrder = 5, retracted = true)
        insertEvent(db, "new", "m1", logicalPosition = 5, occurredOrder = 6, retracted = false)

        assertTrue(
            "被撤销的历史版本共享位置是正常形态，不得误报（查询必须带 retracted = 0）",
            findDuplicateActivePositions(db).isEmpty()
        )
    }

    @Test
    fun duplicatesAreIsolatedPerMatch() {
        val db = rawDb()
        insertMatch(db, "m1")
        insertMatch(db, "m2")
        // 两个对局各有一条位置 3 的 active —— 不是重复
        insertEvent(db, "a1", "m1", logicalPosition = 3)
        insertEvent(db, "b1", "m2", logicalPosition = 3)

        assertTrue(
            "不同对局的位置 3 互不相干，不得算重复",
            findDuplicateActivePositions(db).isEmpty()
        )
    }

    @Test
    fun duplicatesAreCappedAtTwenty() {
        val db = rawDb()
        insertMatch(db, "m1")
        // 造 25 个「各自重复一次」的位置
        repeat(25) { index ->
            val position = index + 1
            insertEvent(db, "a$position", "m1", logicalPosition = position, occurredOrder = position)
            insertEvent(db, "b$position", "m1", logicalPosition = position, occurredOrder = 100 + position)
        }

        assertEquals(
            "日志样本上限必须是 20（异常库不该刷爆日志）",
            20,
            findDuplicateActivePositions(db).size
        )
    }

    @Test
    fun selfCheckNeverThrowsAndDoesNotBlockStartup() {
        val db = rawDb()
        insertMatch(db, "m1")
        insertEvent(db, "e1", "m1", logicalPosition = 7, occurredOrder = 7)
        insertEvent(db, "e2", "m1", logicalPosition = 7, occurredOrder = 8)

        // 命中脏数据：只记录，不抛、不修复
        warnOnDuplicateActivePositions(db)

        // 数据不得被"自动修复"
        db.query("SELECT COUNT(*) FROM match_events WHERE retracted = 0").use { cursor ->
            cursor.moveToFirst()
            assertEquals("自检不得改动任何行", 2, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM match_events WHERE logicalPosition = 7").use { cursor ->
            cursor.moveToFirst()
            assertEquals("位置也不得被改写", 2, cursor.getInt(0))
        }
    }
}
