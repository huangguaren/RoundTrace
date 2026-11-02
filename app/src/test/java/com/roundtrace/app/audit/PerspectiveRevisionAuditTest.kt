package com.roundtrace.app.audit

import com.roundtrace.app.core.export.DefaultImportExportService
import com.roundtrace.app.core.export.ExportOptions
import com.roundtrace.app.core.export.ExportResult
import com.roundtrace.app.core.knowledge.AvalonKnowledgeProvider
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.GamePosition
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.model.PrivateNoteAdded
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.ReconstructedMatch
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.replay.VisibleEventPolicy
import com.roundtrace.app.core.rules.DefaultGameRulesRegistry
import com.roundtrace.app.core.rules.avalon.AvalonRules
import com.roundtrace.app.knowledge.AvalonScenario
import com.roundtrace.app.util.FakeMatchRepository
import com.roundtrace.app.util.MatchBuilder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA 阶段 D · V08 / V09：**修订之后**的时间边界与泄漏面。
 *
 * 上一轮（阶段 D 之前）的 5 出口测试验的是「从未修订过的对局」。
 * 本轮的新问题是：既然历史可以被改写，
 * ① `upTo` 是否仍然只放行「位置 <= upTo」的事实（不能因为修订而提前泄漏）；
 * ② 一条**私有**声称被修订后，公开视角 / 默认导出是否仍然看不到新版本。
 */
class PerspectiveRevisionAuditTest {

    private val registry = DefaultGameRulesRegistry()
    private val provider = AvalonKnowledgeProvider(registry.avalonReducer)

    // ————————————————————————————————————————————————————————
    // V08：选点前 / 后的复盘边界（修订后）
    // ————————————————————————————————————————————————————————

    @Test
    fun v08_revised_vote_is_invisible_before_its_position() {
        val match = matchWithRevisedVoteAtPosition3()

        // 位置 2 之前的快照：不得出现「第 3 个位置」的投票事实（无论是旧版还是修订版）
        val before = provider.snapshot(
            match = match,
            observer = AvalonScenario.MERLIN,
            at = GamePosition(2),
            mode = Perspective.OBSERVER_AT_TIME
        )
        assertTrue(
            "位置 2 之前不得出现任何位置 3 的公开事实，实际=${before.publicFacts.map { it.id }}",
            before.publicFacts.none { it.id == REVISED_VOTE_ID || it.id == ORIGINAL_VOTE_ID }
        )
        assertFalse(
            "位置 2 之前的快照不得泄漏修订后的票数（未来信息）",
            before.publicFacts.any { it.summary.contains("赞成 4") }
        )

        // 位置 3（含）的快照：必须看到**修订后**的版本
        val atPosition3 = provider.snapshot(
            match = match,
            observer = AvalonScenario.MERLIN,
            at = GamePosition(3),
            mode = Perspective.OBSERVER_AT_TIME
        )
        assertTrue(
            "位置 3 必须包含修订后的投票事实",
            atPosition3.publicFacts.any { it.id == REVISED_VOTE_ID }
        )
        assertFalse(
            "修订后的旧版本不得出现",
            atPosition3.publicFacts.any { it.id == ORIGINAL_VOTE_ID }
        )
        assertTrue(
            "位置 3 的票数应为修订后的 4/4，实际=" +
                atPosition3.publicFacts.filter { it.questNo == 1 }.map { it.summary },
            atPosition3.publicFacts.any { it.summary.contains("赞成 4") }
        )
    }

    /** 修订**之后**再看位置 1：复盘位置的含义不能因为「时间线变长」而漂移。 */
    @Test
    fun v08b_position_semantics_stable_after_revision() {
        val base = matchWithRevisedVoteAtPosition3()
        val lastPosition = base.events.maxOf { it.occurredOrder }
        assertEquals("修订不改变时间线长度", 5, lastPosition)

        val end = provider.snapshot(
            match = base,
            observer = AvalonScenario.MERLIN,
            at = GamePosition(lastPosition),
            mode = Perspective.PUBLIC
        )
        assertTrue(
            "末位快照必须包含全部 5 个位置的公开事实",
            end.publicFacts.map { it.occurredOrder }.max() == lastPosition
        )
    }

