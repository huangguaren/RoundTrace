package com.roundtrace.app.audit

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.core.database.RoundTraceDatabase
import com.roundtrace.app.core.database.entity.MatchEventEntity
import com.roundtrace.app.database.LegacyV3Database
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QA 阶段 D · V01 / V02：v3→v4 迁移的**独立**复核。
 *
 * 与工程师的 `MigrationBackfillManualTest` 的区别（避免只复跑别人跑过的东西）：
 * 1. 这里从**真实 v3 DDL**（`schemas/3.json`）造库，然后断言「迁移后 Room 能读」——
 *    即 Room 的 schema 校验真的通过，而不只是 SQL 没报错；
 * 2. 三层修订链 + dormant（全部版本都 retracted）位置 + 迁移后新事件不复用旧位置；
 * 3. 一个**对抗性**用例：`supersedesId` 跨对局指向时，回填 CTE 不分 matchId 会发生什么
 *    （见 `v01b_*`，按当前行为做特征化断言，结论写在 `docs/audit/qa-phase-d.md`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationAuditTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // ————————————————————————————————————————————————————————
    // V01：基础升级
    // ————————————————————————————————————————————————————————

    @Test
    fun v01_migration_backfills_columns_and_room_validates() = runBlocking {
        val name = "audit-v01.db"
        LegacyV3Database.create(context, name) { db ->
            LegacyV3Database.insertV3Match(db, "m1")
            (1..5).forEach { i -> LegacyV3Database.insertV3Event(db, "e$i", "m1", i) }
        }

        val room = RoundTraceDatabase.build(context, name)
        try {
            val raw = room.openHelper.writableDatabase

            assertEquals("迁移后 user_version 必须为 4", 4, LegacyV3Database.userVersion(raw))

            val columns = columnsOf(raw, "match_events")
            assertTrue("必须新增 logicalPosition：$columns", columns.contains("logicalPosition"))
            assertTrue("必须新增 revisionSeq：$columns", columns.contains("revisionSeq"))

            // 无 supersedesId 的普通事件：位置 = 写入序号，版本 = 0
            (1..5).forEach { i ->
                assertEquals(
                    "e$i 的 (logicalPosition, revisionSeq) 应为 ($i, 0)",
                    i to 0,
                    LegacyV3Database.slotOf(raw, "e$i")
                )
            }

            assertTrue(
                "读路径索引必须建好",
                LegacyV3Database.hasIndex(
                    raw,
                    "index_match_events_matchId_logicalPosition_revisionSeq"
                )
            )

            // ★ Room 侧校验：能列出 active 且顺序按 logicalPosition
            val active = room.matchEventDao().listActive("m1")
            assertEquals("迁移后 5 条事件必须都可读", 5, active.size)
            assertEquals(
                "active 顺序必须是逻辑位置升序",
                listOf(1, 2, 3, 4, 5),
                active.map { it.logicalPosition }
            )
            assertEquals(
                "revisionSeq 全部为 0",
                listOf(0, 0, 0, 0, 0),
                active.map { it.revisionSeq }
            )

            // 迁移不改写用户数据
            assertEquals("matches 行数不变", 1, LegacyV3Database.rowsOf(raw, "matches"))
        } finally {
            room.close()
        }
    }

    // ————————————————————————————————————————————————————————
    // V02：连续修订链
    // ————————————————————————————————————————————————————————

    /**
     * 三层修订链 + 一个 dormant 位置。
     *
     * v3 数据：
     * - 位置 1：a0(order1, retracted) ← a1(order2, retracted) ← a2(order3, active)
     * - 位置 4：b0(order4, retracted)   ← 没有后继（dormant）
     * - 位置 5：c0(order5, active)
     *
     * 迁移后期望：a0/a1/a2 都是 logicalPosition=1，seq=0/1/2；b0=position 4, seq 0；
     * c0=position 5, seq 0；active 恰好是 a2、c0。
     */
    @Test
    fun v02_three_level_chain_shares_position_with_increasing_seq() = runBlocking {
        val name = "audit-v02.db"
        LegacyV3Database.create(context, name) { db ->
            LegacyV3Database.insertV3Match(db, "m1")
            LegacyV3Database.insertV3Event(db, "a0", "m1", 1, supersedesId = null, retracted = true)
            LegacyV3Database.insertV3Event(db, "a1", "m1", 2, supersedesId = "a0", retracted = true)
            LegacyV3Database.insertV3Event(db, "a2", "m1", 3, supersedesId = "a1", retracted = false)
            LegacyV3Database.insertV3Event(db, "b0", "m1", 4, supersedesId = null, retracted = true)
            LegacyV3Database.insertV3Event(db, "c0", "m1", 5, supersedesId = null, retracted = false)
        }

        val room = RoundTraceDatabase.build(context, name)
        try {
            val raw = room.openHelper.writableDatabase

            assertEquals("链根 a0", 1 to 0, LegacyV3Database.slotOf(raw, "a0"))
            assertEquals("链中层 a1", 1 to 1, LegacyV3Database.slotOf(raw, "a1"))
            assertEquals("链末 a2", 1 to 2, LegacyV3Database.slotOf(raw, "a2"))
            assertEquals("dormant 位置 b0", 4 to 0, LegacyV3Database.slotOf(raw, "b0"))
            assertEquals("普通位置 c0", 5 to 0, LegacyV3Database.slotOf(raw, "c0"))

            val active = room.matchEventDao().listActive("m1")
            assertEquals(
                "active 应只有链末 a2 与 c0",
                listOf("a2", "c0"),
                active.map { it.id }
            )
            assertEquals(
                "active 的逻辑位置必须是 1、5",
                listOf(1, 5),
                active.map { it.logicalPosition }
            )

            // INV-1：每个位置至多一条 active
            val dup = LegacyV3Database.count(
                raw,
                "SELECT COUNT(*) FROM (SELECT logicalPosition FROM match_events " +
                    "WHERE matchId='m1' AND retracted=0 GROUP BY logicalPosition HAVING COUNT(*)>1)"
            )
            assertEquals("迁移后不得出现同位置多条 active", 0, dup)

            // 审计视图能看出「一个位置三个版本」
            val all = room.matchEventDao().listAll("m1")
            assertEquals("审计视图应有 5 行", 5, all.size)
            assertEquals(
                "位置 1 必须有三条历史版本",
                3,
                all.count { it.logicalPosition == 1 }
            )
        } finally {
            room.close()
        }
    }

    /** 迁移后新追加的事件**不得复用** dormant 位置（位置无空洞可复用）。 */
    @Test
    fun v02b_new_event_after_migration_does_not_reuse_dormant_position() = runBlocking {
        val name = "audit-v02b.db"
        LegacyV3Database.create(context, name) { db ->
            LegacyV3Database.insertV3Match(db, "m1")
            LegacyV3Database.insertV3Event(db, "e1", "m1", 1)
            LegacyV3Database.insertV3Event(db, "e2", "m1", 2, retracted = true)
            LegacyV3Database.insertV3Event(db, "e3", "m1", 3)
        }

        val room = RoundTraceDatabase.build(context, name)
        try {
            val dao = room.matchEventDao()
            assertEquals("下一个位置必须是 MAX+1=4，而不是复用被撤销的 2", 4, dao.nextLogicalPosition("m1"))

            val slot = dao.appendWithSlot(
                matchId = "m1",
                entityProvider = { s ->
                    MatchEventEntity(
                        id = "e4",
                        matchId = "m1",
                        occurredOrder = s.occurredOrder,
                        logicalPosition = s.logicalPosition,
                        revisionSeq = s.revisionSeq,
                        phaseKey = "QUEST_1",
                        eventType = "QUEST_STARTED",
                        payloadJson = "{\"questNo\":1,\"leaderSeat\":1}",
                        visibility = "PUBLIC",
                        recordedAt = 1L,
                        supersedesId = null,
                        retracted = false
                    )
                },
                now = 2L
            )
            assertEquals("新事件必须落在位置 4", 4, slot.logicalPosition)
            assertEquals("新事件 revisionSeq 应为 0", 0, slot.revisionSeq)
            assertEquals("写入序号应继续递增", 4, slot.occurredOrder)
        } finally {
            room.close()
        }
    }

    // ————————————————————————————————————————————————————————
    // 对抗性：跨对局的 supersedesId
    // ————————————————————————————————————————————————————————

    /**
     * 跨局 `supersedesId` 的回填行为。
     *
     * ## 【历史说明】这条用例断言的方向被 F2 翻转了
     *
     * 阶段 D 验收时（HEAD=`30e328a`）它是**特征化用例**：那时两条递归 CTE **不按 matchId 分区**，
     * 跨局子行会继承**另一对局**的链根位置（实测 `f2 → logicalPosition=2, revisionSeq=1`），
     * 从而可能与本局既有位置撞车、破坏 INV-1。
     *
     * 这条缺陷的定级依据是「**可由 RT-002 修复前的历史数据触达**」：`ee20e3d^` 的 `reviseEvent`
     * 只按 eventId 查目标、`EventRevisionPolicy.check(..., sameMatch = true)` 是硬编码断言而非校验，
     * 所以那段时间里一次跨局调用就会在库里留下跨局链。
     *
     * F2 给两条 CTE 都加了 `AND child.matchId = chain.matchId` 之后，跨局链在本局查不到祖先，
     * 自然落到 `COALESCE(..., occurredOrder)` 兜底 —— 下面的断言已翻转为**修复后应然行为**。
     * 改动原因与验收记录见 `docs/audit/qa-phase-d.md` §3.1 / §7。
     */
    @Test
    fun v01b_cross_match_supersedes_chain_falls_back_to_own_order() {
        val name = "audit-v01b.db"
        LegacyV3Database.create(context, name) { db ->
            LegacyV3Database.insertV3Match(db, "mA")
            LegacyV3Database.insertV3Match(db, "mB")
            // mA：普通事件（位置应为 1、2）
            LegacyV3Database.insertV3Event(db, "a1", "mA", 1)
            LegacyV3Database.insertV3Event(db, "a2", "mA", 2)
            // mB：f1 位于 1；f2 的 supersedesId 指向 mA 的 a2（跨局脏链）
            LegacyV3Database.insertV3Event(db, "f1", "mB", 1)
            LegacyV3Database.insertV3Event(db, "f2", "mB", 3, supersedesId = "a2")
        }

        val room = RoundTraceDatabase.build(context, name)
        try {
            val raw = room.openHelper.writableDatabase

            assertEquals("mA 不受影响", 2 to 0, LegacyV3Database.slotOf(raw, "a2"))
            assertEquals("mB 的 f1 在位置 1", 1 to 0, LegacyV3Database.slotOf(raw, "f1"))

            val (f2Pos, f2Seq) = LegacyV3Database.slotOf(raw, "f2")
            println("[AUDIT-D] 跨局脏链：mB.f2 → logicalPosition=$f2Pos, revisionSeq=$f2Seq")

            assertEquals(
                "F2：跨局链查不到祖先，f2 必须回落到自己的写入序号（3）",
                3,
                f2Pos
            )
            assertEquals("F2：既然没接到链上，revisionSeq 必须回到 0（不再是第二版本）", 0, f2Seq)

            val mBDup = LegacyV3Database.count(
                raw,
                "SELECT COUNT(*) FROM (SELECT logicalPosition FROM match_events " +
                    "WHERE matchId='mB' AND retracted=0 GROUP BY logicalPosition HAVING COUNT(*)>1)"
            )
            println("[AUDIT-D] 跨局脏链下 mB 的重复 active 位置数=$mBDup")
            assertEquals("F2：mB 不得出现同位置多条 active", 0, mBDup)
        } finally {
            room.close()
        }
    }

    /**
     * 跨局链**曾经会撞车**的构型：子行继承的链根位置与本局既有 active 位置相同。
     *
     * F2 之后该构造不再撞车（见断言）。这条用例的价值是**守住修复**：
     * 如果哪天有人把 `AND child.matchId = chain.matchId` 去掉，`mBDup` 会重新变成 1。
     */
    @Test
    fun v01b2_cross_match_chain_no_longer_collides_with_local_position() = runBlocking {
        val name = "audit-v01b2.db"
        LegacyV3Database.create(context, name) { db ->
            LegacyV3Database.insertV3Match(db, "mA")
            LegacyV3Database.insertV3Match(db, "mB")
            LegacyV3Database.insertV3Event(db, "a1", "mA", 1)     // mA 位置 1
            LegacyV3Database.insertV3Event(db, "f1", "mB", 1)     // mB 位置 1（active）
            LegacyV3Database.insertV3Event(db, "f2", "mB", 2, supersedesId = "a1") // 修复前会继承 mA 位置 1
        }

        val room = RoundTraceDatabase.build(context, name)
        try {
            val raw = room.openHelper.writableDatabase
            val mBDup = LegacyV3Database.count(
                raw,
                "SELECT COUNT(*) FROM (SELECT logicalPosition FROM match_events " +
                    "WHERE matchId='mB' AND retracted=0 GROUP BY logicalPosition HAVING COUNT(*)>1)"
            )
            val active = room.matchEventDao().listActive("mB")
            println(
                "[AUDIT-D] 撞车构型（F2 后）：mB active=" +
                    active.map { it.id + "@" + it.logicalPosition + "/" + it.revisionSeq } +
                    "，重复位置数=$mBDup"
            )

            assertEquals(
                "F2：f2 回落到自己的写入序号 2，而不是继承 mA 的位置 1",
                2,
                LegacyV3Database.slotOf(raw, "f2").first
            )
            assertEquals("F2：mB 不得出现同位置两条 active", 0, mBDup)
            assertEquals("两条 active 各占一个位置", 2, active.size)
            assertEquals(
                "位置必须是 1、2，不重号",
                listOf(1, 2),
                active.map { it.logicalPosition }.sorted()
            )
        } finally {
            room.close()
        }
    }

    // ————————————————————————————————————————————————————————

    private fun columnsOf(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info($table)").use { cursor ->
            val out = mutableListOf<String>()
            while (cursor.moveToNext()) {
                out += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            out
        }

    @Suppress("unused")
    private fun assertNoNull(value: Any?) = assertNotNull(value)
}
