package com.roundtrace.app.knowledge

import com.roundtrace.app.core.knowledge.AvalonEvidenceAnalyzer
import com.roundtrace.app.core.knowledge.AvalonKnowledgeProvider
import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EvidenceFinding
import com.roundtrace.app.core.model.EvidenceLevel
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.GamePosition
import com.roundtrace.app.core.model.KnowledgeSnapshot
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.SeatKnowledge
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.util.MatchBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NX-01-EDGE（阶段 D）：异常输入不得产生伪确定推论（PRD §5、实施文档 §4）。
 *
 * 与 [EvidencePremiseTest] 的分工：
 * - `EvidencePremiseTest` 钉住「单个任务的证据前提」（牌数/名单/冲突）；
 * - 本文件钉住**前提本身非法**时（人数 4/11、任务号 0/6、牌数越界、座位畸形）的行为：
 *   凡依赖非法前提的确定性结论必须被抑制，并留下可追溯的 A12 数据质量说明；
 *   **正常输入（5–10 人、任务 1–5、合法牌数）的推论与文案必须一字不变**。
 *
 * 测试按「分析器纯函数」构造快照与历史（不经过 reducer），
 * 因为非法人数/任务号本来就不该进入正常写入路径，直接用非法输入验证抑制逻辑最准确。
 */
class EvidenceBoundaryTest {

    private val provider = AvalonKnowledgeProvider(AvalonScenario.reducer())
    private val analyzer = AvalonEvidenceAnalyzer()

    // ————————————————————————————————————————————————————————
    // 夹具
    // ————————————————————————————————————————————————————————

    /** 手工构造快照：默认是 8 人局（good=5, evil=3）的公开视角。 */
    private fun snapshot(
        playerCount: Int = 8,
        goodCount: Int = 5,
        evilCount: Int = 3,
        mode: Perspective = Perspective.PUBLIC,
        observerSeat: Int? = null,
        observerRole: RoleId? = null,
        seatKnowledge: Map<Int, SeatKnowledge> = emptyMap()
    ): KnowledgeSnapshot = KnowledgeSnapshot(
        at = GamePosition(occurredOrder = 900, questNo = null, proposalNo = null),
        mode = mode,
        observerSeat = observerSeat,
        observerRole = observerRole,
        observerRoleIsReconstructed = false,
        publicFacts = emptyList(),
        privateRoleFacts = emptyList(),
        subjectiveBeliefs = emptyList(),
        seatKnowledge = seatKnowledge,
        findings = emptyList(),
        unknowns = emptyList(),
        canOpenGodView = false,
        playerCount = playerCount,
        goodCount = goodCount,
        evilCount = evilCount
    )

    private fun seat(
        seat: Int,
        alignment: Alignment?
    ): Pair<Int, SeatKnowledge> = seat to SeatKnowledge(
        seat = seat,
        knownAlignment = alignment,
        visibleRole = null,
        candidateRoles = emptyList(),
        labels = emptySet()
    )

    private fun proposal(
        questNo: Int,
        proposalNo: Int = 1,
        leaderSeat: Int = 1,
        teamSeats: List<Int>
    ): TeamProposed = TeamProposed(
        eventId = EventId("p-$questNo-$proposalNo"),
        occurredOrder = questNo * 10 + proposalNo,
        recordedAt = questNo * 1_000L,
        visibility = EventVisibility.PUBLIC,
        questNo = questNo,
        proposalNo = proposalNo,
        leaderSeat = leaderSeat,
        teamSeats = teamSeats
    )

    private fun mission(
        questNo: Int,
        teamSeats: List<Int>?,
        failCount: Int?,
        observedOutcome: MissionOutcome?,
        teamSizeExpected: Int
    ): MissionResolved = MissionResolved(
        eventId = EventId("m-$questNo"),
        occurredOrder = questNo * 10 + 5,
        recordedAt = questNo * 1_000L,
        visibility = EventVisibility.PUBLIC,
        questNo = questNo,
        teamSeats = teamSeats,
        teamSizeExpected = teamSizeExpected,
        failCount = failCount,
        observedOutcome = observedOutcome
    )

    private fun analyze(
        snapshot: KnowledgeSnapshot,
        vararg events: MatchEvent
    ): List<EvidenceFinding> = analyzer.analyze(snapshot, events.sortedBy { it.occurredOrder })