    // ————————————————————————————————————————————————————————
    // V09：私有声称/笔记参与修订
    // ————————————————————————————————————————————————————————

    @Test
    fun v09_revised_private_claim_is_still_invisible_in_public_view() {
        val match = matchWithRevisedPrivateClaim(revealed = false)

        val publicSnapshot = provider.snapshot(
            match = match,
            observer = null,
            at = GamePosition(AT),
            mode = Perspective.PUBLIC
        )
        assertFalse(
            "修订后的私有声称不得进入 publicFacts",
            publicSnapshot.publicFacts.any { it.id == NEW_PRIVATE_CLAIM_ID }
        )
        assertFalse(
            "修订后的私有声称正文不得出现在公开事实里",
            publicSnapshot.publicFacts.any { it.summary.contains(NEW_PRIVATE_TEXT) }
        )
        assertFalse(
            "旧版本（已撤销）同样不得出现",
            publicSnapshot.publicFacts.any { it.id == OLD_PRIVATE_CLAIM_ID }
        )
        // 对照：公开声称必须在
        assertTrue(
            "对照组：公开声称必须仍在 publicFacts 里",
            publicSnapshot.publicFacts.any { it.id == PUBLIC_CLAIM_ID }
        )

        // 上帝视角（已揭晓）：两个版本都能看到（审计需要），但只有新版本是 active
        val godMatch = matchWithRevisedPrivateClaim(revealed = true)
        val god = provider.snapshot(
            match = godMatch,
            observer = null,
            at = GamePosition(AT),
            mode = Perspective.GOD_AFTER_REVEAL
        )
        assertFalse(
            "即使上帝视角，private 声称也不该混进 publicFacts（publicFacts 的语义是'公开事实'）",
            god.publicFacts.any { it.id == NEW_PRIVATE_CLAIM_ID }
        )
        assertEquals(
            "上帝视角必须看到 4 号是莫德雷德（修订不改变角色真相）",
            RoleId.MORDRED,
            god.seatKnowledge.getValue(4).visibleRole
        )
    }

    @Test
    fun v09b_revised_private_claim_is_dropped_from_default_export() = runTest {
        val match = matchWithRevisedPrivateClaim(revealed = false)
        val repository = FakeMatchRepository(
            config = AvalonScenario.eightPlayerConfig(),
            initialEvents = match.events,
            revealedAt = null,
            initialRoles = match.roleAssignments
        )
        val service = DefaultImportExportService(repository = repository, rules = AvalonRules())

        val defaultJson = service.exportJson(MatchId("fake-match"), ExportOptions.DEFAULT)
        assertTrue("导出应成功", defaultJson is ExportResult.Success)
        val text = (defaultJson as ExportResult.Success).text
        assertFalse("默认导出不得含修订后的私有声称 id", text.contains(NEW_PRIVATE_CLAIM_ID))
        assertFalse("默认导出不得含修订后的私有声称正文", text.contains(NEW_PRIVATE_TEXT))
        assertFalse("默认导出也不得含旧版本", text.contains(OLD_PRIVATE_CLAIM_ID))
        assertTrue("对照组：公开声称必须保留", text.contains(PUBLIC_CLAIM_ID))

        // 显式勾选后才出现（证明是「被过滤」而不是「没写进去」）
        val withPrivate = service.exportJson(
            MatchId("fake-match"),
            ExportOptions(includePrivateNotes = true, includeSecrets = true)
        )
        assertTrue(
            "勾选后才应包含私有声称",
            (withPrivate as ExportResult.Success).text.contains(NEW_PRIVATE_CLAIM_ID)
        )
    }

