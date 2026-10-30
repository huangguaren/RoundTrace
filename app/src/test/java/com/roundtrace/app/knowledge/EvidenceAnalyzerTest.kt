package com.roundtrace.app.knowledge

import com.roundtrace.app.core.knowledge.AvalonEvidenceAnalyzer
import com.roundtrace.app.core.knowledge.AvalonKnowledgeProvider
import com.roundtrace.app.core.knowledge.bounds
import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.EvidenceLevel
import com.roundtrace.app.core.model.Perspective
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K04 / K05 / K06 / K07 —— 证据分析的「不能过度推断」四条。
 *
 * 这四条是产品差异化的核心：市面上的工具大多在这里翻车
 * （把「任务成功」当成「队员全好」，把「失败牌」当成「某人出牌」）。
 */
class EvidenceAnalyzerTest {

    private val provider = AvalonKnowledgeProvider(AvalonScenario.reducer())
    private val analyzer = AvalonEvidenceAnalyzer()
    private val match = AvalonScenario.match()

    private fun findingsAfterQuest(
        observer: Int,
        questNo: Int
    ) = analyzer.analyzeAt(
        match = match,
        observer = observer,
        at = AvalonScenario.afterQuest(match, questNo),
        mode = Perspective.OBSERVER_AT_TIME,
        knowledgeProvider = provider
    )

    // ————————————————————————————————————————————————————————
    // K04：任务成功不洗白
    // ————————————————————————————————————————————————————————

    @Test
    fun k04_mission_success_does_not_clear_seat_4() {
        val findings = findingsAfterQuest(AvalonScenario.MERLIN, questNo = 1)

        assertTrue(
            "K04 必须产出 A04_SUCCESS_NOT_CLEAR（纠错类）",
            findings.any { it.ruleId == AvalonEvidenceAnalyzer.RULE_A04 }
        )
        val a04 = findings.first { it.ruleId == AvalonEvidenceAnalyzer.RULE_A04 }
        assertEquals(
            "K04 A04 必须是规则确定级",
            EvidenceLevel.RULE_CERTAIN,
            a04.level
        )
        assertTrue(
            "K04 A04 的解释必须点明「不能标记为已证实好人」",
            a04.explanation.contains("不能") && a04.explanation.contains("好人")
        )

        // 4 号在第一轮成功队伍里，但梅林仍不知道他的阵营
        val snapshot = provider.snapshot(
            match = match,
            observer = AvalonScenario.MERLIN,
            at = AvalonScenario.afterQuest(match, 1),
            mode = Perspective.OBSERVER_AT_TIME
        )
        assertEquals(
            "K04 任务 1 成功后 4 号阵营仍必须未知（他是莫德雷德，梅林看不见）",
            null,
            snapshot.seatKnowledge.getValue(4).knownAlignment
        )
        assertFalse(
            "K04 绝不能出现「4 号已被证实好人」这类结论",
            findings.any { it.label.contains("4 号") && it.label.contains("好人") }
        )
    }

    // ————————————————————————————————————————————————————————
    // K05：失败牌不能定位到人
    // ————————————————————————————————————————————————————————

    @Test
    fun k05_one_fail_card_does_not_accuse_seat_3_specifically() {
        val findings = findingsAfterQuest(AvalonScenario.MERLIN, questNo = 2)

        assertTrue(
            "K05 必须产出 A07_CANNOT_PINPOINT_FAIL_VOTER",
            findings.any { it.ruleId == AvalonEvidenceAnalyzer.RULE_A07 }
        )
        val a07 = findings.first { it.ruleId == AvalonEvidenceAnalyzer.RULE_A07 }
        assertEquals("K05 A07 的等级必须是 UNKNOWN", EvidenceLevel.UNKNOWN, a07.level)
        assertTrue(
            "K05 A07 解释必须说明「只记录公开失败牌总数」",
            a07.explanation.contains("失败牌")
        )

        assertFalse(
            "K05 绝不能断言「3 号必然出失败牌」",
            findings.any {
                it.label.contains("3 号") && it.label.contains("失败牌") &&
                    it.level != EvidenceLevel.UNKNOWN
            }
        )
        // A06 只能说「至少 k 名坏人」，不能点名
        val a06 = findings.filter { it.ruleId == AvalonEvidenceAnalyzer.RULE_A06 }
        assertTrue("K05 应产出 A06（队伍内至少 k 名坏人）", a06.isNotEmpty())
        assertTrue(
            "K05 A06 的措辞必须是「至少」而不是指认某人",
            a06.all { it.label.contains("至少") }
        )
    }

