package com.roundtrace.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.core.data.RoomMatchRepository
import com.roundtrace.app.core.database.EventCodec
import com.roundtrace.app.core.database.RoundTraceDatabase
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.QuestStarted
import com.roundtrace.app.core.model.TeamProposalResolved
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteRecorded
import com.roundtrace.app.core.replay.ReplayEngine
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
 * B-2：一次性投影刷新（migration-plan §17.1-S3-5）。
 *
 * 读路径切到逻辑位置后，老对局的 `matches.status` 是「用旧顺序算好并冻结的列」，
 * 可能与重放结论不一致。刷新负责把列重新对齐到重放结果。
 *
 * **本用例最核心的一条不是「状态刷对了」，而是「刷完 `updatedAt` 没变」**：
 * 如果刷新走 `updateStatusOnly`（会写 `updatedAt = now`），
 * 用户升级后会发现所有几个月前的对局突然全部跳到首页顶部 ——
 * 一个"数据修复"把列表顺序搞乱了，比不修更难解释。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectionRefreshTest {

    private lateinit var database: RoundTraceDatabase
    private lateinit var repository: RoomMatchRepository
    private val codec = EventCodec()
    private val clock = fixedClock(1_700_000_000_000L)
    private val reducer = DefaultGameRulesRegistry().avalonReducer

    /** 模拟「很久以前的对局」的时间戳。 */
    private val oldTimestamp = 1_600_000_000_000L

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

    private suspend fun createMatch(title: String = "投影刷新测试"): MatchId =
        repository.createMatch(
            MatchCreationDraft(
                title = title,
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

    /**
     * 追加事件走**真实写入路径** `repository.appendEvent`。
     *
     * 为什么不用 `matchEventDao.appendWithSlot` 直插：直插不会触发 `recomputeProjectionLocked`，
     * `matches.status` 会停在 DRAFT —— 那样根本造不出「旧模型算出的结论」这个前提。
     */
    private suspend fun append(
        matchId: MatchId,
        event: com.roundtrace.app.core.model.MatchEvent
    ) {
        val result = repository.appendEvent(matchId, event)
        check(result.isSuccess) { "追加事件失败：${result.error}" }
    }

    /** 追加一次必定失败的任务（1 张失败牌）。 */
    private suspend fun appendFailedQuest(
        matchId: MatchId,
        questNo: Int,
        team: List<Int>,
        tag: String = "m"
    ) {
        append(
            matchId,
            QuestStarted(
                eventId = EventId("$tag-q$questNo-start"),
                occurredOrder = 0,
                recordedAt = 0L,
                questNo = questNo,
                leaderSeat = 1
            )
        )
        append(
            matchId,
            TeamProposed(
                eventId = EventId("$tag-q$questNo-proposal"),
                occurredOrder = 0,
                recordedAt = 0L,
                questNo = questNo,
                proposalNo = 1,
                leaderSeat = 1,
                teamSeats = team
            )
        )
        append(
            matchId,
            TeamVoteRecorded(
                eventId = EventId("$tag-q$questNo-vote"),
                occurredOrder = 0,
                recordedAt = 0L,
                questNo = questNo,
                proposalNo = 1,
                votesBySeat = null,
                approveCount = 8,
                rejectCount = 0,
                playerCountAtVote = 8
            )
        )
        append(
            matchId,
            TeamProposalResolved(
                eventId = EventId("$tag-q$questNo-resolved"),
                occurredOrder = 0,
                recordedAt = 0L,
                questNo = questNo,
                proposalNo = 1,
                decision = ProposalDecision.APPROVED,
                approveCount = 8,
                rejectCount = 0,
                playerCountAtVote = 8
            )
        )
        append(
            matchId,
            MissionResolved(
                eventId = EventId("$tag-q$questNo-mission"),
                occurredOrder = 0,
                recordedAt = 0L,
                questNo = questNo,
                teamSeats = team,
                teamSizeExpected = team.size,
                failCount = 1,
                observedOutcome = MissionOutcome.FAILED
            )
        )
    }

    private fun rawDb() = database.openHelper.writableDatabase

    private fun storedStatus(matchId: MatchId): MatchStatus =
        MatchStatus.valueOf(
            runBlocking { database.matchDao().findById(matchId.raw) }!!.status
        )

    private fun storedUpdatedAt(matchId: MatchId): Long =
        runBlocking { database.matchDao().findById(matchId.raw) }!!.updatedAt

    /** 把某局伪造成「旧顺序模型算出来的结果」：结论过期 + 时间戳很旧。 */
    private fun makeStaleProjection(matchId: MatchId, staleStatus: MatchStatus) {
        rawDb().execSQL(
            "UPDATE matches SET status = ?, updatedAt = ? WHERE id = ?",
            arrayOf(staleStatus.name, oldTimestamp, matchId.raw)
        )
    }

    private fun replayDerivedStatus(matchId: MatchId): MatchStatus =
        ReplayEngine(reducer)
            .replay(runBlocking { repository.getBundle(matchId) }!!.toReconstructedMatch())
            .state
            .status

    // ————————————————————————————————————————————————————————
    // 核心：结论刷新，但 updatedAt 必须不变
    // ————————————————————————————————————————————————————————

    @Test
    fun refreshRealignsStatusButLeavesUpdatedAtUntouched() = runBlocking {
        val matchId = createMatch()
        appendFailedQuest(matchId, 1, listOf(1, 2, 3))
        appendFailedQuest(matchId, 2, listOf(1, 2, 3, 4))
        appendFailedQuest(matchId, 3, listOf(4, 6, 7, 8))
        assertEquals("前置条件：三次失败应为 FINISHED_EVIL", MatchStatus.FINISHED_EVIL, storedStatus(matchId))
        val derived = replayDerivedStatus(matchId)
        assertEquals("前置条件：重放结论与列一致", MatchStatus.FINISHED_EVIL, derived)

        // 伪造「旧模型算出的过期结论」
        makeStaleProjection(matchId, MatchStatus.IN_PROGRESS)
        assertEquals("前置条件：列已被改成过期结论", MatchStatus.IN_PROGRESS, storedStatus(matchId))
        assertEquals("前置条件：时间戳已被改旧", oldTimestamp, storedUpdatedAt(matchId))

        val result = repository.recomputeAllProjections()

        assertEquals("应处理 1 局", 1, result.processed)
        assertTrue("全部成功时 isComplete 必须为 true", result.isComplete)
        assertEquals(
            "刷新后列必须等于重放结论",
            derived,
            storedStatus(matchId)
        )
        assertEquals(
            "**刷新绝不能改 updatedAt** —— 否则老对局会全部跳到首页顶部",
            oldTimestamp,
            storedUpdatedAt(matchId)
        )
    }

    @Test
    fun refreshIsIdempotent() = runBlocking {
        val matchId = createMatch()
        appendFailedQuest(matchId, 1, listOf(1, 2, 3))
        appendFailedQuest(matchId, 2, listOf(1, 2, 3, 4))
        appendFailedQuest(matchId, 3, listOf(4, 6, 7, 8))
        makeStaleProjection(matchId, MatchStatus.IN_PROGRESS)

        repository.recomputeAllProjections()
        val afterFirst = storedStatus(matchId)
        val updatedAtAfterFirst = storedUpdatedAt(matchId)

        repository.recomputeAllProjections()

        assertEquals("重复刷新结论不变", afterFirst, storedStatus(matchId))
        assertEquals("重复刷新时间戳仍不变", updatedAtAfterFirst, storedUpdatedAt(matchId))
        assertEquals("结论应等于重放", replayDerivedStatus(matchId), storedStatus(matchId))
    }

    @Test
    fun refreshCoversEveryMatch() = runBlocking {
        val first = createMatch("对局 A")
        val second = createMatch("对局 B")
        appendFailedQuest(first, 1, listOf(1, 2, 3), tag = "a")
        appendFailedQuest(first, 2, listOf(1, 2, 3, 4), tag = "a")
        appendFailedQuest(first, 3, listOf(4, 6, 7, 8), tag = "a")
        appendFailedQuest(second, 1, listOf(1, 2, 3), tag = "b")

        makeStaleProjection(first, MatchStatus.IN_PROGRESS)
        makeStaleProjection(second, MatchStatus.FINISHED_EVIL)

        val result = repository.recomputeAllProjections()

        assertEquals("应处理 2 局", 2, result.processed)
        assertTrue("两局都健康，isComplete 必须为 true", result.isComplete)
        assertEquals(
            "A 局应被刷成三次失败",
            MatchStatus.FINISHED_EVIL,
            storedStatus(first)
        )
        assertEquals(
            "B 局只有一次失败，应被刷回进行中",
            MatchStatus.IN_PROGRESS,
            storedStatus(second)
        )
        listOf(first, second).forEach { id ->
            assertEquals(
                "刷完后列必须等于重放结论（$id）",
                replayDerivedStatus(id),
                storedStatus(id)
            )
            assertEquals(
                "两局的时间戳都不得被改",
                oldTimestamp,
                storedUpdatedAt(id)
            )
        }
    }

    @Test
    fun refreshOnEmptyDatabaseReturnsZero() = runBlocking {
        val result = repository.recomputeAllProjections()
        assertEquals("空库应返回 0", 0, result.processed)
        assertTrue("空库也算全部成功", result.isComplete)
    }

    @Test
    fun refreshDoesNotInventStatusForMatchWithoutEvents() = runBlocking {
        val matchId = createMatch()
        makeStaleProjection(matchId, MatchStatus.FINISHED_EVIL)

        repository.recomputeAllProjections()

        assertEquals(
            "没有任何事件的对局，重放结论是 DRAFT —— 刷新必须照实写回，不得保留手工脏值",
            MatchStatus.DRAFT,
            storedStatus(matchId)
        )
        assertTrue("且不得改动时间戳", storedUpdatedAt(matchId) == oldTimestamp)
    }

    // ————————————————————————————————————————————————————————
    // 失败必须被上报（修正「失败当成成功」的缺陷）
    // ————————————————————————————————————————————————————————

    /**
     * 关键回归：**单局刷新失败时，必须返回 `isComplete = false`**。
     *
     * 上一版实现是「逐局 runCatching → 全部跑完就写标记」，等于把失败当成成功：
     * 某局重放抛异常时，调用方照样写版本标记 → 该局**永久**停在旧结论，且没有任何信号。
     *
     * 现在调用方（`AppContainer`）以 `isComplete` 决定是否写标记，所以这里断言的就是那条契约。
     */
    @Test
    fun failedMatchIsReportedAndPreventsMarker() = runBlocking {
        val healthy = createMatch("健康对局")
        val broken = createMatch("损坏对局")
        appendFailedQuest(healthy, 1, listOf(1, 2, 3), tag = "h")
        appendFailedQuest(healthy, 2, listOf(1, 2, 3, 4), tag = "h")
        appendFailedQuest(healthy, 3, listOf(4, 6, 7, 8), tag = "h")

        // 让 broken 局的事件表里出现一条「解不开」的行：未知 eventType → codec.decode 抛异常
        rawDb().execSQL(
            "INSERT INTO match_events (id, matchId, occurredOrder, logicalPosition, revisionSeq, " +
                "phaseKey, eventType, payloadVersion, payloadJson, visibility, recordedAt, " +
                "supersedesId, retracted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                "broken-e1", broken.raw, 1, 1, 0,
                "QUEST_1", "NOT_A_REAL_TYPE", 1, "{}",
                "PUBLIC", 1L, null, 0
            )
        )

        // 两局都伪造成过期结论
        makeStaleProjection(healthy, MatchStatus.IN_PROGRESS)
        makeStaleProjection(broken, MatchStatus.IN_PROGRESS)

        val result = repository.recomputeAllProjections()

        assertEquals("健康局应被刷新", 1, result.processed)
        assertEquals("失败局应被上报 1 条", 1, result.failed.size)
        assertEquals("上报的必须正是那一局", broken.raw, result.failed.first().raw)
        assertTrue(
            "**有失败时 isComplete 必须为 false** —— 调用方据此不写版本标记、下次重试",
            !result.isComplete
        )
        assertEquals(
            "健康局的结论仍必须被刷新（不能因别局失败而整批放弃）",
            replayDerivedStatus(healthy),
            storedStatus(healthy)
        )
        assertEquals(
            "失败局保持原样（不被改坏）",
            MatchStatus.IN_PROGRESS,
            storedStatus(broken)
        )
        assertEquals(
            "失败局的时间戳也不得被动",
            oldTimestamp,
            storedUpdatedAt(broken)
        )
    }
}
