package com.roundtrace.app.knowledge

import com.roundtrace.app.core.knowledge.AvalonEvidenceAnalyzer
import com.roundtrace.app.core.knowledge.AvalonKnowledgeProvider
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.FactKind
import com.roundtrace.app.core.model.GamePosition
import com.roundtrace.app.core.model.KnowledgeLabel
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteRecorded
import com.roundtrace.app.core.replay.VisibleEventPolicy
import com.roundtrace.app.util.MatchBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RT-005：`PRIVATE_OBSERVER` 事件不得从任何缝里漏到非授权视角（TASKS.md 之外的安全回归）。
 *
 * ## 被测的三条缝
 *
 * 1. `AvalonKnowledgeProvider.buildPublicFacts` —— 公开事实列表；
 * 2. `AvalonKnowledgeProvider.claimLabels` —— 声称标签（`CLAIMED_*`）；
 * 3. `AvalonEvidenceAnalyzer.analyze` —— 推理历史。
 *
 * 这三处原来都只看「时间边界」，不看「可见性边界」。
 * 于是「我私下记了一句 4 号跳梅林」会变成公开视角下的 A08 结论。
 *
 * ## 不得破坏的既有语义（B31）
 *
 * `PrivateNoteAdded.availableFromOrder` 的回填语义仍然有效：
 * 本策略只决定「谁」能看，不决定「什么时候」能看。
 * 已有 `PerspectiveIsolationTest` 覆盖回填场景，这里额外断言「本人仍看得到」。
 */
class VisibilityLeakTest {

    private val provider = AvalonKnowledgeProvider(AvalonScenario.reducer())
    private val analyzer = AvalonEvidenceAnalyzer()

    private companion object {
        const val PRIVATE_OWNER = 4
        const val OTHER_SEAT = 2
        const val PRIVATE_CLAIM_ID = "private-claim-4"
        const val PRIVATE_PROPOSAL_ID = "private-proposal-4"
        const val PRIVATE_VOTE_ID = "private-vote-4"
        const val PRIVATE_NOTE_ID = "private-note-4"
        const val PRIVATE_NOTE_TEXT = "私下记：4 号跳梅林"
    }

    /**
     * 在真实五任务场景上，叠加三条**归属 4 号**的 `PRIVATE_OBSERVER` 事件：
     * 一条私人声称（跳梅林）、一条私人组队提案、一条私人投票。
     *
     * `occurredOrder` 用 900+ 保证排在所有公开事件之后；`availableFromOrder = 1`
     * 让私人笔记按 B31 的「回填」语义立即可见。
     */
    private fun builderWithPrivateEvents(): MatchBuilder {
        val builder = AvalonScenario.fullMatch()
        builder.appendRaw(
            PlayerClaimed(
                eventId = EventId(PRIVATE_CLAIM_ID),
                occurredOrder = 900,
                recordedAt = 0L,
                visibility = EventVisibility.PRIVATE_OBSERVER,
                questNo = 2,
                speakerSeat = PRIVATE_OWNER,
                claimType = ClaimType.ROLE_CLAIM,
                claimedRole = RoleId.MERLIN,
                targetSeats = listOf(1)
            )
        )
        builder.appendRaw(
            TeamProposed(
                eventId = EventId(PRIVATE_PROPOSAL_ID),
                occurredOrder = 901,
                recordedAt = 0L,
                visibility = EventVisibility.PRIVATE_OBSERVER,
                questNo = 2,
                proposalNo = 9,
                leaderSeat = PRIVATE_OWNER,
                teamSeats = listOf(4, 5, 8)
            )
        )
        builder.appendRaw(
            TeamVoteRecorded(
                eventId = EventId(PRIVATE_VOTE_ID),
                occurredOrder = 902,
                recordedAt = 0L,
                visibility = EventVisibility.PRIVATE_OBSERVER,
                questNo = 2,
                proposalNo = 9,
                votesBySeat = null,
                approveCount = 1,
                rejectCount = 7,
                playerCountAtVote = 8
            )
        )
        builder.appendRaw(
            com.roundtrace.app.core.model.PrivateNoteAdded(
                eventId = EventId(PRIVATE_NOTE_ID),
                occurredOrder = 903,
                recordedAt = 0L,
                visibility = EventVisibility.PRIVATE_OBSERVER,
                questNo = 2,
                observerSeat = PRIVATE_OWNER,
                beliefType = com.roundtrace.app.core.model.BeliefType.ROLE_GUESS,
                targetSeats = listOf(1),
                text = PRIVATE_NOTE_TEXT,
                availableFromOrder = 1
            )
        )
        return builder
    }