    // ————————————————————————————————————————————————————————
    // K06：第 4 任务双失败 → 存在另一名隐藏坏人，但不能单锁 4 号
    // ————————————————————————————————————————————————————————

    @Test
    fun k06_two_fail_cards_imply_another_hidden_evil_not_seat_4() {
        val findings = findingsAfterQuest(AvalonScenario.MERLIN, questNo = 4)

        val a06 = findings.filter {
            it.ruleId == AvalonEvidenceAnalyzer.RULE_A06 && it.relatedQuestNo == 4
        }
        assertTrue("K06 第 4 任务 2 张失败牌应产出 A06", a06.isNotEmpty())
        assertTrue(
            "K06 A06 必须给出「至少有 2 名坏人」——这正是「除 3 号之外还有另一名隐藏坏人」，实际=${a06.first().label}",
            a06.first().label.contains("至少") && a06.first().label.contains("2 名")
        )

        assertTrue(
            "K06 必须同时产出 A07（不能定位到人）",
            findings.any {
                it.ruleId == AvalonEvidenceAnalyzer.RULE_A07 && it.relatedQuestNo == 4
            }
        )
        assertFalse(
            "K06 绝不能无证据单锁 4 号是坏人",
            findings.any {
                it.relatedSeats == listOf(4) &&
                    (it.level == EvidenceLevel.RULE_CERTAIN ||
                        it.level == EvidenceLevel.OBSERVER_CERTAIN) &&
                    it.label.contains("坏人")
            }
        )
        // 梅林夜间只知道 3、5 是坏人，4 号（莫德雷德）不在其中
        val a11Seats = findings
            .filter { it.ruleId == AvalonEvidenceAnalyzer.RULE_A11 }
            .flatMap { it.relatedSeats }
            .sorted()
        assertEquals("K06 夜间确定的坏人只能是 3、5", listOf(3, 5), a11Seats)
    }

    // ————————————————————————————————————————————————————————
    // K07：第五轮 5 人队 → 至少 1 名坏人，但不知是谁
    // ————————————————————————————————————————————————————————

    @Test
    fun k07_bounds_self_check_min_evil_is_one() {
        // 与 ARCHITECTURE §7.4 自检用例逐参数一致
        val result = bounds(
            teamSeats = listOf(2, 4, 6, 7, 8),
            goodCount = 5,
            evilCount = 3,
            knownGood = setOf(1),
            knownEvil = setOf(3, 5)
        )

        assertEquals("K07 minEvil 必须为 1", 1, result.minEvil)
        assertTrue("K07 atLeastOneEvil 必须为 true", result.atLeastOneEvil)
        assertEquals("K07 maxEvil 应为 min(3, 5-0)=3", 3, result.maxEvil)
    }

    @Test
    fun k07_fifth_quest_team_contains_evil_but_cannot_name_who() {
        val findings = findingsAfterQuest(AvalonScenario.MERLIN, questNo = 5)

        val a05 = findings.filter {
            it.ruleId == AvalonEvidenceAnalyzer.RULE_A05 && it.relatedQuestNo == 5
        }
        assertTrue("K07 第 5 任务应产出 A05", a05.isNotEmpty())
        val finding = a05.first()
        assertTrue(
            "K07 A05 必须说「至少含 1 名坏人」，实际=${finding.label}",
            finding.label.contains("至少含 1 名坏人")
        )
        assertEquals(
            "K07 观察者角色已知时，等级应为「本人可知」",
            EvidenceLevel.OBSERVER_CERTAIN,
            finding.level
        )
        assertTrue(
            "K07 A05 必须带证据事件 id",
            finding.sourceEventIds.isNotEmpty()
        )
        assertFalse(
            "K07 不能凭这条直接得知是谁",
            finding.label.contains("4 号是") || finding.label.contains("是 4 号")
        )
    }

    // ————————————————————————————————————————————————————————
    // 输出纪律（T52③④⑤）
    // ————————————————————————————————————————————————————————