    private fun List<EvidenceFinding>.ofRule(ruleId: String): List<EvidenceFinding> =
        filter { it.ruleId == ruleId }

    private fun List<EvidenceFinding>.dataQuality(): List<EvidenceFinding> =
        filter { it.id.startsWith("A12-dq-") }

    // ————————————————————————————————————————————————————————
    // 1) 非法玩家数（4 / 11）
    // ————————————————————————————————————————————————————————

    @Test
    fun `非法玩家数 4 抑制数量与门槛结论并给出数据质量说明`() {
        val snapshot = snapshot(playerCount = 4, goodCount = 0, evilCount = 0)
        val findings = analyze(
            snapshot,
            proposal(questNo = 1, teamSeats = listOf(1, 2)),
            mission(
                questNo = 1,
                teamSeats = listOf(1, 2),
                failCount = 1,
                observedOutcome = MissionOutcome.FAILED,
                teamSizeExpected = 2
            )
        )

        assertTrue(
            "人数 4 不在 5..10，A01「队伍应为 N 人」不得出现",
            findings.ofRule(AvalonEvidenceAnalyzer.RULE_A01).isEmpty()
        )
        assertTrue(
            "人数非法时 A03 的门槛结论不得出现",
            findings.ofRule(AvalonEvidenceAnalyzer.RULE_A03).isEmpty()
        )
        assertTrue(
            "缺陷：goodCount=0 时 bounds 会算出「至少含 2 名坏人」（整队都是坏人），" +
                "这是不可能的 RULE_CERTAIN，必须抑制。实际=" +
                findings.ofRule(AvalonEvidenceAnalyzer.RULE_A05).map { it.label },
            findings.ofRule(AvalonEvidenceAnalyzer.RULE_A05).isEmpty()
        )
        assertTrue(
            "必须留下可追溯的数据质量说明（A12-dq）",
            findings.dataQuality().isNotEmpty()
        )
        assertTrue(
            "数据质量说明必须点明人数越界，实际=${findings.dataQuality().map { it.label }}",
            findings.dataQuality().any { it.label.contains("4") && it.label.contains("5") }
        )
        assertTrue(
            "A12 数据质量提示必须是 UNKNOWN 等级",
            findings.dataQuality().all { it.level == EvidenceLevel.UNKNOWN }
        )
        assertTrue(
            "数据质量说明必须复用 A12（不新增 ruleId）",
            findings.dataQuality().all { it.ruleId == AvalonEvidenceAnalyzer.RULE_A12 }
        )
    }

    @Test
    fun `非法玩家数 11 抑制数量与门槛结论`() {
        val snapshot = snapshot(playerCount = 11, goodCount = 0, evilCount = 0)
        val findings = analyze(
            snapshot,
            proposal(questNo = 1, teamSeats = listOf(1, 2, 3)),
            mission(
                questNo = 1,
                teamSeats = listOf(1, 2, 3),
                failCount = 1,
                observedOutcome = MissionOutcome.FAILED,
                teamSizeExpected = 3
            )
        )

        assertTrue(findings.ofRule(AvalonEvidenceAnalyzer.RULE_A01).isEmpty())
        assertTrue(findings.ofRule(AvalonEvidenceAnalyzer.RULE_A03).isEmpty())
        assertTrue(
            "人数 11 时 goodCount=0，A05 会声称整队都是坏人；必须抑制",
            findings.ofRule(AvalonEvidenceAnalyzer.RULE_A05).isEmpty()
        )
        assertTrue(findings.dataQuality().isNotEmpty())
    }

    // ————————————————————————————————————————————————————————
    // 2) 非法任务号（0 / 6）
    // ————————————————————————————————————————————————————————

    @Test
    fun `任务号 0 抑制门槛与数量结论`() {
        val findings = analyze(
            snapshot(),
            proposal(questNo = 0, teamSeats = listOf(1, 2, 3)),
            mission(
                questNo = 0,
                teamSeats = listOf(1, 2, 3),
                failCount = 1,
                observedOutcome = MissionOutcome.FAILED,
                teamSizeExpected = -1
            )
        )

        assertTrue(
            "任务 0 不在 1..5，A01 不得出现",
            findings.ofRule(AvalonEvidenceAnalyzer.RULE_A01).isEmpty()
        )
        assertTrue(
            "缺陷：failThreshold 对任何任务号都返回 1，A03 会对任务 0 输出「需 ≥1 张失败牌」；必须抑制",
            findings.ofRule(AvalonEvidenceAnalyzer.RULE_A03).isEmpty()
        )
        assertTrue(findings.ofRule(AvalonEvidenceAnalyzer.RULE_A05).isEmpty())
        assertTrue(
            "必须说明任务号越界，实际=${findings.dataQuality().map { it.label }}",
            findings.dataQuality().any { it.label.contains("任务号") || it.label.contains("任务") }
        )
    }

