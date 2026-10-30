package com.roundtrace.app.knowledge

import com.roundtrace.app.core.knowledge.AvalonEvidenceAnalyzer
import com.roundtrace.app.core.knowledge.AvalonKnowledgeProvider
import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.GamePosition
import com.roundtrace.app.core.model.KnowledgeLabel
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.PrivateRelation
import com.roundtrace.app.core.model.RoleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K02：7 号（派西维尔）观察开局 —— 看到 1/3 两名候选，不区分真假。
 * K08：7 号公开跳派西维尔 —— 显示为 claim，不等于真实身份。
 */
class PercivalKnowledgeTest {

    private val provider = AvalonKnowledgeProvider(AvalonScenario.reducer())
    private val analyzer = AvalonEvidenceAnalyzer()
    private val match = AvalonScenario.match()

    @Test
    fun k02_percival_sees_two_candidates_without_distinguishing() {
        val snapshot = provider.snapshot(
            match = match,
            observer = AvalonScenario.PERCIVAL,
            at = GamePosition.start(),
            mode = Perspective.OBSERVER_AT_TIME
        )

        val candidates = snapshot.privateRoleFacts
            .filter { it.relation == PrivateRelation.MERLIN_CANDIDATE }
            .map { it.targetSeat }
            .sorted()
        assertEquals("K02 派西维尔候选应为 1、3", listOf(1, 3), candidates)

        val seat1 = snapshot.seatKnowledge.getValue(1)
        val seat3 = snapshot.seatKnowledge.getValue(3)
        assertEquals(
            "K02 1 号应为候选角色 [梅林, 莫甘娜]（不区分真假）",
            listOf(RoleId.MERLIN, RoleId.MORGANA),
            seat1.candidateRoles
        )
        assertEquals(
            "K02 3 号同样应为候选角色 [梅林, 莫甘娜]",
            listOf(RoleId.MERLIN, RoleId.MORGANA),
            seat3.candidateRoles
        )
        assertNull("K02 候选不等于确定：1 号阵营必须未知", seat1.knownAlignment)
        assertNull("K02 候选不等于确定：3 号阵营必须未知", seat3.knownAlignment)
        assertNull("K02 更不能把 1 号标成梅林", seat1.visibleRole)
        assertNull("K02 也不能把 3 号标成莫甘娜", seat3.visibleRole)
    }

    @Test
    fun k02_percival_does_not_get_evil_list() {
        val snapshot = provider.snapshot(
            match = match,
            observer = AvalonScenario.PERCIVAL,
            at = GamePosition.start(),
            mode = Perspective.OBSERVER_AT_TIME
        )
        assertTrue(
            "K02 派西维尔不得获得「确定坏人」名单",
            snapshot.privateRoleFacts.none { it.relation == PrivateRelation.KNOWN_EVIL }
        )
        assertEquals(
            "K02 派西维尔自己的阵营是好人（自己知道）",
            Alignment.GOOD,
            snapshot.seatKnowledge.getValue(AvalonScenario.PERCIVAL).knownAlignment
        )
    }

    @Test
    fun k08_public_percival_claim_is_only_a_claim() {
        // 场景里 7 号在第 3 任务后公开声称派西维尔
        val afterClaim = GamePosition(
            match.events.first { it is com.roundtrace.app.core.model.PlayerClaimed }.occurredOrder
        )
        val snapshot = provider.snapshot(
            match = match,
            observer = AvalonScenario.MERLIN,
            at = afterClaim,
            mode = Perspective.OBSERVER_AT_TIME
        )

        val claimedSeat = snapshot.seatKnowledge.getValue(AvalonScenario.PERCIVAL)
        assertTrue(
            "K08 7 号的标签必须含 CLAIMED_PERCIVAL",
            claimedSeat.labels.contains(KnowledgeLabel.CLAIMED_PERCIVAL)
        )
        assertNull(
            "K08 声称绝不写入 visibleRole（否则就等于承认身份）",
            claimedSeat.visibleRole
        )
        assertNull(
            "K08 声称也不改 knownAlignment",
            claimedSeat.knownAlignment
        )
    }

    @Test
    fun k08_claim_appears_as_claim_fact_and_a08_finding() {
        val at = AvalonScenario.end(match)
        val snapshot = provider.snapshot(
            match = match,
            observer = AvalonScenario.PERCIVAL,
            at = at,
            mode = Perspective.OBSERVER_AT_TIME
        )

        val claimFacts = snapshot.publicFacts.filter {
            it.kind == com.roundtrace.app.core.model.FactKind.CLAIM
        }
        assertEquals("K08 公开事实里应恰好有 1 条声称", 1, claimFacts.size)
        assertTrue(
            "K08 声称摘要应标明这是声称",
            claimFacts.first().summary.contains("声称")
        )

        val findings = analyzer.analyzeAt(
            match = match,
            observer = AvalonScenario.PERCIVAL,
            at = at,
            mode = Perspective.OBSERVER_AT_TIME,
            knowledgeProvider = provider
        )
        val a08 = findings.filter { it.ruleId == AvalonEvidenceAnalyzer.RULE_A08 }
        assertEquals("K08 必须产出 A08_CLAIM_IS_NOT_FACT", 1, a08.size)
        assertTrue(
            "K08 A08 结论里应出现「只是声称」",
            a08.first().label.contains("只是声称")
        )
        assertTrue(
            "K08 A08 必须带证据事件 id",
            a08.first().sourceEventIds.isNotEmpty()
        )
    }
}
