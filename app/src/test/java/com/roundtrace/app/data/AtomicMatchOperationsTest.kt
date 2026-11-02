package com.roundtrace.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.audit.MutableClock
import com.roundtrace.app.core.data.RepositoryError
import com.roundtrace.app.core.data.RoomMatchRepository
import com.roundtrace.app.core.database.EventCodec
import com.roundtrace.app.core.database.RoundTraceDatabase
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventFactory
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.ReconstructedMatch
import com.roundtrace.app.core.model.RevealState
import com.roundtrace.app.core.model.RoleAssignment
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.RoleSource
import com.roundtrace.app.core.replay.ReplayEngine
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.GameReducer
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.util.SequentialIdGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** RT-008/014：真实 Room 事务及故障回滚，不用内存 fake 代替原子性证据。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AtomicMatchOperationsTest {
    private lateinit var database: RoundTraceDatabase
    private lateinit var repository: RoomMatchRepository
    private val registry = DefaultGameRulesRegistry()
    private val clock = MutableClock(1_700_000_000_000L)
    private val ids = SequentialIdGenerator("atomic")
    private val events = EventFactory(ids, clock)

    private fun repository(reducer: GameReducer = registry.avalonReducer) = RoomMatchRepository(
        database, registry, reducer, EventCodec(), ids, clock
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), RoundTraceDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = repository()
    }

    @After
    fun tearDown() = database.close()

    private suspend fun createMatch(): MatchId = repository.createMatch(
        MatchCreationDraft(
            title = "原子写入测试",
            playerCount = 8,
            mySeat = 1,
            roleSlots = AvalonPreset.standardPreset(8),
            participants = MatchCreationDraft.defaultParticipants(8),
            initialLeaderSeat = 1,
            presetId = AvalonPreset.presetId(8)
        )
    )

    private fun role(seat: Int = 1) = RoleAssignment(
        seat, RoleId.MERLIN, RoleSource.REVEALED_AFTER_END, RevealState.HIDDEN
    )

    @Test
    fun revealSavesRolesTimestampAndDerivedStatusTogether() = runBlocking {
        val match = createMatch()
        repository.appendEvent(match, events.questStarted(1, 1))
        assertTrue(repository.revealMatch(match, listOf(role())).isSuccess)
        val bundle = repository.getBundle(match)!!
        assertEquals(RoleId.MERLIN, bundle.roleAssignments.getValue(1).roleId)
        assertTrue(bundle.roleAssignments.values.all { it.revealState == RevealState.REVEALED })
        assertEquals(clock.nowMs, bundle.revealedAt)
        assertEquals(
            ReplayEngine(registry.avalonReducer).replay(bundle.toReconstructedMatch()).state.status,
            bundle.summary.status
        )
    }

    @Test
    fun failedFinalProjectionRollsBackRolesAndRevealedTimestamp() = runBlocking {
        val match = createMatch()
        val beforeRoles = database.roleAssignmentDao().listByMatch(match.raw)
        val beforeMatch = database.matchDao().findById(match.raw)
        // 第一次写揭晓时间允许成功，随后投影刷新失败，验证最末一步失败也整体回滚。
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER abort_reveal_projection BEFORE UPDATE OF status ON matches " +
                "WHEN OLD.revealedAt IS NOT NULL BEGIN SELECT RAISE(ABORT, 'projection failure'); END"
        )
        val result = repository.revealMatch(match, listOf(role()))
        assertEquals(RepositoryError.WRITE_FAILED, result.error)
        assertEquals(beforeRoles, database.roleAssignmentDao().listByMatch(match.raw))
        assertEquals(beforeMatch, database.matchDao().findById(match.raw))
    }

    @Test
    fun repeatedAndConcurrentRevealsKeepTheFirstRevealedAt() = runBlocking {
        val match = createMatch()
        assertTrue(repository.revealMatch(match, listOf(role())).isSuccess)
        val first = repository.getBundle(match)!!.revealedAt
        clock.advanceBy(10_000)
        coroutineScope {
            List(2) {
                async(Dispatchers.IO) { repository.revealMatch(match, listOf(role())) }
            }.awaitAll().forEach { assertTrue(it.isSuccess) }
        }
        assertEquals(first, repository.getBundle(match)!!.revealedAt)
        assertEquals(8, database.roleAssignmentDao().listByMatch(match.raw).size)
    }

    @Test
    fun invalidOrMissingRevealDoesNotChangeAnotherMatch() = runBlocking {
        val match = createMatch()
        val before = repository.getBundle(match)
        assertFalse(repository.revealMatch(match, listOf(role(9))).isSuccess)
        assertFalse(repository.revealMatch(match, listOf(role(), role())).isSuccess)
        assertEquals(RepositoryError.NOT_FOUND, repository.revealMatch(MatchId("missing"), listOf(role())).error)
        assertEquals(before, repository.getBundle(match))
    }

    @Test
    fun bulkRollbackUsesLogicalPositionsAndIsolatesMatches() = runBlocking {
        val match = createMatch()
        val other = createMatch()
        val first = events.questStarted(1, 1)
        repository.appendEvent(match, first)
        val second = events.questStarted(2, 2)
        repository.appendEvent(match, second)
        val revised = events.questStarted(1, 3)
        assertTrue(repository.reviseEvent(match, first.eventId, revised).isSuccess)
        repository.appendEvent(other, events.questStarted(1, 1))
        repository.revealAllRoles(match)
        val otherBefore = repository.getBundle(other)
        val revealedAt = repository.getBundle(match)!!.revealedAt
        assertTrue(repository.retractAfterOrder(match, 1).isSuccess)
        val after = repository.getBundle(match)!!
        assertEquals(listOf(revised.eventId), after.events.map { it.eventId })
        assertEquals(revealedAt, after.revealedAt)
        assertEquals(otherBefore, repository.getBundle(other))
        assertEquals(3, database.matchEventDao().listAll(match.raw).size)
        assertTrue(database.matchEventDao().findById(second.eventId.raw)!!.retracted)
        assertEquals(ReplayEngine(registry.avalonReducer).replay(after.toReconstructedMatch()).state.status, after.summary.status)
    }

    @Test
    fun failedBulkProjectionRollsBackEveryRetractionAndTimestamp() = runBlocking {
        val match = createMatch()
        (1..3).forEach { repository.appendEvent(match, events.questStarted(it, it)) }
        val beforeEvents = database.matchEventDao().listAll(match.raw)
        val beforeMatch = database.matchDao().findById(match.raw)
        clock.advanceBy(10_000)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER abort_rollback_projection BEFORE UPDATE OF status ON matches " +
                "BEGIN SELECT RAISE(ABORT, 'projection failure'); END"
        )
        assertEquals(RepositoryError.WRITE_FAILED, repository.retractAfterOrder(match, 1).error)
        assertEquals(beforeEvents, database.matchEventDao().listAll(match.raw))
        assertEquals(beforeMatch, database.matchDao().findById(match.raw))
    }

    @Test
    fun repeatedRollbackIsIdempotentAndNegativeBoundaryDoesNotWipeHistory() = runBlocking {
        val match = createMatch()
        (1..2).forEach { repository.appendEvent(match, events.questStarted(it, it)) }
        val before = repository.getBundle(match)
        assertFalse(repository.retractAfterOrder(match, -1).isSuccess)
        assertEquals(before, repository.getBundle(match))
        assertTrue(repository.retractAfterOrder(match, 1).isSuccess)
        val after = database.matchDao().findById(match.raw)
        clock.advanceBy(10_000)
        assertTrue(repository.retractAfterOrder(match, 1).isSuccess)
        assertEquals(after, database.matchDao().findById(match.raw))
        assertEquals(RepositoryError.NOT_FOUND, repository.retractAfterOrder(MatchId("missing"), 0).error)
    }

    @Test
    fun concurrentAppendAndRollbackHaveOnlySerialOutcomes() = runBlocking {
        val match = createMatch()
        val first = events.questStarted(1, 1)
        repository.appendEvent(match, first)
        (2..3).forEach { repository.appendEvent(match, events.questStarted(it, it)) }
        val appended = events.questStarted(4, 4)
        coroutineScope {
            listOf(
                async(Dispatchers.IO) { repository.retractAfterOrder(match, 1) },
                async(Dispatchers.IO) { repository.appendEvent(match, appended) }
            ).awaitAll().forEach { assertTrue(it.isSuccess) }
        }
        val active = repository.getBundle(match)!!.events.map { it.eventId }
        assertTrue(active == listOf(first.eventId) || active == listOf(first.eventId, appended.eventId))
    }

    @Test
    fun cancellationDuringRevealPropagatesAndRollsBack() = runBlocking {
        val match = createMatch()
        val before = database.roleAssignmentDao().listByMatch(match.raw)
        val reducer = object : GameReducer by registry.avalonReducer {
            override fun initialState(match: ReconstructedMatch) = throw CancellationException("cancel reveal")
        }
        var cancelled = false
        try {
            repository(reducer).revealMatch(match, listOf(role()))
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertNull(database.matchDao().findById(match.raw)!!.revealedAt)
        assertEquals(before, database.roleAssignmentDao().listByMatch(match.raw))
    }
}