    @Test
    fun `任务号 6 抑制门槛与数量结论`() {
        val findings = analyze(
            snapshot(),
            proposal(questNo = 6, teamSeats = listOf(1, 2, 3)),
            mission(
                questNo = 6,
                teamSeats = listOf(1, 2, 3),
                failCount = 2,
                observedOutcome = MissionOutcome.FAILED,
                teamSizeExpected = -1
            )
        )

        assertTrue(findings.ofRule(AvalonEvidenceAnalyzer.RULE_A01).isEmpty())
        assertTrue(findings.ofRule(AvalonEvidenceAnalyzer.RULE_A03).isEmpty())
        assertTrue(findings.ofRule(AvalonEvidenceAnalyzer.RULE_A05).isEmpty())
        assertTrue(findings.dataQuality().isNotEmpty())
    }

    // ————————————————————————————————————————————————————————
    // 3) 非法失败牌数（99 / -1 / 超过队伍人数）
    // ————————————————————————————————————————————————————————

    @Test
    fun `失败牌数超出队伍人数时抑制坏人数下界`() {
        val findings = analyze(
            snapshot(),
            proposal(questNo = 1, teamSeats = listOf(1, 2, 3)),
            mission(
                questNo = 1,
                teamSeats = listOf(1, 2, 3),
                failCount = 4, // 队伍只有 3 人
                observedOutcome = MissionOutcome.FAILED,
                teamSizeExpected = 3
            )
        )

        assertTrue(
            "failCount=4 > 队伍 3 人：不得声称「至少 4 名坏人」",
            findings.ofRule(AvalonEvidenceAnalyzer.RULE_A06).isEmpty()
        )
        val dq = findings.dataQuality().firstOrNull { it.id.startsWith("A12-dq-failcount-") }
        assertNotNull("失败牌数越界必须留下数据质量说明", dq)
        assertTrue(
            "说明里必须同时给出牌数与队伍规模，实际=${dq!!.explanation}",
            dq.explanation.contains("4") && dq.explanation.contains("3")
        )
        assertTrue(
            "说明必须说明任务胜负计数仍按明确结果推进（B34）",
            dq.explanation.contains("B34")
        )
    }

    @Test
    fun `负数与超大失败牌数都不产生确定性坏人数`() {
        // 表驱动：-1 与 99 都超出 0..队伍规模
        listOf(-1, 99).forEach { failCount ->
            val findings = analyze(
                snapshot(),
                proposal(questNo = 1, teamSeats = listOf(1, 2, 3)),
                mission(
                    questNo = 1,
                    teamSeats = listOf(1, 2, 3),
                    failCount = failCount,
                    observedOutcome = if (failCount > 0) MissionOutcome.FAILED else MissionOutcome.SUCCESS,
                    teamSizeExpected = 3
                )
            )
            assertTrue(
                "failCount=$failCount 非法，A06 不得出现",
                findings.ofRule(AvalonEvidenceAnalyzer.RULE_A06).isEmpty()
            )
            assertTrue(
                "failCount=$failCount 必须留下 A12 数据质量说明",
                findings.dataQuality().any { it.id.startsWith("A12-dq-failcount-") }
            )
        }
    }

    // ————————————————————————————————————————————————————————
    // 4) 座位畸形（越界 / 重复）
    // ————————————————————————————————————————————————————————

