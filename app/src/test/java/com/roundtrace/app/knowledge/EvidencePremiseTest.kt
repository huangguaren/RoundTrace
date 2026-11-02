package com.roundtrace.app.knowledge

import com.roundtrace.app.core.knowledge.AvalonEvidenceAnalyzer
import com.roundtrace.app.core.knowledge.AvalonKnowledgeProvider
import com.roundtrace.app.core.model.EvidenceFinding
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.util.MatchBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NX-01（阶段 A）：修正推论与数据质量——按「证据前提」驱动 A03/A04/A06/A07/A12。
 *
 * 对应 docs/NEXT_ITERATION_IMPLEMENTATION_AI_CODING.md §2 与 docs/decisions.md B34。
 *
 * 三个暴露缺陷的场景（复用 AvalonScenario / MatchBuilder 基建，8 人局）：
 *
 * | 场景 | 任务 | 数据形态 | 期望 |
 * |---|---|---|---|
 * | ① 门槛成功 | 4（门槛 2） | observedOutcome=SUCCESS、failCount=null、队伍完整 | A03 给出门槛解释；A04 不说「失败牌为 0」；A12-fail 以观察结果为准 |
 * | ② 队伍缺失 | 2 / 3 | teamSeats=null；或记录了但人数 ≠ 期望 | A12-team 不再说「不参与计数」，且覆盖人数不完整触发 |
 * | ③ 冲突/畸形 | 4 / 1 | failCount 与 observedOutcome 矛盾；座位越界/重复 | 出现 A12-conflict；A06 带冲突提示；畸形座位抑制依赖名单的推论 |
 */
class EvidencePremiseTest {

    private val provider = AvalonKnowledgeProvider(AvalonScenario.reducer())
    private val analyzer = AvalonEvidenceAnalyzer()

    // ————————————————————————————————————————————————————————
    // 场景构造（每个场景独立对局，互不污染）
    // ————————————————————————————————————————————————————————

    /**
     * 场景①：8 人局第 4 任务（门槛 2）、队伍完整、只记录了「成功」、失败牌数未记。
     * 典型现场：出现 1 张失败牌但未达门槛 2，任务仍成功；用户只记了结果没记牌数。
     */
    private fun thresholdSuccessWithoutFailCount(): MatchBuilder =
        MatchBuilder.eightPlayerMerlinMordred().questCycle(
            questNo = 4,
            leaderSeat = 5,
            teamSeats = listOf(1, 3, 4, 5, 8),
            failCount = null,
            observedOutcome = MissionOutcome.SUCCESS,
            missionTeamSeats = listOf(1, 3, 4, 5, 8)
        )

    /** 场景②a：队伍成员完全未记录，但结果明确为成功。 */
    private fun missionWithNullTeam(): MatchBuilder =
        MatchBuilder.eightPlayerMerlinMordred().questCycle(
            questNo = 2,
            leaderSeat = 3,
            teamSeats = listOf(2, 3, 4, 6),
            failCount = null,
            observedOutcome = MissionOutcome.SUCCESS,
            missionTeamSeats = null
        )

    /** 场景②b：队伍记录了但只有 2 人（期望 4 人），结果明确为失败。 */
    private fun missionWithPartialTeam(): MatchBuilder =
        MatchBuilder.eightPlayerMerlinMordred().questCycle(
            questNo = 3,
            leaderSeat = 4,
            teamSeats = listOf(2, 3, 4, 6),
            failCount = 1,
            observedOutcome = MissionOutcome.FAILED,
            missionTeamSeats = listOf(2, 4)
        )

    /**
     * 场景③冲突：失败牌数 2 ≥ 门槛 2（按牌数应判失败），但记录的观察结果是「成功」。
     */
    private fun conflictingFailCountAndOutcome(): MatchBuilder =
        MatchBuilder.eightPlayerMerlinMordred().questCycle(
            questNo = 4,
            leaderSeat = 5,
            teamSeats = listOf(1, 3, 4, 5, 8),
            failCount = 2,
            observedOutcome = MissionOutcome.SUCCESS,
            missionTeamSeats = listOf(1, 3, 4, 5, 8)
        )

