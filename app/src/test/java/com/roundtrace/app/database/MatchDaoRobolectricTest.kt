package com.roundtrace.app.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.core.database.EventCodec
import com.roundtrace.app.core.database.RoundTraceDatabase
import com.roundtrace.app.core.database.entity.MatchEntity
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.QuestStarted
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D01 的 **JVM（Robolectric）** 版本（TASKS.md T43①）。
 *
 * 与 `src/androidTest/.../MatchDaoTest.kt` 是**并行保障**：
 * 这份在 JVM 上跑，不依赖设备；androidTest 那份留给接真机时用。
 *
 * 核心目的：用**真实 Room + 真实 SQLite** 钉死
 * 「`occurredOrder` 必须由仓储盖章为 1..N，连续、无空洞、无重复」。
 *
 * Robolectric 需要下载 `android-all-instrumented`（约 100MB），
 * 已通过 `robolectric.dependency.repo.url` 指向阿里云镜像（见 docs/decisions.md B28）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MatchDaoRobolectricTest {

    private lateinit var database: RoundTraceDatabase
    private val codec = EventCodec()
    private val matchId = MatchId("d01-jvm-match")

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RoundTraceDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun insertMatch() {
        database.matchDao().insert(
            MatchEntity(
                id = matchId.raw,
                gameType = "AVALON",
                rulesetId = "avalon.standard",
                rulesetVersion = 1,
                presetId = "avalon.standard.8p",
                playerCount = 8,
                configJson = "{}",
                status = "DRAFT",
                title = "D01 JVM 测试对局",
                mySeat = 1,
                initialLeaderSeat = 1,
                createdAt = 0L,
                updatedAt = 0L,
                revealedAt = null,
                schemaVersion = 1
            )
        )
    }

    /**
     * 追加一条 `QUEST_STARTED`。
     *
     * 传进来的领域事件 `occurredOrder` 恒为 0——这是领域层的契约，
     * 真实 order 只能由 DAO 的 `COALESCE(MAX(occurredOrder),0)+1` 分配。
     */
    private suspend fun appendQuestStarted(tag: Int, now: Long): Int =
        database.matchEventDao().appendWithOrder(
            matchId = matchId.raw,
            entityProvider = { order ->
                val event = QuestStarted(
                    eventId = EventId("d01-jvm-$tag"),
                    occurredOrder = 0,
                    recordedAt = now,
                    questNo = 1,
                    leaderSeat = 1
                )
                codec.encode(event, matchId, order)
            },
            now = now
        )

    // ————————————————————————————————————————————————————————
    // 核心断言：1..N 连续无空洞、无重复、严格递增
    // ————————————————————————————————————————————————————————

    @Test
    fun d01_twentyEventsHaveContiguousOrderFromOne() = runBlocking {
        insertMatch()

        val assigned = (0 until 20).map { index -> appendQuestStarted(index, index.toLong()) }

        assertEquals(
            "appendWithOrder 必须依次返回 1..20，实际=$assigned",
            (1..20).toList(),
            assigned
        )

        val stored = database.matchEventDao().listActive(matchId.raw)
        val orders = stored.map { it.occurredOrder }

        assertEquals("应落库 20 条事件", 20, stored.size)
        assertEquals("落库顺序必须为 1..20", (1..20).toList(), orders)
        assertEquals("occurredOrder 不得重复", 20, orders.distinct().size)
        assertTrue(
            "必须严格递增（无空洞）",
            orders.zipWithNext().all { (previous, next) -> next == previous + 1 }
        )
        assertEquals("首条必须是 1，不是 0", 1, orders.first())
    }

    @Test
    fun d01_appendWithOrderReturnsContiguousOrdersAfterRetraction() = runBlocking {
        insertMatch()
        appendQuestStarted(0, 1L)
        appendQuestStarted(1, 2L)
        val third = appendQuestStarted(2, 3L)

        // 撤销中间那条（模拟用户改错）——order 计数器不回退，保证审计链不撞号
        val middle = database.matchEventDao().listActive(matchId.raw)
            .first { it.occurredOrder == 2 }
        database.matchEventDao().markRetracted(middle.id)

        val fourth = appendQuestStarted(3, 4L)
        assertEquals("撤销后新事件仍应顺延", 4, fourth)

        val activeOrders = database.matchEventDao().listActive(matchId.raw).map { it.occurredOrder }
        assertEquals("读路径只剩 1、3、4", listOf(1, 3, fourth), activeOrders)
        assertEquals("第三条原始 order 应为 3", 3, third)
    }

    @Test
    fun d01_maxOccurredOrderTracksAppends() = runBlocking {
        insertMatch()
        assertEquals(0, database.matchEventDao().maxOccurredOrder(matchId.raw))
        appendQuestStarted(0, 1L)
        assertEquals(1, database.matchEventDao().maxOccurredOrder(matchId.raw))
        appendQuestStarted(1, 2L)
        assertEquals(2, database.matchEventDao().maxOccurredOrder(matchId.raw))
        assertEquals(3, database.matchEventDao().nextOccurredOrder(matchId.raw))
    }

    @Test
    fun d01_appendTouchesMatchUpdatedAt() = runBlocking {
        insertMatch()
        appendQuestStarted(0, 500L)
        val entity = database.matchDao().findById(matchId.raw)
        assertNotNull(entity)
        assertEquals("appendWithOrder 必须更新 matches.updatedAt", 500L, entity?.updatedAt)
    }

    // ————————————————————————————————————————————————————————
    // 序列化契约：order 只在列，payload 不双写
    // ————————————————————————————————————————————————————————

    @Test
    fun d01_payloadDoesNotDuplicateOrder() = runBlocking {
        insertMatch()
        appendQuestStarted(0, 1L)

        val stored = database.matchEventDao().listActive(matchId.raw).first()
        assertTrue(
            "payload 不得包含 occurredOrder：${stored.payloadJson}",
            !stored.payloadJson.contains("occurredOrder")
        )
        assertTrue(
            "payload 不得包含 eventId：${stored.payloadJson}",
            !stored.payloadJson.contains("eventId")
        )
        assertEquals("payloadVersion 应为 1", 1, stored.payloadVersion)
        assertEquals("phaseKey 应为 QUEST_1", "QUEST_1", stored.phaseKey)
        assertEquals("eventType 应为 QUEST_STARTED", "QUEST_STARTED", stored.eventType)
    }

    @Test
    fun d01_retractedHiddenFromReadPathButKeptForAudit() = runBlocking {
        insertMatch()
        appendQuestStarted(0, 1L)
        val target = database.matchEventDao().listActive(matchId.raw).first()

        database.matchEventDao().markRetracted(target.id)

        assertEquals(
            "retracted 事件不得出现在读路径",
            0,
            database.matchEventDao().listActive(matchId.raw).size
        )
        assertEquals(
            "但审计链必须保留",
            1,
            database.matchEventDao().listAll(matchId.raw).size
        )
    }

    @Test
    fun d01_countByTypeIgnoresRetracted() = runBlocking {
        insertMatch()
        appendQuestStarted(0, 1L)
        appendQuestStarted(1, 1L)
        assertEquals(2, database.matchEventDao().countByType(matchId.raw, "QUEST_STARTED"))

        val first = database.matchEventDao().listActive(matchId.raw).first()
        database.matchEventDao().markRetracted(first.id)
        assertEquals(
            "撤销后计数应减 1",
            1,
            database.matchEventDao().countByType(matchId.raw, "QUEST_STARTED")
        )
    }

    // ————————————————————————————————————————————————————————
    // 类型转换回归（主理人 §三.4）
    // ————————————————————————————————————————————————————————

    @Test
    fun d01_booleanColumnRoundTrip() = runBlocking {
        insertMatch()
        appendQuestStarted(0, 1L)
        val target = database.matchEventDao().listActive(matchId.raw).first()
        assertEquals("新建事件 retracted 应为 false", false, target.retracted)

        database.matchEventDao().markRetracted(target.id)
        val retracted = database.matchEventDao().listAll(matchId.raw).first()
        assertEquals("撤销后 retracted 应为 true", true, retracted.retracted)
        assertNotEquals("读路径与审计链是两份不同视图", 1, database.matchEventDao().listActive(matchId.raw).size)
    }
}