    /** 私有笔记（PrivateNoteAdded）从事修订后仍受 `availableFromOrder` 时间闸门约束。 */
    @Test
    fun v09c_revised_private_note_keeps_time_gate() {
        val builder = AvalonScenario.fullMatch()
        builder.appendRaw(
            PrivateNoteAdded(
                eventId = EventId(OLD_NOTE_ID),
                occurredOrder = POSITION,
                recordedAt = 0L,
                visibility = EventVisibility.PRIVATE_OBSERVER,
                questNo = 3,
                observerSeat = AvalonScenario.MERLIN,
                beliefType = com.roundtrace.app.core.model.BeliefType.SUSPECT,
                targetSeats = listOf(4),
                text = "旧笔记",
                availableFromOrder = 1,
                retracted = true
            )
        )
        builder.appendRaw(
            PrivateNoteAdded(
                eventId = EventId(NEW_NOTE_ID),
                occurredOrder = POSITION,
                recordedAt = 0L,
                visibility = EventVisibility.PRIVATE_OBSERVER,
                questNo = 3,
                observerSeat = AvalonScenario.MERLIN,
                beliefType = com.roundtrace.app.core.model.BeliefType.SUSPECT,
                targetSeats = listOf(4),
                text = "修订后的笔记",
                availableFromOrder = 900
            )
        )
        val match = builder.build()

        val early = provider.snapshot(
            match = match,
            observer = AvalonScenario.MERLIN,
            at = GamePosition(1),
            mode = Perspective.OBSERVER_AT_TIME
        )
        val late = provider.snapshot(
            match = match,
            observer = AvalonScenario.MERLIN,
            at = GamePosition(10_000),
            mode = Perspective.OBSERVER_AT_TIME
        )

        assertFalse(
            "availableFromOrder 未到的修订版笔记不得显示",
            early.subjectiveBeliefs.any { it.text == "修订后的笔记" }
        )
        assertFalse(
            "已撤销的旧版本笔记不得显示（retracted 过滤必须早于一切）",
            late.subjectiveBeliefs.any { it.text == "旧笔记" }
        )
        assertTrue(
            "时间到了之后修订版笔记才出现",
            late.subjectiveBeliefs.any { it.text == "修订后的笔记" }
        )
        assertFalse(
            "对照组外的检查：主观笔记永不进入 publicFacts",
            late.publicFacts.any { it.summary.contains("笔记") }
        )
    }

    // ————————————————————————————————————————————————————————
    // 构造
    // ————————————————————————————————————————————————————————

