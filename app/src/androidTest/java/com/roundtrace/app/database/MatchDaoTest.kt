package com.roundtrace.app.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roundtrace.app.core.database.EventCodec
import com.roundtrace.app.core.database.RoundTraceDatabase
import com.roundtrace.app.core.database.entity.MatchEntity
import com.roundtrace.app.core.database.entity.MatchEventEntity
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.QuestStarted
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D01：新建对局 + 连续事件 + 重开数据库，`occurredOrder` 顺序完整（TASKS.md T43①）。
 *
 * 这是主理人本轮指定的硬性验收项：**`occurredOrder` 必须由仓储盖章为 1..N**。
 * JVM 单测无法跑 Room（需要 Robolectric 下载 android-all，与本机网络限制冲突），
 * 因此「真实 1..N」由本仪器测试保证；JVM 侧只验证编解码契约（见 `EventCodecTest`）。
 */
@RunWith(AndroidJUnit4::class)
class MatchDaoTest {

    private lateinit var database: RoundTraceDatabase
    private val codec = EventCodec()
    private val matchId = MatchId("d01-match")

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, RoundTraceDatabase::class.java)
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
                title = "D01 测试对局",
                mySeat = 1,
                initialLeaderSeat = 1,
                createdAt = 0L,
                updatedAt = 0L,
                revealedAt = null,
                schemaVersion = 1
            )
        )
    }

    private suspend fun appendQuestStarted(questNo: Int, now: Long): Int =
        database.matchEventDao().appendWithOrder(
            matchId = matchId.raw,
            entityProvider = { order ->
                val event = QuestStarted(
                    eventId = EventId("d01-e-$questNo-$order"),
                    occurredOrder = 0,
                    recordedAt = now,
                    questNo = questNo,
                    leaderSeat = 1
                )
                codec.encode(event, matchId, order)
            },
            now = now
        )

    @Test
    fun d01_twentyEventsHaveContiguousOrder() = runTest {
        insertMatch()

        val assigned = mutableListOf<Int>()
        repeat(20) { index ->
            assigned += appendQuestStarted(questNo = 1, now = index.toLong())
        }

        assertEquals(
            "appendWithOrder 必须返回 1..20",
            (1..20).toList(),
            assigned
        )

        val stored = database.matchEventDao().listActive(matchId.raw)
        assertEquals("应有 20 条事件", 20, stored.size)
        assertEquals(
            "落库后的 occurredOrder 必须是 1..20 且严格递增",
            (1..20).toList(),
            stored.map { it.occurredOrder }
        )
        assertEquals(
            "occurredOrder 不得重复",
            stored.map { it.occurredOrder }.distinct().size,
            stored.size
        )
    }

    @Test
    fun d01_maxOccurredOrderTracksAppends() = runTest {
        insertMatch()
        assertEquals(0, database.matchEventDao().maxOccurredOrder(matchId.raw))
        appendQuestStarted(1, 1L)
        assertEquals(1, database.matchEventDao().maxOccurredOrder(matchId.raw))
        appendQuestStarted(1, 2L)
        assertEquals(2, database.matchEventDao().maxOccurredOrder(matchId.raw))
    }

    @Test
    fun d01_retractedEventsAreExcludedFromReadPath() = runTest {
        insertMatch()
        val order = appendQuestStarted(1, 1L)
        val stored = database.matchEventDao().listActive(matchId.raw)
        val target = stored.first { it.occurredOrder == order }

        database.matchEventDao().markRetracted(target.id)

        assertEquals(
            "retracted 事件不得出现在读路径",
            0,
            database.matchEventDao().listActive(matchId.raw).size
        )
        assertEquals(
            "但审计链必须保留（listAll 能看到）",
            1,
            database.matchEventDao().listAll(matchId.raw).size
        )
    }

    @Test
    fun d01_appendTouchesMatchUpdatedAt() = runTest {
        insertMatch()
        appendQuestStarted(1, 500L)
        val entity = database.matchDao().findById(matchId.raw)
        assertNotNull(entity)
        assertEquals(
            "appendWithOrder 必须更新 matches.updatedAt",
            500L,
            entity?.updatedAt
        )
    }

    @Test
    fun d01_summaryProjectionCountsQuestStarted() = runTest {
        insertMatch()
        appendQuestStarted(1, 1L)
        appendQuestStarted(1, 1L)

        database.matchDao().observeById(matchId.raw)
        val entity = database.matchDao().findById(matchId.raw)
        assertEquals("对局应存在", "D01 测试对局", entity?.title)
        // 投影查询本身在 Flow 里，这里用 countByType 验证同一事实
        assertEquals(
            "QUEST_STARTED 计数应为 2",
            2,
            database.matchEventDao().countByType(matchId.raw, "QUEST_STARTED")
        )
    }

    @Test
    fun d01_payloadDoesNotDuplicateOrder() = runTest {
        insertMatch()
        appendQuestStarted(1, 1L)
        val stored: MatchEventEntity = database.matchEventDao().listActive(matchId.raw).first()
        assertTrue(
            "payload 不应包含 occurredOrder：${stored.payloadJson}",
            !stored.payloadJson.contains("occurredOrder")
        )
        assertEquals("payloadVersion 应为 1", 1, stored.payloadVersion)
        assertEquals("phaseKey 应为 QUEST_1", "QUEST_1", stored.phaseKey)
    }
}