    @Test
    fun output_discipline_every_finding_has_rule_id_and_evidence() {
        val findings = analyzer.analyzeAt(
            match = match,
            observer = AvalonScenario.MERLIN,
            at = AvalonScenario.end(match),
            mode = Perspective.OBSERVER_AT_TIME,
            knowledgeProvider = provider
        )

        assertTrue("必须产出结论", findings.isNotEmpty())
        assertTrue(
            "每条结论都必须有非空 ruleId",
            findings.all { it.ruleId.isNotBlank() }
        )
        val inferenceWithoutEvidence = findings.filter {
            it.level == EvidenceLevel.INFERENCE && it.sourceEventIds.isEmpty()
        }
        assertTrue(
            "无证据的推测必须说明「无直接证据」，实际=${inferenceWithoutEvidence.map { it.label }}",
            inferenceWithoutEvidence.all { it.explanation.contains("无直接证据") || it.explanation.contains("人工核对") }
        )
        assertTrue(
            "禁止输出百分比概率（不得出现 % 号）",
            findings.none { it.label.contains("%") || it.explanation.contains("%") }
        )
        assertTrue(
            "结论 id 不得重复",
            findings.map { it.id }.distinct().size == findings.size
        )
    }

    @Test
    fun a12_reports_missing_data_instead_of_guessing() {
        val findings = findingsAfterQuest(AvalonScenario.MERLIN, questNo = 5)

        assertTrue(
            "第 5 任务失败牌未记录时必须产出 A12_DATA_INCOMPLETE",
            findings.any {
                it.ruleId == AvalonEvidenceAnalyzer.RULE_A12 && it.relatedQuestNo == 5
            }
        )
        assertTrue(
            "A12 必须是 UNKNOWN 等级",
            findings.filter { it.ruleId == AvalonEvidenceAnalyzer.RULE_A12 }
                .all { it.level == EvidenceLevel.UNKNOWN }
        )
    }

    @Test
    fun bounds_never_exceed_team_size_or_matrix() {
        // 极端输入自洽性：已知队伍里全是坏人
        val result = bounds(
            teamSeats = listOf(3, 4, 5),
            goodCount = 5,
            evilCount = 3,
            knownGood = setOf(1, 2),
            knownEvil = setOf(3, 4, 5)
        )
        assertEquals("下界应等于已知坏人数", 3, result.minEvil)
        assertEquals("上界不得超队伍人数", 3, result.maxEvil)
        assertFalse("全坏队伍不应被判定为全好", result.allGood)
    }

    // ————————————————————————————————————————————————————————
    // 12 条 ruleId 全覆盖（T52②：「实现 §7.5 全部 12 条」的证据）
    // ————————————————————————————————————————————————————————

    @Test
    fun all_twelve_rule_ids_are_implemented_and_reachable() {
        // ① 主场景覆盖 A01/A03/A04/A05/A06/A07/A08/A11/A12
        val mainFindings = analyzer.analyzeAt(
            match = match,
            observer = AvalonScenario.MERLIN,
            at = AvalonScenario.end(match),
            mode = Perspective.OBSERVER_AT_TIME,
            knowledgeProvider = provider
        )
        val mainRules = mainFindings.map { it.ruleId }.toSet()

        // ② 构造一个「提案被否决 + 队长座位越界」的小场景，补 A02/A09/A10
        val extraBuilder = com.roundtrace.app.util.MatchBuilder.eightPlayerMerlinMordred()
            .questStarted(1, 1)
            .teamProposed(1, 1, leaderSeat = 99, teamSeats = listOf(1, 2, 3))
            .teamVoteRecorded(1, 1, approveCount = 2, rejectCount = 6)
            .teamProposalResolved(
                1,
                1,
                com.roundtrace.app.core.model.ProposalDecision.REJECTED,
                approveCount = 2,
                rejectCount = 6
            )
        val extraMatch = extraBuilder.build()
        val extraFindings = analyzer.analyzeAt(
            match = extraMatch,
            observer = AvalonScenario.MERLIN,
            at = AvalonScenario.end(extraMatch),
            mode = Perspective.OBSERVER_AT_TIME,
            knowledgeProvider = provider
        )
        val allRules = mainRules + extraFindings.map { it.ruleId }

        val missing = AvalonEvidenceAnalyzer.ALL_RULE_IDS.filterNot { allRules.contains(it) }
        assertTrue(
            "§7.5 的 12 条 ruleId 必须全部可达，缺失=$missing（实际=$allRules）",
            missing.isEmpty()
        )
        assertEquals(
            "ALL_RULE_IDS 必须恰好 12 条",
            12,
            AvalonEvidenceAnalyzer.ALL_RULE_IDS.size
        )
    }
}
