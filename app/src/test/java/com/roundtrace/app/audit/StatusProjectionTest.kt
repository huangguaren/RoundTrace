package com.roundtrace.app.audit

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.core.data.MatchBundle
import com.roundtrace.app.core.data.MatchRepository
import com.roundtrace.app.core.data.RoomMatchRepository
import com.roundtrace.app.core.database.EventCodec
import com.roundtrace.app.core.database.RoundTraceDatabase
import com.roundtrace.app.core.export.DefaultImportExportService
import com.roundtrace.app.core.export.ExportOptions
import com.roundtrace.app.core.export.ImportOutcome
import com.roundtrace.app.core.export.MatchJsonCodec
import com.roundtrace.app.core.knowledge.AvalonKnowledgeProvider
import com.roundtrace.app.core.model.AssassinationRecorded
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.RevealState
import com.roundtrace.app.core.model.RoleAssignment
import com.roundtrace.app.core.model.RoleSource
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.rules.avalon.AvalonRules
import com.roundtrace.app.core.util.SequentialIdGenerator
import com.roundtrace.app.util.MatchBuilder
import com.roundtrace.app.util.RoleFixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QA-P1 · RT-001 / RT-004 对抗性回归：`matches.status` 与 `matches.revealedAt`
 * 必须始终与「事件重放派生结果」一致。
 *
 * 覆盖：
 * - **E2E-01**：揭晓后追加刺杀，`revealedAt` 不得被状态投影覆盖，上帝视角仍开放；
 * - **E2E-05**：撤销掉决定胜负的事件后，`status` 必须回落到派生状态；
 * - **E2E-06**：导入一场 `FINISHED_GOOD` 对局，落库状态必须是重放结果，不能停在 DRAFT；
 * - **B.7 边界**：把事件全部撤销后 `status` 是什么（如实记录，不做猜测性断言）。
 *
 * 时钟是 [MutableClock]：`fixedClock` 永远返回同一时刻，无法区分
 * 「`revealedAt` 被保住了」与「`revealedAt` 被重写成了同一个值」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatusProjectionTest {

    private lateinit var database: RoundTraceDatabase
    private lateinit var repository: RoomMatchRepository

    private val clock = MutableClock(1_700_000_000_000L)
    private val registry = DefaultGameRulesRegistry()
    private val codec = EventCodec()
    private val provider = AvalonKnowledgeProvider(registry.avalonReducer)

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
            codec = codec,
            idGenerator = SequentialIdGenerator("id"),
            clock = clock
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ————————————————————————————————————————————————————————
    // E2E-01
    // ————————————————————————————————————————————————————————

    @Test
    fun e2e01_revealedAt_survives_later_event_appends() = runBlocking {
        val matchId = createMatch()
        saveFullRoles(matchId)

        appendQuestCycle(matchId, questNo = 1, failCount = 0)
        appendQuestCycle(matchId, questNo = 2, failCount = 0)
        appendQuestCycle(matchId, questNo = 3, failCount = 0)

        assertEquals(
            "三次任务成功且计划含刺客 → 必须进入刺杀待决（不能直接判好人胜）",
            MatchStatus.AWAITING_ASSASSINATION,
            statusOf(matchId)
        )

        clock.advanceBy(60_000)
        repository.revealAllRoles(matchId)
        val revealedAt = row(matchId).revealedAt
        assertNotNull("揭晓后 revealedAt 必须落库", revealedAt)
        assertEquals("revealedAt 必须是揭晓那一刻", clock.nowMs, revealedAt)

        // 关键：把「现在」推后一小时，任何「用 now 重写 revealedAt」的实现都会暴露
        clock.advanceBy(3_600_000)
        val assassinated = repository.appendEvent(
            matchId,
            AssassinationRecorded(
                eventId = EventId("assassin-hit-merlin"),
                occurredOrder = 0,
                recordedAt = 0L,
                assassinSeat = RoleFixtures.ASSASSIN_SEAT,
                targetSeat = RoleFixtures.MERLIN_SEAT,
                hitMerlin = null
            )
        )
        assertNull("刺杀事件应写入成功", assassinated.error)

        val after = row(matchId)
        assertEquals(
            "RT-001：revealedAt 不得被后续事件的状态投影覆盖",
            revealedAt,
            after.revealedAt
        )
        assertEquals(
            "刺中梅林 → 坏人胜",
            MatchStatus.FINISHED_EVIL,
            MatchStatus.valueOf(after.status)
        )

        val bundle = repository.getBundle(matchId)!!
        assertTrue(
            "揭晓 + 终局后上帝视角必须仍然开放（RT-001 的可见症状）",
            provider.canOpenGodView(
                bundle.toReconstructedMatch(),
                MatchStatus.valueOf(after.status)
            )
        )
        // 上帝视角确实能拿到全量角色，证明它不是「假开放」
        val snapshot = provider.snapshot(
            match = bundle.toReconstructedMatch(),
            observer = null,
            at = com.roundtrace.app.core.model.GamePosition(
                bundle.events.maxOf { it.occurredOrder }
            ),
            mode = com.roundtrace.app.core.model.Perspective.GOD_AFTER_REVEAL
        )
        assertEquals(
            "上帝视角必须看到 4 号是莫德雷德",
            com.roundtrace.app.core.model.RoleId.MORDRED,
            snapshot.seatKnowledge.getValue(RoleFixtures.MORDRED_SEAT).visibleRole
        )
    }

    // ————————————————————————————————————————————————————————
    // E2E-05
    // ————————————————————————————————————————————————————————

    @Test
    fun e2e05_retracting_decisive_event_rolls_status_back_to_derived() = runBlocking {
        val matchId = createMatch()
        saveFullRoles(matchId)

        appendQuestCycle(matchId, questNo = 1, failCount = 1)
        appendQuestCycle(matchId, questNo = 2, failCount = 1)
        val decisive = appendQuestCycle(matchId, questNo = 3, failCount = 1)

        assertEquals(
            "三次任务失败 → 坏人直接胜",
            MatchStatus.FINISHED_EVIL,
            statusOf(matchId)
        )

        val retracted = repository.retractEvent(matchId, decisive)
        assertNull("撤销应成功", retracted.error)

        val after = row(matchId)
        val derived = derivedStatus(
            repository.getBundle(matchId)!!.toReconstructedMatch(),
            registry.avalonReducer
        )
        assertEquals(
            "撤销决定胜负的事件后，matches.status 必须等于重放派生状态",
            derived.name,
            after.status
        )
        assertEquals(
            "两次失败时不构成终局，必须回到进行中",
            MatchStatus.IN_PROGRESS,
            MatchStatus.valueOf(after.status)
        )
    }

    // ————————————————————————————————————————————————————————
    // E2E-06
    // ————————————————————————————————————————————————————————

    @Test
    fun e2e06_import_recomputes_status_from_events() = runBlocking {
        // 先放一场「已存在」的对局，用来证明导入是新建而不是覆盖
        val existing = createMatch()
        val existingBefore = row(existing)
        assertEquals("导入前应只有 1 场对局", 1, countMatches())

        val revealedAt = 1_700_000_500_000L
        val built = buildFinishedGoodMatch(revealedAt)
        val json = MatchJsonCodec().encode(
            bundle = bundleOf(built, MatchStatus.FINISHED_GOOD, updatedAtEpochMs = revealedAt),
            options = ExportOptions(includeSecrets = true, includePrivateNotes = true)
        )

        val service = DefaultImportExportService(
            repository = repository,
            rules = AvalonRules(),
            codec = MatchJsonCodec()
        )
        val outcome = service.importFromJson(json)
        assertTrue(
            "合法 JSON 必须导入成功，实际=$outcome",
            outcome is ImportOutcome.Imported
        )
        val newId = (outcome as ImportOutcome.Imported).matchId

        val imported = row(newId)
        assertEquals(
            "RT-004：导入后 status 必须由重放派生（FINISHED_GOOD），不能停在 DRAFT",
            MatchStatus.FINISHED_GOOD.name,
            imported.status
        )
        assertEquals(
            "导入必须保留 revealedAt",
            revealedAt,
            imported.revealedAt
        )

        assertEquals("导入必须是新建对局，不得覆盖已有数据", 2, countMatches())
        assertNotEquals("导入应产生新的 matchId", existing.raw, newId.raw)
        assertEquals(
            "已有对局的 status 不得被导入影响",
            existingBefore.status,
            row(existing).status
        )
        assertEquals(
            "已有对局的 revealedAt 不得被导入影响",
            existingBefore.revealedAt,
            row(existing).revealedAt
        )
    }

    // ————————————————————————————————————————————————————————
    // B.7 边界：全部事件被撤销
    // ————————————————————————————————————————————————————————

    @Test
    fun boundary_retract_every_event_keeps_projection_consistent() = runBlocking {
        val matchId = createMatch()
        saveFullRoles(matchId)
        appendQuestCycle(matchId, questNo = 1, failCount = 0)

        val allActive = database.matchEventDao().listActive(matchId.raw)
        allActive.forEach { entity ->
            val result = repository.retractEvent(matchId, EventId(entity.id))
            assertNull("逐条撤销都应成功：${entity.id}", result.error)
        }

        val after = row(matchId)
        val bundle = repository.getBundle(matchId)!!
        val derived = derivedStatus(bundle.toReconstructedMatch(), registry.avalonReducer)

        // 只断言「投影 == 派生」，不断言我们猜的那个值；实际值在报告里如实写出
        println("[AUDIT] 撤销全部事件后：matches.status=${after.status}，replay 派生=${derived.name}")
        assertEquals(
            "投影必须与重放一致（实测 status=${after.status}，derived=${derived.name}）",
            derived.name,
            after.status
        )
        assertEquals("事件全撤销后读路径应为空", 0, bundle.events.size)
    }

    // ————————————————————————————————————————————————————————
    // 工具
    // ————————————————————————————————————————————————————————

    private suspend fun createMatch(playerCount: Int = 8): MatchId =
        repository.createMatch(
            MatchCreationDraft(
                title = "审计对局（状态投影）",
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

    /** 写入 8 人真实角色表（与实际玩法一致，避免「全员 UNKNOWN」导致刺杀无法裁决）。 */
    private suspend fun saveFullRoles(matchId: MatchId) {
        repository.saveRoleAssignments(
            matchId,
            RoleFixtures.EIGHT_PLAYER_MERLIN_MORDRED.map { (seat, role) ->
                RoleAssignment(
                    seat = seat,
                    roleId = role,
                    source = RoleSource.PRESET_MANUAL,
                    revealState = RevealState.HIDDEN
                )
            }
        )
    }

    /**
     * 写入一次完整任务闭环，返回 `MissionResolved` 的事件 id。
     *
     * 故意**不**显式写 `QuestStarted`：走仓储的「首次提案自动补建」路径（§9.3-18），
     * 顺带把这条路径也纳入回归。
     */
    private suspend fun appendQuestCycle(
        matchId: MatchId,
        questNo: Int,
        failCount: Int
    ): EventId {
        val playerCount = row(matchId).playerCount
        val size = officialTeamSize(playerCount, questNo)
        val team = (1..size).toList()
        val leader = ((questNo - 1) % playerCount) + 1
        val tag = "q$questNo"

        appendOk(
            matchId,
            AuditEvents.teamProposed("$tag-proposal", questNo, 1, leader, team)
        )
        appendOk(
            matchId,
            AuditEvents.teamVote("$tag-vote", questNo, 1, playerCount, 0, playerCount)
        )
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
            observedOutcome = if (failCount >= failThreshold(playerCount, questNo)) {
                MissionOutcome.FAILED
            } else {
                MissionOutcome.SUCCESS
            }
        )
        appendOk(matchId, mission)
        return mission.eventId
    }

    private suspend fun appendOk(matchId: MatchId, event: com.roundtrace.app.core.model.MatchEvent) {
        val result = repository.appendEvent(matchId, event)
        assertNull(
            "事件写入不应失败：${event.shortName()} → ${result.error}",
            result.error
        )
    }

    private fun failThreshold(playerCount: Int, questNo: Int): Int =
        if (playerCount >= 7 && questNo == 4) 2 else 1

    private suspend fun statusOf(matchId: MatchId): MatchStatus =
        MatchStatus.valueOf(row(matchId).status)

    private suspend fun row(matchId: MatchId) =
        database.matchDao().findById(matchId.raw) ?: error("对局不存在：$matchId")

    private suspend fun countMatches(): Int =
        database.matchDao()
            .observeSummaries(MatchStatus.entries.map { it.name })
            .first()
            .size

    private fun buildFinishedGoodMatch(revealedAt: Long): com.roundtrace.app.core.model.ReconstructedMatch {
        val builder: MatchBuilder = MatchBuilder.eightPlayerMerlinMordred()
        (1..3).forEach { questNo ->
            val size = officialTeamSize(8, questNo)
            builder.questCycle(
                questNo = questNo,
                leaderSeat = ((questNo - 1) % 8) + 1,
                teamSeats = (1..size).toList(),
                failCount = 0,
                observedOutcome = MissionOutcome.SUCCESS
            )
        }
        // 1 号是梅林；刺客 5 号刺错 2 号 → 好人胜
        builder.assassinationRecorded(
            assassinSeat = RoleFixtures.ASSASSIN_SEAT,
            targetSeat = RoleFixtures.LOYAL_SERVANT_2_SEAT,
            hitMerlin = false
        )
        return builder.build(revealedAtEpochMs = revealedAt)
    }
}