    /**
     * 场景③畸形座位：任务记录的队伍为 [0, 4, 4]——0 号越界、4 号重复
     *（人数 3 恰好等于期望，说明问题只能由座位合法性检查暴露）。
     */
    private fun missionWithMalformedSeats(): MatchBuilder =
        MatchBuilder.eightPlayerMerlinMordred().questCycle(
            questNo = 1,
            leaderSeat = 2,
            teamSeats = listOf(2, 4, 6),
            failCount = 1,
            observedOutcome = MissionOutcome.FAILED,
            missionTeamSeats = listOf(0, 4, 4)
        )

    private fun analyze(builder: MatchBuilder): List<EvidenceFinding> {
        val match = builder.build()
        return analyzer.analyzeAt(
            match = match,
            observer = AvalonScenario.MERLIN,
            at = AvalonScenario.end(match),
            mode = Perspective.OBSERVER_AT_TIME,
            knowledgeProvider = provider
        )
    }

    private fun findingsForQuest(
        findings: List<EvidenceFinding>,
        questNo: Int
    ): List<EvidenceFinding> = findings.filter { it.relatedQuestNo == questNo }

    private fun ruleIdsForQuest(
        findings: List<EvidenceFinding>,
        questNo: Int
    ): Set<String> = findingsForQuest(findings, questNo).map { it.ruleId }.toSet()

    // ————————————————————————————————————————————————————————
    // 场景①：门槛成功 + 失败牌数未知
    // ————————————————————————————————————————————————————————

    @Test
    fun `场景1 门槛成功且牌数未知时 A03 必须出现并按门槛解释`() {
        val findings = analyze(thresholdSuccessWithoutFailCount())

        val a03 = findingsForQuest(findings, questNo = 4)
            .firstOrNull { it.ruleId == AvalonEvidenceAnalyzer.RULE_A03 }
        assertNotNull(
            "缺陷1：failCount=null 且结果为成功时，最需要解释门槛的 A03 反而不触发",
            a03
        )
        assertTrue(
            "A03 必须说明门槛为 2，实际=${a03!!.explanation}",
            a03.explanation.contains("门槛") && a03.explanation.contains("2")
        )
        assertTrue(
            "A03 必须解释「成功只说明失败牌未达门槛」（出现 1 张失败牌仍可能成功），实际=${a03.explanation}",
            a03.explanation.contains("未达门槛")
        )
        assertTrue(
            "A03 应点明 1 张失败牌仍算成功（8 人局第 4 任务门槛 2），实际=${a03.explanation}",
            a03.explanation.contains("1 张失败牌仍算成功")
        )
    }

    @Test
    fun `场景1 A04 不得断言失败牌为 0 应表述为未达门槛`() {
        val findings = analyze(thresholdSuccessWithoutFailCount())

        val a04 = findingsForQuest(findings, questNo = 4)
            .firstOrNull { it.ruleId == AvalonEvidenceAnalyzer.RULE_A04 }
        assertNotNull("A04 在任务成功的适用场景必出现", a04)
        assertFalse(
            "缺陷1：failCount 未记录时「失败牌为 0」是错误断言（可能存在 1 张失败牌只是未达门槛），实际=${a04!!.explanation}",
            a04.explanation.contains("失败牌为 0")
        )
        assertTrue(
            "A04 应表述为「失败牌未达门槛」，实际=${a04.explanation}",
            a04.explanation.contains("未达门槛")
        )
        assertTrue(
            "A04 仍须保留纠错语义：不能把队员标记为已证实好人",
            a04.explanation.contains("不能") && a04.explanation.contains("好人")
        )
    }

    @Test
    fun `场景1 A06 不出现且 A12fail 以记录的观察结果为准`() {
        val findings = analyze(thresholdSuccessWithoutFailCount())
        val quest4 = findingsForQuest(findings, questNo = 4)

        assertFalse(
            "失败牌数未知时不得给出「队伍内至少 f 名坏人」的下界",
            quest4.any { it.ruleId == AvalonEvidenceAnalyzer.RULE_A06 }
        )

        val a12Fail = quest4.firstOrNull { it.id == "A12-fail-4" }
        assertNotNull("失败牌数未记录必须产出 A12-fail", a12Fail)
        assertFalse(
            "缺陷2：结果已记录为成功时，说「无法判断该任务成败」与记录矛盾，实际=${a12Fail!!.explanation}",
            a12Fail.explanation.contains("无法判断")
        )
        assertTrue(
            "A12-fail 应说明「成败以记录的观察结果为准」，实际=${a12Fail.explanation}",
            a12Fail.explanation.contains("以记录的观察结果为准")
        )
    }