    private fun endOf(builder: MatchBuilder) =
        GamePosition(builder.build().events.maxOf { it.occurredOrder })

    // ————————————————————————————————————————————————————————
    // 缝 1：公开事实
    // ————————————————————————————————————————————————————————

    @Test
    fun publicFactsNeverContainPrivateEvents() {
        val builder = builderWithPrivateEvents()
        val snapshot = provider.snapshot(
            match = builder.build(),
            observer = null,
            at = endOf(builder),
            mode = Perspective.PUBLIC
        )

        assertTrue(
            "公开事实不得包含私有声称：${snapshot.publicFacts.map { it.summary }}",
            snapshot.publicFacts.none { it.id == PRIVATE_CLAIM_ID }
        )
        assertTrue(
            "公开事实不得包含私有提案",
            snapshot.publicFacts.none { it.id == PRIVATE_PROPOSAL_ID }
        )
        assertTrue(
            "公开事实不得包含私投票",
            snapshot.publicFacts.none { it.id == PRIVATE_VOTE_ID }
        )
        assertTrue(
            "公开事实里不得出现私人笔记正文",
            snapshot.publicFacts.none { it.summary.contains(PRIVATE_NOTE_TEXT) }
        )
        assertTrue(
            "公开事实里应仍有真实存在的公开声称（第 3 任务 7 号）",
            snapshot.publicFacts.any { it.kind == FactKind.CLAIM }
        )
    }

    // ————————————————————————————————————————————————————————
    // 缝 2：声称标签
    // ————————————————————————————————————————————————————————

    @Test
    fun privateClaimDoesNotProducePublicClaimLabel() {
        val builder = builderWithPrivateEvents()
        val snapshot = provider.snapshot(
            match = builder.build(),
            observer = null,
            at = endOf(builder),
            mode = Perspective.PUBLIC
        )

        assertTrue(
            "4 号在公开视角下不得因为私人声称而获得 CLAIMED_MERLIN 标签，" +
                "实际=${snapshot.seatKnowledge.getValue(PRIVATE_OWNER).labels}",
            !snapshot.seatKnowledge.getValue(PRIVATE_OWNER)
                .labels.contains(KnowledgeLabel.CLAIMED_MERLIN)
        )
        assertTrue(
            "7 号的公开声称仍应产生 CLAIMED_PERCIVAL 标签",
            snapshot.seatKnowledge.getValue(7).labels.contains(KnowledgeLabel.CLAIMED_PERCIVAL)
        )
    }

    // ————————————————————————————————————————————————————————
    // 缝 3：推理历史
    // ————————————————————————————————————————————————————————

    @Test
    fun publicAnalysisIgnoresPrivateClaim() {
        val builder = builderWithPrivateEvents()
        val findings = analyzer.analyzeAt(
            match = builder.build(),
            observer = null,
            at = endOf(builder),
            mode = Perspective.PUBLIC,
            knowledgeProvider = provider
        )

        assertFalse(
            "公开推理不得引用私有声称事件",
            findings.any { it.sourceEventIds.any { id -> id.raw == PRIVATE_CLAIM_ID } }
        )
        assertFalse(
            "公开推理不得引用私有提案事件",
            findings.any { it.sourceEventIds.any { id -> id.raw == PRIVATE_PROPOSAL_ID } }
        )
        assertFalse(
            "公开推理不得引用私有投票事件",
            findings.any { it.sourceEventIds.any { id -> id.raw == PRIVATE_VOTE_ID } }
        )
        assertFalse(
            "公开推理不得出现私有提案编号的结果（A01-2-9 / A02-2-9）",
            findings.any { it.id == "A01-2-9" || it.id == "A02-2-9" }
        )
    }

    @Test
    fun publicAnalysisStillSeesRealPublicEvents() {
        val builder = builderWithPrivateEvents()
        val findings = analyzer.analyzeAt(
            match = builder.build(),
            observer = null,
            at = endOf(builder),
            mode = Perspective.PUBLIC,
            knowledgeProvider = provider
        )

        assertTrue(
            "公开提案仍必须被分析（A01），否则说明过滤过头了",
            findings.any { it.ruleId == AvalonEvidenceAnalyzer.RULE_A01 }
        )
        assertTrue(
            "公开任务结果仍必须被分析（A06/A07）",
            findings.any { it.ruleId == AvalonEvidenceAnalyzer.RULE_A06 }
        )
        assertTrue(
            "公开声称仍必须被分析（A08）",
            findings.any { it.ruleId == AvalonEvidenceAnalyzer.RULE_A08 }
        )
    }