    @Test
    fun `提案座位越界或重复时抑制 A05 并说明原因`() {
        // 观察者视角：已知 3 号是坏人；提案里 3 号重复出现且含越界座位 0
        val seatKnowledge = mapOf(seat(1, Alignment.GOOD), seat(3, Alignment.EVIL))
        val findings = analyze(
            snapshot(
                mode = Perspective.OBSERVER_AT_TIME,
                observerSeat = 1,
                observerRole = RoleId.MERLIN,
                seatKnowledge = seatKnowledge
            ),
            proposal(questNo = 1, teamSeats = listOf(3, 3, 0))
        )

        assertTrue(
            "缺陷：提案座位畸形时 bounds 仍会给出「至少含 N 名坏人」，必须抑制；实际=" +
                findings.ofRule(AvalonEvidenceAnalyzer.RULE_A05).map { it.label },
            findings.ofRule(AvalonEvidenceAnalyzer.RULE_A05).isEmpty()
        )
        val dq = findings.dataQuality().firstOrNull { it.id.startsWith("A12-dq-proposal-seats-") }
        assertNotNull("提案座位畸形必须留下数据质量说明", dq)
        assertTrue(
            "说明必须点出越界或重复，实际=${dq!!.explanation}",
            dq.explanation.contains("越界") || dq.explanation.contains("重复")
        )
    }

    @Test
    fun `任务队伍座位畸形时抑制 A06 并保留现场事实`() {
        val findings = analyze(
            snapshot(),
            proposal(questNo = 1, teamSeats = listOf(1, 2, 3)),
            mission(
                questNo = 1,
                teamSeats = listOf(1, 1, 0), // 重复 + 越界
                failCount = 1,
                observedOutcome = MissionOutcome.FAILED,
                teamSizeExpected = 3
            )
        )

        assertTrue(
            "队伍座位畸形时 A06 下界必须抑制",
            findings.ofRule(AvalonEvidenceAnalyzer.RULE_A06).isEmpty()
        )
        assertTrue(
            "A12-team 必须说明座位异常",
            findings.any { it.id == "A12-team-1" && it.ruleId == AvalonEvidenceAnalyzer.RULE_A12 }
        )
        assertTrue(
            "A07（不能定位出牌人）不依赖名单合法性，仍须出现",
            findings.ofRule(AvalonEvidenceAnalyzer.RULE_A07).isNotEmpty()
        )
        assertTrue(
            "A03（门槛，不依赖名单）在合法人数/任务号下必须保留",
            findings.ofRule(AvalonEvidenceAnalyzer.RULE_A03).isNotEmpty()
        )
    }

    // ————————————————————————————————————————————————————————
    // 5) 队伍少人 / 多人 / 缺失
    // ————————————————————————————————————————————————————————

    @Test
    fun `队伍少人与多人都触发 A12team 且下界不超过实际人数`() {
        val cases = listOf(
            "少人" to listOf(1, 2),
            "多人" to listOf(1, 2, 3, 4, 5, 6)
        )
        cases.forEach { (label, seats) ->
            val findings = analyze(
                snapshot(),
                proposal(questNo = 1, teamSeats = listOf(1, 2, 3)),
                mission(
                    questNo = 1,
                    teamSeats = seats,
                    failCount = 1,
                    observedOutcome = MissionOutcome.FAILED,
                    teamSizeExpected = 3
                )
            )
            assertTrue(
                "$label：必须触发 A12-team",
                findings.any { it.id == "A12-team-1" }
            )
            val a06 = findings.ofRule(AvalonEvidenceAnalyzer.RULE_A06).firstOrNull()
            if (a06 != null) {
                assertTrue(
                    "$label：下界（1 张失败牌）不得大于实际队伍人数 ${seats.size}，实际=${a06.label}",
                    a06.label.contains("至少有 1 名")
                )
                assertTrue(
                    "$label：relatedSeats 必须来自记录名单且不超过人数",
                    a06.relatedSeats.size <= seats.size
                )
            }
        }
    }

    @Test
    fun `队伍完全缺失时不下结论但仍保留门槛与纠错`() {
        val findings = analyze(
            snapshot(),
            proposal(questNo = 1, teamSeats = listOf(1, 2, 3)),
            mission(
                questNo = 1,
                teamSeats = null,
                failCount = null,
                observedOutcome = MissionOutcome.SUCCESS,
                teamSizeExpected = 3
            )
        )

        assertTrue(
            "A12-team 必须说明队伍未记录",
            findings.any { it.id == "A12-team-1" }
        )
        assertTrue(
            "A12-fail 必须说明牌数未记录",
            findings.any { it.id == "A12-fail-1" }
        )
        assertTrue(
            "失败牌数未知时不得给出坏人数下界",
            findings.ofRule(AvalonEvidenceAnalyzer.RULE_A06).isEmpty()
        )
        assertTrue(
            "结果明确为成功时 A04 必须出现（纠错类）",
            findings.ofRule(AvalonEvidenceAnalyzer.RULE_A04).isNotEmpty()
        )
    }

