package com.roundtrace.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.core.data.RoomMatchRepository
import com.roundtrace.app.core.database.EventCodec
import com.roundtrace.app.core.database.RoundTraceDatabase
import com.roundtrace.app.core.database.dao.EventSlot
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.QuestStarted
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.util.SequentialIdGenerator
import com.roundtrace.app.core.util.fixedClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `MatchBundle.events` 的**顺序契约**测试（migration-plan §17.1-S3-4 五条 + 去重确定性）。
 *
 * 为什么单独一个文件：这五条是仓储对外承诺的**数据契约**，
 * 不是某个用例的副产物。任何一条被破坏，复盘选点、时间线、导出顺序都会跟着错。
 *
 * | # | 契约 |
 * |---|---|
 * | C1 | 不含 `retracted = true` |
 * | C2 | 按逻辑位置严格升序 |
 * | C3 | 每个位置至多一条（多版本取 `revisionSeq` 最大者） |
 * | C4 | `size` 不保证等于 `MAX(logicalPosition)`（撤销会留 dormant 位置） |
 * | C5 | 元素 `occurredOrder == logicalPosition` |
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MatchBundleOrderContractTest {

    private lateinit var database: RoundTraceDatabase
    private lateinit var repository: RoomMatchRepository
    private val codec = EventCodec()
    private val clock = fixedClock(1_700_000_000_000L)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RoundTraceDatabase::class.java
        ).allowMainThreadQueries().build()

        val registry = DefaultGameRulesRegistry()
        repository = RoomMatchRepository(
            database = database,
            gameRulesRegistry = registry,
            reducer = registry.avalonReducer,
            codec = codec,
            idGenerator = SequentialIdGenerator("id"),
            clock = clock
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun createMatch(): MatchId = repository.createMatch(
        MatchCreationDraft(
            title = "顺序契约测试",
            playerCount = 8,
            roleSlots = AvalonPreset.standardPreset(8),
            mySeat = 1,
            initialLeaderSeat = 1,
            participants = MatchCreationDraft.defaultParticipants(8),
            presetId = AvalonPreset.presetId(8),
            rulesetId = AvalonPreset.RULESET_ID,
            rulesetVersion = AvalonPreset.RULESET_VERSION
        )
    )

    private suspend fun append(matchId: MatchId, id: String, questNo: Int): EventSlot =
        database.matchEventDao().appendWithSlot(
            matchId = matchId.raw,
            entityProvider = { slot ->
                codec.encode(
                    QuestStarted(
                        eventId = EventId(id),
                        occurredOrder = 0,
                        recordedAt = 0L,
                        questNo = questNo,
                        leaderSeat = 1
                    ),
                    matchId,
                    slot.occurredOrder,
                    slot.logicalPosition,
                    slot.revisionSeq
                )
            },
            now = clock.millis()
        )

    // ————————————————————————————————————————————————————————
    // C1 / C2 / C3 / C5：正常路径
    // ————————————————————————————————————————————————————————

    @Test
    fun bundleEventsAreActiveAscendingUniqueAndCarryLogicalPosition() = runBlocking {
        val matchId = createMatch()
        (1..5).forEach { append(matchId, "e-$it", questNo = it) }

        val bundle = repository.getBundle(matchId)!!
        val events = bundle.events

        assertTrue("C1：不得包含 retracted 事件", events.none { it.retracted })
        assertEquals(
            "C2：必须按逻辑位置严格升序",
            events.map { it.occurredOrder }.sorted(),
            events.map { it.occurredOrder }
        )
        assertEquals(
            "C3：每个位置至多一条",
            events.size,
            events.map { it.occurredOrder }.distinct().size
        )

        // C5：领域 occurredOrder == 列 logicalPosition
        val entities = database.matchEventDao().listAll(matchId.raw).associateBy { it.id }
        events.forEach { event ->
            assertEquals(
                "C5：${event.eventId.raw} 的领域 occurredOrder 必须等于列 logicalPosition",
                entities.getValue(event.eventId.raw).logicalPosition,
                event.occurredOrder
            )
        }
    }

    // ————————————————————————————————————————————————————————
    // C4：撤销留下 dormant 位置，size != MAX(logicalPosition)
    // ————————————————————————————————————————————————————————

    @Test
    fun bundleSizeDoesNotEqualMaxPositionWhenPositionIsDormant() = runBlocking {
        val matchId = createMatch()
        (1..5).forEach { append(matchId, "e-$it", questNo = it) }

        // 撤销位置 3 → 该位置没有 active 版本（dormant），但位置号不会被回收
        repository.retractEvent(matchId, EventId("e-3"))

        val bundle = repository.getBundle(matchId)!!
        assertEquals("撤销后只剩 4 条 active", 4, bundle.events.size)
        assertEquals(
            "最大位置仍是 5（位置号不回收）",
            5,
            bundle.events.maxOf { it.occurredOrder }
        )
        assertTrue(
            "C4：size(4) 必须**不等于** MAX(logicalPosition)(5) —— 撤销会留空缺",
            bundle.events.size != bundle.events.maxOf { it.occurredOrder }
        )
        assertTrue("被撤销的位置不得出现", bundle.events.none { it.eventId.raw == "e-3" })
    }

    // ————————————————————————————————————————————————————————
    // C3 的异常路径：同位置两条 active 时，revisionSeq 大者胜且结果确定
    // ————————————————————————————————————————————————————————

    @Test
    fun duplicateActivePositionsResolveToHighestRevisionSeq() = runBlocking {
        val matchId = createMatch()
        append(matchId, "e-1", questNo = 1)
        append(matchId, "e-2", questNo = 2)

        // 绕过 DAO policy 直接插两条同位置 active（模拟不变量已被破坏的历史数据）。
        // 不变量本该由 reviseWithSlot 保证；这里要证明「坏数据也有确定输出」。
        val raw = database.openHelper.writableDatabase
        raw.execSQL(
            "INSERT INTO match_events (id, matchId, occurredOrder, logicalPosition, revisionSeq, " +
                "phaseKey, eventType, payloadVersion, payloadJson, visibility, recordedAt, " +
                "supersedesId, retracted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                "dup-low", matchId.raw, 90, 3, 1,
                "QUEST_3", "QUEST_STARTED", 1, "{\"questNo\":3,\"leaderSeat\":2}",
                "PUBLIC", 1L, null, 0
            )
        )
        raw.execSQL(
            "INSERT INTO match_events (id, matchId, occurredOrder, logicalPosition, revisionSeq, " +
                "phaseKey, eventType, payloadVersion, payloadJson, visibility, recordedAt, " +
                "supersedesId, retracted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                "dup-high", matchId.raw, 91, 3, 5,
                "QUEST_3", "QUEST_STARTED", 1, "{\"questNo\":3,\"leaderSeat\":4}",
                "PUBLIC", 2L, null, 0
            )
        )

        val bundle = repository.getBundle(matchId)!!

        assertEquals("位置 3 只能保留一条", 1, bundle.events.count { it.occurredOrder == 3 })
        assertEquals(
            "胜者必须是 revisionSeq 更大的那条（dup-high, leaderSeat=4）",
            "dup-high",
            bundle.events.first { it.occurredOrder == 3 }.eventId.raw
        )
        assertEquals(
            "总条数应为 3（位置 1、2、3）",
            3,
            bundle.events.size
        )
        assertEquals(
            "被丢弃的行**不得**被删除（审计仍可见）",
            true,
            database.matchEventDao().listAll(matchId.raw).any { it.id == "dup-low" }
        )
    }

    @Test
    fun duplicateResolutionIsDeterministicAcrossReads() = runBlocking {
        val matchId = createMatch()
        append(matchId, "e-1", questNo = 1)

        val raw = database.openHelper.writableDatabase
        // revisionSeq 相同的极端情形：必须靠 recordedAt / id 决出唯一结果
        listOf(
            Triple("same-b", 2L, 7),
            Triple("same-a", 2L, 7),
            Triple("same-c", 2L, 7)
        ).forEach { (id, recordedAt, position) ->
            raw.execSQL(
                "INSERT INTO match_events (id, matchId, occurredOrder, logicalPosition, revisionSeq, " +
                    "phaseKey, eventType, payloadVersion, payloadJson, visibility, recordedAt, " +
                    "supersedesId, retracted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    id, matchId.raw, 50, position, 0,
                    "QUEST_2", "QUEST_STARTED", 1, "{\"questNo\":2,\"leaderSeat\":1}",
                    "PUBLIC", recordedAt, null, 0
                )
            )
        }

        val first = repository.getBundle(matchId)!!.events.first { it.occurredOrder == 7 }.eventId.raw
        val second = repository.getBundle(matchId)!!.events.first { it.occurredOrder == 7 }.eventId.raw

        assertEquals("同一份数据两次读取结果必须相同", first, second)
        assertTrue(
            "revisionSeq/recordedAt 全相同时按 id 字典序取胜者（此处为 same-a），实际=$first",
            first == "same-a"
        )
    }
}