    // ————————————————————————————————————————————————————————
    // 场景②：队伍成员缺失 / 不完整
    // ————————————————————————————————————————————————————————

    @Test
    fun `场景2 队伍未记录时 A12team 不得说任务不参与计数`() {
        val findings = analyze(missionWithNullTeam())

        val a12Team = findingsForQuest(findings, questNo = 2)
            .firstOrNull { it.id == "A12-team-2" }
        assertNotNull("teamSeats=null 必须产出 A12-team", a12Team)
        assertFalse(
            "缺陷3：「本任务不参与好人/坏人计数」与 B34（结果明确即计数）直接冲突，实际=${a12Team!!.explanation}",
            a12Team.explanation.contains("不参与好人/坏人计数") ||
                a12Team.explanation.contains("不参与计数")
        )
        assertTrue(
            "A12-team 应说明队员不完整只影响『谁在队伍里』的推论，任务结果仍计入胜负，实际=${a12Team.explanation}",
            a12Team.explanation.contains("仍计入") && a12Team.explanation.contains("推论不可靠")
        )
    }

    @Test
    fun `场景2 队伍记录了但人数不符期望时也触发 A12team`() {
        val findings = analyze(missionWithPartialTeam())

        val a12Team = findingsForQuest(findings, questNo = 3)
            .firstOrNull { it.id == "A12-team-3" }
        assertNotNull(
            "缺陷3：teamSeats 记录了但 size(2) ≠ 期望(4) 属于不完整记录，也应触发 A12-team",
            a12Team
        )
        assertTrue(
            "A12-team 应指出记录人数与期望人数的差距，实际=${a12Team!!.explanation}",
            a12Team.explanation.contains("2") && a12Team.explanation.contains("4")
        )
        assertTrue(
            "人数不完整同样不得宣称不参与计数（B34）",
            !a12Team.explanation.contains("不参与好人/坏人计数")
        )
    }

    // ————————————————————————————————————————————————————————
    // 场景③：失败牌数与记录结果冲突
    // ————————————————————————————————————————————————————————

    @Test
    fun `场景3 牌数与结果冲突时产出 A12conflict 且不新增 ruleId`() {
        val findings = analyze(conflictingFailCountAndOutcome())

        val conflict = findingsForQuest(findings, questNo = 4)
            .firstOrNull { it.id == "A12-conflict-4" }
        assertNotNull(
            "缺陷4：failCount=2 ≥ 门槛 2 但记录为成功，必须产出冲突提示（id=A12-conflict-4）",
            conflict
        )
        assertEquals(
            "冲突提示复用 A12 ruleId，不得新增第 13 条 ruleId（ALL_RULE_IDS 恰好 12 条）",
            AvalonEvidenceAnalyzer.RULE_A12,
            conflict!!.ruleId
        )
        assertTrue(
            "冲突提示必须说明两个记录来源相互矛盾，实际=${conflict.explanation}",
            conflict.explanation.contains("冲突")
        )
        assertTrue(
            "冲突提示必须带源事件 id（输出纪律）",
            conflict.sourceEventIds.isNotEmpty()
        )
    }

    @Test
    fun `场景3 冲突时 A06 保留但必须附带冲突说明`() {
        // 设计选择（B34 + 证据最大化）：A06 不抑制、附带冲突说明。
        // 理由：失败牌数 2 本身是独立于任务结果的直接观察——即使结果记录有误，
        // 「出现过 2 张失败牌 ⇒ 队伍内至少 2 名坏人」仍然成立；抑制会丢掉真实下界。
        // 冲突说明提示用户两个记录必有一误，需人工核对后采信。
        val findings = analyze(conflictingFailCountAndOutcome())

        val a06 = findingsForQuest(findings, questNo = 4)
            .firstOrNull { it.ruleId == AvalonEvidenceAnalyzer.RULE_A06 }
        assertNotNull("冲突场景仍应给出「至少 2 名坏人」的规则下界", a06)
        assertTrue(
            "A06 必须附带冲突说明，实际=${a06!!.explanation}",
            a06.explanation.contains("冲突")
        )
    }