    // ————————————————————————————————————————————————————————
    // 6) failCount 与 observedOutcome 冲突
    // ————————————————————————————————————————————————————————

    @Test
    fun `牌数与观察结果冲突时给出 A12conflict 且牌数为 0 也不越权`() {
        // failCount=0（按门槛应为成功）但记录为失败 → 冲突
        val findings = analyze(
            snapshot(),
            proposal(questNo = 2, teamSeats = listOf(1, 2, 3, 4)),
            mission(
                questNo = 2,
                teamSeats = listOf(1, 2, 3, 4),
                failCount = 0,
                observedOutcome = MissionOutcome.FAILED,
                teamSizeExpected = 4
            )
        )

        val conflict = findings.firstOrNull { it.id == "A12-conflict-2" }
        assertNotNull("牌数 0 与记录结果「失败」冲突，必须产出 A12-conflict-2", conflict)
        assertEquals(AvalonEvidenceAnalyzer.RULE_A12, conflict!!.ruleId)
        assertEquals(EvidenceLevel.UNKNOWN, conflict.level)
        assertFalse(
            "冲突时不得声称「失败牌为 0 所以任务成功」，实际=${conflict.explanation}",
            conflict.explanation.contains("所以任务成功")
        )
        assertTrue(
            "A06 在冲突时不得出现「至少 0 名坏人」这种无信息量的确定性结论",
            findings.ofRule(AvalonEvidenceAnalyzer.RULE_A06).isEmpty()
        )
    }

    // ————————————————————————————————————————————————————————
    // 7) 7–10 人局第 4 任务：1 张失败牌仍成功（门槛 2）
    // ————————————————————————————————————————————————————————

    @Test
    fun `八人局第四任务一张失败牌仍成功且门槛为 2`() {
        val findings = analyze(
            snapshot(playerCount = 8, goodCount = 5, evilCount = 3),
            proposal(questNo = 4, teamSeats = listOf(1, 3, 4, 5, 8)),
            mission(
                questNo = 4,
                teamSeats = listOf(1, 3, 4, 5, 8),
                failCount = 1,
                observedOutcome = MissionOutcome.SUCCESS,
                teamSizeExpected = 5
            )
        )

        val a03 = findings.ofRule(AvalonEvidenceAnalyzer.RULE_A03).firstOrNull()
        assertNotNull("门槛结论必须出现", a03)
        assertTrue("门槛必须为 2，实际=${a03!!.label}", a03.label.contains("2"))

        assertTrue(
            "1 张失败牌未达门槛 2 → 不得产出冲突提示（A12-conflict-4）",
            findings.none { it.id == "A12-conflict-4" }
        )
        assertTrue(
            "结果明确成功 → A04 必须出现",
            findings.ofRule(AvalonEvidenceAnalyzer.RULE_A04).isNotEmpty()
        )
        val a06 = findings.ofRule(AvalonEvidenceAnalyzer.RULE_A06).firstOrNull()
        assertNotNull("1 张失败牌仍是有效下界：队伍内至少 1 名坏人", a06)
        assertTrue(
            "A06 不得把下界说成 2（门槛与失败牌数是两件事），实际=${a06!!.label}",
            a06.label.contains("至少有 1 名")
        )
    }

    // ————————————————————————————————————————————————————————
    // 8) 牌数缺失但结果已知；结果明确仍计胜负（B34）
    // ————————————————————————————————————————————————————————

    @Test
    fun `牌数缺失但结果已知时按结果解释且不推导下界`() {
        val findings = analyze(
            snapshot(),
            proposal(questNo = 2, teamSeats = listOf(2, 3, 4, 6)),
            mission(
                questNo = 2,
                teamSeats = listOf(2, 3, 4, 6),
                failCount = null,
                observedOutcome = MissionOutcome.FAILED,
                teamSizeExpected = 4
            )
        )

        val a12Fail = findings.firstOrNull { it.id == "A12-fail-2" }
        assertNotNull(a12Fail)
        assertTrue(
            "结果已知时必须说明「成败以记录的观察结果为准」，实际=${a12Fail!!.explanation}",
            a12Fail.explanation.contains("以记录的观察结果为准")
        )
        assertFalse(
            "结果已记录为失败时不得说「无法判断该任务成败」",
            a12Fail.explanation.contains("无法判断")
        )
        assertTrue(
            "牌数未知 → 不得给出坏人数下界",
            findings.ofRule(AvalonEvidenceAnalyzer.RULE_A06).isEmpty()
        )
    }

