package com.roundtrace.app.audit

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roundtrace.app.core.data.RepositoryError
import com.roundtrace.app.core.data.RoomMatchRepository
import com.roundtrace.app.core.database.EventCodec
import com.roundtrace.app.core.database.RoundTraceDatabase
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.MatchCreationDraft
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.RevealState
import com.roundtrace.app.core.model.RoleAssignment
import com.roundtrace.app.core.model.RoleSource
import com.roundtrace.app.core.replay.ReplayEngine
import com.roundtrace.app.core.replay.TimelineBuilder
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.avalon.AvalonPreset
import com.roundtrace.app.core.util.SequentialIdGenerator
import com.roundtrace.app.util.RoleFixtures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QA 阶段 D · V04 / V05 / V06 / V07：修订（编辑历史）的一致性与原子性。
 *
 * 全部走**真实 Room + 真实事务**，不走 FakeRepository —— V05/V06 想验的正是
 * 「事务真的回滚了」「并发真的只有一方赢」，内存 fake 给不出这个保证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RevisionIntegrityAuditTest {

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

    // ————————————————————————————————————————————————————————
    // V04：修改中间历史记录
    // ————————————————————————————————————————————————————————

    @Test
    fun v04_revising_third_of_five_keeps_position_and_order() = runBlocking {
        val matchId = createMatch()
        val ids = (1..5).map { i ->
            val r = repository.appendEvent(matchId, claim("c$i", seat = i, text = "原始 $i"))
            assertNull("追加 c$i 不应失败：${r.error}", r.error)
            r.eventId!!
        }

        val revised = repository.reviseEvent(
            matchId = matchId,
            targetEventId = ids[2],
            replacement = claim("c3-v2", seat = 3, text = "修订后 3")
        )
        assertNull("修订应成功：${revised.error}", revised.error)

        val bundle = repository.getBundle(matchId)!!
        assertEquals("active 事件数必须保持 5（修订不增加时间线长度）", 5, bundle.events.size)
        assertEquals(
            "领域 occurredOrder 必须仍是 1..5（等于逻辑位置）",
            listOf(1, 2, 3, 4, 5),
            bundle.events.map { it.occurredOrder }
        )
        assertEquals(
            "第 3 条必须是新版本",
            "c3-v2",
            (bundle.events[2] as PlayerClaimed).eventId.raw
        )
        assertEquals(
            "第 1、2、4、5 条内容不得被改动",
            listOf("c1", "c2", "c4", "c5"),
            listOf(0, 1, 3, 4).map { (bundle.events[it] as PlayerClaimed).eventId.raw }
        )

        // 时间线（复盘页）顺序同样必须是 1..5，且第 3 条是修订后的文案
        val timeline = TimelineBuilder.build(bundle.events)
        assertEquals("时间线条目数不变", 5, timeline.size)
        assertEquals(
            "时间线顺序必须仍是 1..5",
            listOf(1, 2, 3, 4, 5),
            timeline.map { it.occurredOrder }
        )
        assertEquals(
            "时间线第 3 条必须指向**修订后**的事件 id（不是旧版本）",
            "c3-v2",
            timeline[2].sourceEventIds.single().raw
        )
        // F4（P2-2）：声称原文必须出现在 detail 里 —— 走真实仓储也要成立，
        // 不能只在 TimelineBuilder 单测里成立（编码/解码链可能丢字段）
        assertTrue(
            "F4：修订后的原文必须出现在时间线 detail，实际=${timeline[2].detail}",
            timeline[2].detail.contains("修订后 3")
        )
        assertFalse(
            "F4：旧版本的原文不得残留",
            timeline[2].detail.contains("原始 3")
        )

        // 审计视图：位置 3 有两个版本，旧版本 retracted
        val all = database.matchEventDao().listAll(matchId.raw)
        assertEquals("审计视图应有 6 行（5 + 1 个历史版本）", 6, all.size)
        assertEquals("位置 3 必须有两个版本", 2, all.count { it.logicalPosition == 3 })
        assertEquals("其中恰好一个被撤销", 1, all.count { it.logicalPosition == 3 && it.retracted })
    }

    // ————————————————————————————————————————————————————————
    // V05：保存中途失败
    // ————————————————————————————————————————————————————————

    /**
     * 修订被规则拒绝（跨事件类型）时必须**一行都不写**：旧事实在、版本号不变、无孤儿新行。
     *
     * 用「跨类型修订」而不是 mock 异常，是因为它走的是**真实的前置校验分支**，
     * 不需要给生产代码留测试钩子。
     */
    @Test
    fun v05_rejected_revision_writes_nothing_and_keeps_old_fact() = runBlocking {
        val matchId = createMatch()
        val target = repository.appendEvent(matchId, claim("c1", 1, "原始")).eventId!!

        val before = snapshotOf(matchId)
        val result = repository.reviseEvent(
            matchId = matchId,
            targetEventId = target,
            replacement = AuditEvents.questStarted("wrong-type", questNo = 1, leaderSeat = 1)
        )

        assertEquals(
            "跨类型修订必须被拒",
            RepositoryError.INVALID_SUPERSEDE_TARGET,
            result.error
        )
        assertEquals("数据库必须完全没变", before, snapshotOf(matchId))

        val active = database.matchEventDao().listActive(matchId.raw)
        assertEquals("旧事实必须还在", listOf("c1"), active.map { it.id })
        assertEquals("位置不得变", listOf(1), active.map { it.logicalPosition })
        assertEquals("版本号不得变", listOf(0), active.map { it.revisionSeq })
        assertEquals("不得留下孤儿新行", 1, database.matchEventDao().listAll(matchId.raw).size)
    }

    // ————————————————————————————————————————————————————————
    // V06：重复点击 / 并发修订
    // ————————————————————————————————————————————————————————

    /**
     * 用**同一个 targetEventId 连点两次**（UI 双指/排队提交的等价物）：
     * 第二次必须拿到可理解的错误，且该位置**仍只有一条 active**。
     */
    @Test
    fun v06_second_revision_on_same_stale_target_fails_cleanly() = runBlocking {
        val matchId = createMatch()
        val target = repository.appendEvent(matchId, claim("c1", 1, "原始")).eventId!!

        val first = repository.reviseEvent(matchId, target, claim("c1-a", 1, "第一次修订"))
        assertNull("第一次修订应成功：${first.error}", first.error)

        val second = repository.reviseEvent(matchId, target, claim("c1-b", 1, "第二次修订"))
        assertEquals(
            "目标已被撤销，第二次必须返回 INVALID_SUPERSEDE_TARGET（不是崩溃）",
            RepositoryError.INVALID_SUPERSEDE_TARGET,
            second.error
        )

        val active = database.matchEventDao().listActive(matchId.raw)
        assertEquals("该位置只能有一条 active", 1, active.size)
        assertEquals("留下的是第一次修订的版本", "c1-a", active.first().id)
        assertEquals("位置不变", 1, active.first().logicalPosition)
        assertEquals("版本号只前进了一次", 1, active.first().revisionSeq)

        // 审计链：3 行（原始 + 第一次 + 第二次一行都没写）
        assertEquals("被拒的第二次不得写入任何行", 2, database.matchEventDao().listAll(matchId.raw).size)
    }

    // ————————————————————————————————————————————————————————
    // V07：修改影响终局的任务
    // ————————————————————————————————————————————————————————

    /**
     * 三次失败 → 坏人胜。揭晓（revealedAt 落库）后，把第 3 次任务改成成功：
     * - `matches.status`（首页读列）必须回落到 IN_PROGRESS；
     * - 记录页 / 复盘页读的是同一份 replay，必须与列一致；
     * - `revealedAt` 必须保留（RT-001 不得退化）。
     */
    @Test
    fun v07_revising_decisive_mission_updates_status_keeps_revealed_at() = runBlocking {
        val matchId = createMatch()
        saveFullRoles(matchId)

        appendQuestCycle(matchId, questNo = 1, failCount = 1)
        appendQuestCycle(matchId, questNo = 2, failCount = 1)
        val decisive = appendQuestCycle(matchId, questNo = 3, failCount = 1)
        assertEquals("三次失败必须终局为坏人胜", MatchStatus.FINISHED_EVIL, statusOf(matchId))

        clock.advanceBy(60_000)
        repository.revealAllRoles(matchId)
        val revealedAt = row(matchId).revealedAt
        assertNotNull("揭晓后必须有 revealedAt", revealedAt)

        clock.advanceBy(3_600_000)
        val replacement = AuditEvents.missionResolved(
            id = "q3-mission-fixed",
            questNo = 3,
            teamSeats = (1..4).toList(),
            teamSizeExpected = 4,
            failCount = 0,
            observedOutcome = MissionOutcome.SUCCESS
        )
        val revised = repository.reviseEvent(matchId, decisive, replacement)
        assertNull("修订任务结果应成功：${revised.error}", revised.error)

        val stored = row(matchId)
        val bundle = repository.getBundle(matchId)!!
        val replayed = ReplayEngine(registry.avalonReducer)
            .replay(bundle.toReconstructedMatch()).state.status

        assertEquals(
            "首页读的列必须与复盘重放一致",
            replayed.name,
            stored.status
        )
        assertEquals(
            "两次失败不应终局，必须回到进行中",
            MatchStatus.IN_PROGRESS,
            MatchStatus.valueOf(stored.status)
        )
        assertEquals(
            "RT-001：修订不得清掉 revealedAt",
            revealedAt,
            stored.revealedAt
        )
        // 记录页/复盘页看到的事件里，第 3 任务必须是成功
        val mission3 = bundle.events
            .filterIsInstance<com.roundtrace.app.core.model.MissionResolved>()
            .first { it.questNo == 3 }
        assertEquals("第 3 任务应变成成功", MissionOutcome.SUCCESS, mission3.observedOutcome)
        assertEquals("失败牌数应为 0", 0, mission3.failCount)
    }

    // ————————————————————————————————————————————————————————
    // 工具
    // ————————————————————————————————————————————————————————

    private fun claim(id: String, seat: Int, text: String): PlayerClaimed = PlayerClaimed(
        eventId = EventId(id),
        occurredOrder = 0,
        recordedAt = 0L,
        questNo = 1,
        speakerSeat = seat,
        claimType = ClaimType.SUSPECT,
        claimedRole = null,
        targetSeats = emptyList(),
        text = text
    )

    /** DB 的完整「指纹」：行数 + 每行 (id, 位置, 版本, retracted)。 */
    private suspend fun snapshotOf(matchId: MatchId): List<String> =
        database.matchEventDao().listAll(matchId.raw)
            .map { "${it.id}|${it.logicalPosition}|${it.revisionSeq}|${it.retracted}" }

    private suspend fun createMatch(playerCount: Int = 8): MatchId =
        repository.createMatch(
            MatchCreationDraft(
                title = "审计对局（修订完整性）",
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

    private suspend fun appendQuestCycle(matchId: MatchId, questNo: Int, failCount: Int): EventId {
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

    private suspend fun statusOf(matchId: MatchId): MatchStatus =
        MatchStatus.valueOf(row(matchId).status)

    private suspend fun row(matchId: MatchId) =
        database.matchDao().findById(matchId.raw) ?: error("对局不存在")
}