    /**
     * 位置 3 是一次投票，被修订成「4 赞成 / 4 反对」。
     * 领域层用「同 occurredOrder + 旧版本 retracted=1」表达一次修订。
     */
    private fun matchWithRevisedVoteAtPosition3(): ReconstructedMatch {
        val builder: MatchBuilder = MatchBuilder.eightPlayerMerlinMordred()
        // 全部用 appendRaw，才能把「位置 3」精确钉死（MatchBuilder 的便捷方法会自己发号）
        builder.appendRaw(
            com.roundtrace.app.core.model.QuestStarted(
                eventId = EventId("audit-q1-start"),
                occurredOrder = 1,
                recordedAt = 0L,
                questNo = 1,
                leaderSeat = 1
            )
        )
        builder.appendRaw(
            com.roundtrace.app.core.model.TeamProposed(
                eventId = EventId("audit-q1-proposal"),
                occurredOrder = 2,
                recordedAt = 0L,
                questNo = 1,
                proposalNo = 1,
                leaderSeat = 1,
                teamSeats = listOf(1, 2, 3)
            )
        )
        // 位置 3：旧版本（已撤销）+ 修订版
        builder.appendRaw(
            com.roundtrace.app.core.model.TeamVoteRecorded(
                eventId = EventId(ORIGINAL_VOTE_ID),
                occurredOrder = 3,
                recordedAt = 0L,
                questNo = 1,
                proposalNo = 1,
                votesBySeat = null,
                approveCount = 2,
                rejectCount = 6,
                playerCountAtVote = 8,
                retracted = true
            )
        )
        builder.appendRaw(
            com.roundtrace.app.core.model.TeamVoteRecorded(
                eventId = EventId(REVISED_VOTE_ID),
                occurredOrder = 3,
                recordedAt = 0L,
                questNo = 1,
                proposalNo = 1,
                votesBySeat = null,
                approveCount = 4,
                rejectCount = 4,
                playerCountAtVote = 8
            )
        )
        builder.appendRaw(
            com.roundtrace.app.core.model.TeamProposalResolved(
                eventId = EventId("audit-q1-decision"),
                occurredOrder = 4,
                recordedAt = 0L,
                questNo = 1,
                proposalNo = 1,
                decision = ProposalDecision.REJECTED,
                approveCount = 4,
                rejectCount = 4,
                playerCountAtVote = 8
            )
        )
        builder.appendRaw(
            com.roundtrace.app.core.model.MissionResolved(
                eventId = EventId("audit-q1-mission"),
                occurredOrder = 5,
                recordedAt = 0L,
                questNo = 1,
                teamSeats = listOf(1, 2, 3),
                teamSizeExpected = 3,
                failCount = 0,
                observedOutcome = MissionOutcome.SUCCESS
            )
        )
        return builder.build()
    }

    private fun matchWithRevisedPrivateClaim(revealed: Boolean): ReconstructedMatch {
        val builder = AvalonScenario.fullMatch()
        builder.appendRaw(
            PlayerClaimed(
                eventId = EventId(OLD_PRIVATE_CLAIM_ID),
                occurredOrder = POSITION,
                recordedAt = 0L,
                visibility = EventVisibility.PRIVATE_OBSERVER,
                questNo = 3,
                speakerSeat = 4,
                claimType = ClaimType.ROLE_CLAIM,
                claimedRole = RoleId.MERLIN,
                text = "旧：我要跳梅林",
                retracted = true
            )
        )
        builder.appendRaw(
            PlayerClaimed(
                eventId = EventId(NEW_PRIVATE_CLAIM_ID),
                occurredOrder = POSITION,
                recordedAt = 0L,
                visibility = EventVisibility.PRIVATE_OBSERVER,
                questNo = 3,
                speakerSeat = 4,
                claimType = ClaimType.ROLE_CLAIM,
                claimedRole = RoleId.MERLIN,
                text = NEW_PRIVATE_TEXT
            )
        )
        builder.appendRaw(
            PlayerClaimed(
                eventId = EventId(PUBLIC_CLAIM_ID),
                occurredOrder = POSITION + 1,
                recordedAt = 0L,
                visibility = EventVisibility.PUBLIC,
                questNo = 3,
                speakerSeat = 2,
                claimType = ClaimType.SUSPECT,
                claimedRole = null,
                targetSeats = listOf(3),
                text = "公开：3 号有问题"
            )
        )
        return builder.build(revealedAtEpochMs = if (revealed) 1_700_000_100_000L else null)
    }

    private companion object {
        const val ORIGINAL_VOTE_ID = "audit-vote-original"
        const val REVISED_VOTE_ID = "audit-vote-revised"
        const val OLD_PRIVATE_CLAIM_ID = "audit-private-old"
        const val NEW_PRIVATE_CLAIM_ID = "audit-private-new"
        const val PUBLIC_CLAIM_ID = "audit-public-claim"
        const val OLD_NOTE_ID = "audit-note-old"
        const val NEW_NOTE_ID = "audit-note-new"
        const val NEW_PRIVATE_TEXT = "修订后：我还是要跳梅林"
        const val POSITION = 900
        const val AT = 10_000
    }
}