    @Test
    fun `结果明确时即使队伍与牌数缺失仍计入胜负计数`() {
        // B34：verified 只表示可复核性，不阻断计数
        val builder = MatchBuilder.eightPlayerMerlinMordred().questCycle(
            questNo = 1,
            leaderSeat = 1,
            teamSeats = listOf(1, 2, 3),
            failCount = null,
            observedOutcome = MissionOutcome.SUCCESS,
            missionTeamSeats = null
        )
        val state = builder.replay()
        assertEquals(
            "结果明确为成功 → 好人成功计数必须为 1（B34）",
            1,
            state.goodSuccessCount
        )
        assertEquals(0, state.evilFailureCount)
    }

    // ————————————————————————————————————————————————————————
    // 9) 正常输入不受影响（防「为了抑制而过度抑制」）
    // ————————————————————————————————————————————————————————

    @Test
    fun `正常输入下 A01 A03 A05 A06 文案与既有行为一致`() {
        // 复用既有 K07 场景（8 人局第 5 任务），确认阶段 D 的闸门没有误伤正常路径
        val match = AvalonScenario.match(revealed = false)
        val findings = analyzer.analyzeAt(
            match = match,
            observer = AvalonScenario.MERLIN,
            at = AvalonScenario.end(match),
            mode = Perspective.OBSERVER_AT_TIME,
            knowledgeProvider = provider
        )

        assertTrue(
            "正常输入不得出现任何数据质量提示（A12-dq）",
            findings.dataQuality().isEmpty()
        )
        assertTrue("A01 应正常出现", findings.ofRule(AvalonEvidenceAnalyzer.RULE_A01).isNotEmpty())

        val a05 = findings.ofRule(AvalonEvidenceAnalyzer.RULE_A05)
            .firstOrNull { it.relatedQuestNo == 5 }
        assertNotNull("K07：正常输入下 A05 必须仍然出现", a05)
        assertTrue(
            "K07 文案必须保持「至少含 1 名坏人」，实际=${a05!!.label}",
            a05.label.contains("至少含 1 名坏人")
        )
        assertEquals(EvidenceLevel.OBSERVER_CERTAIN, a05.level)
        assertTrue(
            "A05 的确定性等级不得被降低",
            findings.ofRule(AvalonEvidenceAnalyzer.RULE_A05)
                .all { it.level == EvidenceLevel.OBSERVER_CERTAIN }
        )
        assertEquals(
            "ALL_RULE_IDS 必须恰好 12 条（未新增 ruleId）",
            12,
            AvalonEvidenceAnalyzer.ALL_RULE_IDS.size
        )
        assertEquals(
            "ALL_RULE_IDS 不得重复",
            AvalonEvidenceAnalyzer.ALL_RULE_IDS.distinct().size,
            AvalonEvidenceAnalyzer.ALL_RULE_IDS.size
        )
    }

    @Test
    fun `结论 id 在非法输入下仍然唯一`() {
        val findings = analyze(
            snapshot(playerCount = 4, goodCount = 0, evilCount = 0),
            proposal(questNo = 1, proposalNo = 1, teamSeats = listOf(1, 2)),
            proposal(questNo = 1, proposalNo = 2, teamSeats = listOf(1, 1)),
            mission(
                questNo = 1,
                teamSeats = listOf(1, 2),
                failCount = 99,
                observedOutcome = MissionOutcome.FAILED,
                teamSizeExpected = 2
            )
        )
        assertEquals(
            "输出纪律：结论 id 不得重复，实际=${findings.map { it.id }}",
            findings.map { it.id }.distinct().size,
            findings.size
        )
        assertTrue(
            "输出纪律：每条结论都必须带非空 ruleId",
            findings.all { it.ruleId.isNotBlank() }
        )
        assertTrue(
            "输出纪律：不得出现百分比概率",
            findings.none { it.label.contains("%") || it.explanation.contains("%") }
        )
    }
}