    @Test
    fun `场景3 冲突时 A04 不得与失败牌数自相矛盾`() {
        val findings = analyze(conflictingFailCountAndOutcome())

        val a04 = findingsForQuest(findings, questNo = 4)
            .firstOrNull { it.ruleId == AvalonEvidenceAnalyzer.RULE_A04 }
        assertNotNull("记录为成功 ⇒ A04 适用场景必出现", a04)
        assertFalse(
            "failCount=2 时说「失败牌为 0」与记录直接矛盾，实际=${a04!!.explanation}",
            a04.explanation.contains("失败牌为 0")
        )
        assertTrue(
            "A04 应指出记录之间冲突、需人工核对，实际=${a04.explanation}",
            a04.explanation.contains("冲突")
        )
    }

    // ————————————————————————————————————————————————————————
    // 场景③：畸形座位（越界 / 重复）
    // ————————————————————————————————————————————————————————

    @Test
    fun `场景3 畸形座位抑制依赖座位名单的推论`() {
        val findings = analyze(missionWithMalformedSeats())
        val quest1 = findingsForQuest(findings, questNo = 1)

        assertFalse(
            "缺陷4：队伍名单含 0 号（越界）与重复 4 号时，" +
                "「该队伍内至少 1 名坏人」这类依赖名单的推论必须被抑制",
            quest1.any { it.ruleId == AvalonEvidenceAnalyzer.RULE_A06 }
        )
        val a12Team = quest1.firstOrNull { it.id == "A12-team-1" }
        assertNotNull(
            "畸形座位必须产出 A12-team（座位越界/重复属于数据残缺）",
            a12Team
        )
        assertTrue(
            "A12-team 应指出座位名单异常（越界或重复），实际=${a12Team!!.explanation}",
            a12Team.explanation.contains("越界") || a12Team.explanation.contains("重复")
        )
    }

    @Test
    fun `场景3 畸形座位时现场事实保留`() {
        val findings = analyze(missionWithMalformedSeats())
        val quest1 = findingsForQuest(findings, questNo = 1)

        assertTrue(
            "A03（门槛规则事实）不依赖座位名单，必须保留",
            quest1.any { it.ruleId == AvalonEvidenceAnalyzer.RULE_A03 }
        )
        assertTrue(
            "A07（纠错类：不能定位出牌人）在失败牌数已知时必出现",
            quest1.any { it.ruleId == AvalonEvidenceAnalyzer.RULE_A07 }
        )
        assertFalse(
            "失败牌数已记录（1 张），不得出现 A12-fail",
            quest1.any { it.id == "A12-fail-1" }
        )
    }

    @Test
    fun `A06 非法失败牌数（99张）不给出确定性坏人数量`() {
        val findings = analyze(
            MatchBuilder.eightPlayerMerlinMordred().questCycle(
                questNo = 1,
                leaderSeat = 1,
                teamSeats = listOf(1, 2),
                failCount = 99,
                observedOutcome = MissionOutcome.FAILED,
                missionTeamSeats = listOf(1, 2)
            )
        )
        val quest1 = findingsForQuest(findings, questNo = 1)
        assertFalse(
            "failCount=99 非法，不应给出 A06 确定性结论",
            quest1.any { it.ruleId == AvalonEvidenceAnalyzer.RULE_A06 }
        )
    }

    @Test
    fun `A06 负失败牌数（-1）不给出确定性结论`() {
        val findings = analyze(
            MatchBuilder.eightPlayerMerlinMordred().questCycle(
                questNo = 1,
                leaderSeat = 1,
                teamSeats = listOf(1, 2),
                failCount = -1,
                observedOutcome = MissionOutcome.SUCCESS,
                missionTeamSeats = listOf(1, 2)
            )
        )
        val quest1 = findingsForQuest(findings, questNo = 1)
        assertFalse(
            "failCount=-1 非法，不应给出 A06 确定性结论",
            quest1.any { it.ruleId == AvalonEvidenceAnalyzer.RULE_A06 }
        )
    }
}
