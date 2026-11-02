package com.roundtrace.app.audit

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.core.data.RoomMatchRepository
import com.roundtrace.app.core.database.EventCodec
import com.roundtrace.app.core.database.RoundTraceDatabase
import com.roundtrace.app.core.export.DefaultImportExportService
import com.roundtrace.app.core.export.ExportOptions
import com.roundtrace.app.core.export.ExportResult
import com.roundtrace.app.core.export.ImportOutcome
import com.roundtrace.app.core.export.MatchJsonCodec
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.RevealState
import com.roundtrace.app.core.model.RoleAssignment
import com.roundtrace.app.core.model.RoleSource
import com.roundtrace.app.core.replay.ReplayEngine
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.rules.avalon.AvalonRules
import com.roundtrace.app.core.util.SequentialIdGenerator
import com.roundtrace.app.util.RoleFixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QA 阶段 D · V10：**修订过**的对局导出再导入。
 *
 * 关键点（与阶段 1 的 V-导入测试的区别）：这里导出的是「一个位置有过两个版本」的对局。
 * 导出只包含 active 版本，因此导入后的相对顺序必须仍然连续，
 * 且不能因为丢掉历史版本而让位置出现空洞或错位。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportImportRevisionAuditTest {

    private lateinit var database: RoundTraceDatabase
    private lateinit var repository: RoomMatchRepository

    private val clock = MutableClock(1_700_000_000_000L)
    private val registry = DefaultGameRulesRegistry()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RoundTraceDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()

        repository = RoomMatchRepository(
            database = database,
            gameRulesRegistry = registry,
            reducer = registry.avalonReducer,
            codec = EventCodec(),
            idGenerator = SequentialIdGenerator("id"),
            clock = clock
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun v10_exported_revised_match_round_trips_with_same_order_and_outcome() = runBlocking {
        val matchId = createMatch()
        saveFullRoles(matchId)

        val mission3 = appendQuestCycle(matchId, questNo = 1, failCount = 1)
        appendQuestCycle(matchId, questNo = 2, failCount = 0)

        // 修订第 1 任务：失败 → 成功
        val revised = repository.reviseEvent(
            matchId = matchId,
            targetEventId = mission3,
            replacement = AuditEvents.missionResolved(
                id = "q1-mission-v2",
                questNo = 1,
                teamSeats = (1..3).toList(),
                teamSizeExpected = 3,
                failCount = 0,
                observedOutcome = MissionOutcome.SUCCESS
            )
        )
        assertNull("修订应成功：${revised.error}", revised.error)

        val sourceBundle = repository.getBundle(matchId)!!
        val sourceStatus = row(matchId).status
        val sourceShape = shapeOf(sourceBundle.events)

        val service = DefaultImportExportService(repository = repository, rules = AvalonRules())
        val exported = service.exportJson(
            matchId,
            ExportOptions(includeSecrets = true, includePrivateNotes = true)
        )
        assertTrue("导出应成功", exported is ExportResult.Success)
        val json = (exported as ExportResult.Success).text

        assertTrue("schemaVersion 必须仍为 1", json.contains("\"schemaVersion\": 1"))
        assertEquals(
            "导出的事件条数必须等于 active 事件数（历史版本不导出）",
            sourceBundle.events.size,
            (exported as ExportResult.Success).eventCount
        )
        assertTrue(
            "导出不得包含 retracted=true 的历史版本行",
            !json.contains("\"retracted\": true")
        )
        assertTrue(
            "导出的必须是修订后的新版本",
            json.contains("q1-mission-v2")
        )

        val outcome = service.importFromJson(json)
        assertTrue("导入应成功，实际=$outcome", outcome is ImportOutcome.Imported)
        val importedId = (outcome as ImportOutcome.Imported).matchId

        val importedBundle = repository.getBundle(importedId)!!
        assertEquals(
            "导入后的事件内容与相对顺序必须与源一致",
            sourceShape,
            shapeOf(importedBundle.events)
        )
        assertEquals(
            "导入后的 occurredOrder 必须是 1..N 连续（无空洞）",
            (1..importedBundle.events.size).toList(),
            importedBundle.events.map { it.occurredOrder }
        )
        assertEquals(
            "胜负必须一致（导入后 status 由重放派生）",
            sourceStatus,
            row(importedId).status
        )
        assertEquals(
            "落库 status 必须等于重放派生",
            ReplayEngine(registry.avalonReducer)
                .replay(importedBundle.toReconstructedMatch()).state.status.name,
            row(importedId).status
        )

        val importedMission1 = importedBundle.events
            .filterIsInstance<MissionResolved>()
            .first { it.questNo == 1 }
        assertEquals("第 1 任务必须是修订后的成功", MissionOutcome.SUCCESS, importedMission1.observedOutcome)
        assertEquals("失败牌数必须是修订后的 0", 0, importedMission1.failCount)

        // 不覆盖原对局
        assertEquals(
            "导入必须新建对局",
            2,
            database.matchDao().observeSummaries(MatchStatus.entries.map { it.name }).first().size
        )
        assertEquals("原对局状态不得被导入影响", sourceStatus, row(matchId).status)
    }

    /** 导出/导入的 schemaVersion 不接受「悄悄升版本但没写迁移」的情况。 */
    @Test
    fun v10b_unknown_schema_version_is_rejected_not_silently_accepted() = runBlocking {
        val matchId = createMatch()
        saveFullRoles(matchId)
        appendQuestCycle(matchId, questNo = 1, failCount = 0)

        val service = DefaultImportExportService(repository = repository, rules = AvalonRules())
        val json = ((service.exportJson(
            matchId,
            ExportOptions(includeSecrets = true, includePrivateNotes = true)
        ) as ExportResult.Success).text)

        val tampered = json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        val outcome = service.importFromJson(tampered)
        assertTrue(
            "未知 schemaVersion 必须被拒绝，实际=$outcome",
            outcome is ImportOutcome.Rejected
        )
    }

    // ————————————————————————————————————————————————————————

    /** 事件的「形状」：类型 + 位置 + 关键字段（不含 id / recordedAt）。 */
    private fun shapeOf(events: List<com.roundtrace.app.core.model.MatchEvent>): List<String> =
        events.map { event ->
            val extra = when (event) {
                is MissionResolved -> "fail=${event.failCount},outcome=${event.observedOutcome?.name}"
                is com.roundtrace.app.core.model.TeamVoteRecorded -> "approve=${event.approveCount},reject=${event.rejectCount}"
                is com.roundtrace.app.core.model.TeamProposed -> "team=${event.teamSeats.sorted()}"
                is com.roundtrace.app.core.model.TeamProposalResolved -> "decision=${event.decision.name}"
                is com.roundtrace.app.core.model.QuestStarted -> "leader=${event.leaderSeat}"
                else -> ""
            }
            "${event.type}@${event.occurredOrder}($extra)"
        }

    private suspend fun createMatch(playerCount: Int = 8): MatchId =
        repository.createMatch(
            MatchCreationDraft(
                title = "审计对局（导出修订）",
                playerCount = playerCount,
                roleSlots = AvalonPreset.standardPreset(playerCount),
                mySeat = RoleFixtures.MERLIN_SEAT,
                initialLeaderSeat = 1,
                participants = MatchCreationDraft.defaultParticipants(playerCount),
                presetId = AvalonPreset.presetId(playerCount),
                rulesetId = AvalonPreset.RULESET_ID,
                rulesetVersion = AvalonPreset.RULESET_VERSION
            )
        )

    private suspend fun saveFullRoles(matchId: MatchId) {
        repository.saveRoleAssignments(
            matchId,
            RoleFixtures.EIGHT_PLAYER_MERLIN_MORDRED.map { (seat, role) ->
                RoleAssignment(seat, role, RoleSource.PRESET_MANUAL, RevealState.HIDDEN)
            }
        )
    }

    private suspend fun appendQuestCycle(matchId: MatchId, questNo: Int, failCount: Int): com.roundtrace.app.core.model.EventId {
        val playerCount = row(matchId).playerCount
        val size = officialTeamSize(playerCount, questNo)
        val team = (1..size).toList()
        val leader = ((questNo - 1) % playerCount) + 1
        val tag = "q$questNo"

        appendOk(matchId, AuditEvents.teamProposed("$tag-proposal", questNo, 1, leader, team))
        appendOk(matchId, AuditEvents.teamVote("$tag-vote", questNo, 1, playerCount, 0, playerCount))
        appendOk(
            matchId,
            AuditEvents.proposalResolved(
                "$tag-decision", questNo, 1, ProposalDecision.APPROVED, playerCount, 0, playerCount
            )
        )
        val mission = AuditEvents.missionResolved(
            id = "$tag-mission",
            questNo = questNo,
            teamSeats = team,
            teamSizeExpected = size,
            failCount = failCount,
            observedOutcome = if (failCount >= 1) MissionOutcome.FAILED else MissionOutcome.SUCCESS
        )
        appendOk(matchId, mission)
        return mission.eventId
    }

    private suspend fun appendOk(matchId: MatchId, event: com.roundtrace.app.core.model.MatchEvent) {
        val r = repository.appendEvent(matchId, event)
        assertNull("写入 ${event.shortName()} 不应失败：${r.error}", r.error)
    }

    private suspend fun row(matchId: MatchId) =
        database.matchDao().findById(matchId.raw) ?: error("对局不存在")
}