    @Test
    fun ownerSeesOwnPrivateClaim() {
        val builder = builderWithPrivateEvents()
        val findings = analyzer.analyzeAt(
            match = builder.build(),
            observer = PRIVATE_OWNER,
            at = endOf(builder),
            mode = Perspective.OBSERVER_AT_TIME,
            knowledgeProvider = provider
        )

        assertTrue(
            "4 号本人应能看到自己那条私人声称产生的结论",
            findings.any { it.sourceEventIds.any { id -> id.raw == PRIVATE_CLAIM_ID } }
        )
    }

    @Test
    fun otherObserverCannotSeeSomeoneElsesPrivateClaim() {
        val builder = builderWithPrivateEvents()
        val findings = analyzer.analyzeAt(
            match = builder.build(),
            observer = OTHER_SEAT,
            at = endOf(builder),
            mode = Perspective.OBSERVER_AT_TIME,
            knowledgeProvider = provider
        )

        assertFalse(
            "2 号不得看到 4 号的私人声称",
            findings.any { it.sourceEventIds.any { id -> id.raw == PRIVATE_CLAIM_ID } }
        )
    }

    @Test
    fun godViewSeesAllPrivateEvents() {
        val builder = builderWithPrivateEvents()
        val revealed = builder.build(revealedAtEpochMs = 1_700_000_100_000L)
        val findings = analyzer.analyzeAt(
            match = revealed,
            observer = null,
            at = GamePosition(revealed.events.maxOf { it.occurredOrder }),
            mode = Perspective.GOD_AFTER_REVEAL,
            knowledgeProvider = provider
        )

        assertTrue(
            "上帝视角应能看到私有声称",
            findings.any { it.sourceEventIds.any { id -> id.raw == PRIVATE_CLAIM_ID } }
        )
    }

    // ————————————————————————————————————————————————————————
    // B31 回归：策略不得破坏「回填笔记」
    // ————————————————————————————————————————————————————————

    @Test
    fun backfilledPrivateNoteStillVisibleToOwner() {
        val builder = builderWithPrivateEvents()
        val match = builder.build()
        // 取一个「早于笔记事件」的位置：笔记靠 availableFromOrder = 1 回填
        val earlyPosition = GamePosition(1)

        val snapshot = provider.snapshot(
            match = match,
            observer = PRIVATE_OWNER,
            at = earlyPosition,
            mode = Perspective.OBSERVER_AT_TIME
        )

        assertTrue(
            "B31：availableFromOrder 早于 at 的私人笔记，本人必须仍能看到（RT-005 不得破坏回填）",
            snapshot.subjectiveBeliefs.any { it.text == PRIVATE_NOTE_TEXT }
        )
        assertTrue(
            "但公开视角永远看不到它",
            provider.snapshot(
                match = match,
                observer = null,
                at = earlyPosition,
                mode = Perspective.PUBLIC
            ).subjectiveBeliefs.isEmpty()
        )
    }

    // ————————————————————————————————————————————————————————
    // 策略本身（纯函数）
    // ————————————————————————————————————————————————————————

    @Test
    fun policyMatrixIsExplicit() {
        val builder = builderWithPrivateEvents()
        val events = builder.build().events
        val privateClaim = events.first { it.eventId.raw == PRIVATE_CLAIM_ID }

        assertFalse(
            "PUBLIC 不得看见私有事件",
            VisibleEventPolicy.isVisibleTo(privateClaim, Perspective.PUBLIC, null)
        )
        assertFalse(
            "别人的当时视角不得看见",
            VisibleEventPolicy.isVisibleTo(privateClaim, Perspective.OBSERVER_AT_TIME, OTHER_SEAT)
        )
        assertTrue(
            "本人当时视角可见",
            VisibleEventPolicy.isVisibleTo(
                privateClaim,
                Perspective.OBSERVER_AT_TIME,
                PRIVATE_OWNER
            )
        )
        assertTrue(
            "观察者未指定时即使事件是自己的也不可见（安全侧默认）",
            !VisibleEventPolicy.isVisibleTo(privateClaim, Perspective.OBSERVER_AT_TIME, null)
        )
        assertTrue(
            "上帝视角全可见",
            VisibleEventPolicy.isVisibleTo(
                privateClaim,
                Perspective.GOD_AFTER_REVEAL,
                null
            )
        )

        assertEquals(
            "PUBLIC 过滤后不得残留任何私有事件",
            0,
            VisibleEventPolicy.forPerspective(events, Perspective.PUBLIC, null)
                .count { it.visibility == EventVisibility.PRIVATE_OBSERVER }
        )
        assertEquals(
            "上帝视角过滤后应保留全部事件",
            events.size,
            VisibleEventPolicy.forPerspective(events, Perspective.GOD_AFTER_REVEAL, null).size
        )
    }
}
