package com.roundtrace.app.core.replay

import com.roundtrace.app.core.model.BeliefType
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteChoice
import com.roundtrace.app.util.MatchBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玩家行为时间线纯函数测试（F05-01 Phase A）。
 *
 * 设计约束（与 PRD 一一对应，改断言前先读 `docs/F05_01_PLAYER_TRACE_REQUIREMENTS.md`）：
 * - 顺序不能反：**先按视角过滤，再做座位聚合**；反过来会让私有字段从聚合结果里漏出去。
 * - `null` ≠ `0`；汇总票 ≠ 个人票；被提名上车 ≠ 实际任务队员；声称 ≠ 身份事实。
 * - 唯一的时间例外是 `PrivateNoteAdded.availableFromOrder`（decisions B31）。
 */
class PlayerTraceBuilderTest {

    private companion object {
        const val PLAYER_COUNT = 8

        /** 时间上界：足够大，覆盖全部事件。 */
        const val AT_END = 999
    }

    /**
     * 固定夹具（8 人，事件顺序 = 调用顺序）：
     *
     * | order | 事件 |
     * |---|---|
     * | 1 | 任务 1 开始，队长 1 |
     * | 2 | 任务 1 第 1 次提案，队长 1，队伍 1/2/3 |
     * | 3 | 任务 1 第 1 次投票，**只有汇总票**：赞成 5 / 反对 3 |
     * | 4 | 任务 1 第 1 次决议：通过 |
     * | 5 | 任务 1 结果，队伍 1/2/3，失败牌 1 |
     * | 6 | 任务 1 第 2 次提案，队长 2，队伍 2/3/4 |
     * | 7 | 任务 1 第 2 次投票，**逐人票**：1 赞成 / 2 反对 / 3 未知 |
     * | 8 | 任务 1 第 2 次决议：通过 |
     * | 9 | 任务 1 结果，队伍 2/3/4/5，失败牌 0 |
     * | 10 | 任务 2 开始，队长 4 |
     * | 11 | 任务 2 第 1 次提案，队长 4，队伍 4/5/6 |
     * | 12 | 任务 2 结果，队伍 4/5/6，失败牌**未记录** |
     * | 13 | 任务 3 开始，队长 5 |
     * | 14 | 任务 3 结果，队伍**未记录** |
     * | 15 | 3 号声称派西维尔，提及 1 号 |
     * | 16 | 1 号发言怀疑 4 号 |
     * | 17 | 1 号私人笔记：怀疑 4 号，从第 1 个事件起可见 |
     * | 18 | 1 号私人笔记：信任 2 号，从第 99 个事件起可见（尚未生效） |
     */
    private fun scenario(): MatchBuilder = MatchBuilder.eightPlayerMerlinMordred().apply {
        questStarted(1, 1)
        teamProposed(1, 1, 1, listOf(1, 2, 3))
        teamVoteRecorded(1, 1, approveCount = 5, rejectCount = 3)
        teamProposalResolved(1, 1, ProposalDecision.APPROVED, approveCount = 5, rejectCount = 3)
        missionResolved(1, listOf(1, 2, 3), failCount = 1, observedOutcome = MissionOutcome.FAILED)

        teamProposed(1, 2, 2, listOf(2, 3, 4))
        teamVoteRecorded(
            1,
            2,
            approveCount = 1,
            rejectCount = 1,
            unknownVoteCount = 1,
            votesBySeat = mapOf(
                1 to TeamVoteChoice.APPROVE,
                2 to TeamVoteChoice.REJECT,
                3 to TeamVoteChoice.UNKNOWN
            )
        )
        teamProposalResolved(1, 2, ProposalDecision.APPROVED, approveCount = 1, rejectCount = 1)
        missionResolved(1, listOf(2, 3, 4, 5), failCount = 0, observedOutcome = MissionOutcome.SUCCESS)

        questStarted(2, 4)
        teamProposed(2, 1, 4, listOf(4, 5, 6))
        missionResolved(2, listOf(4, 5, 6), failCount = null, observedOutcome = null)

        questStarted(3, 5)
        missionResolved(3, teamSeats = null, failCount = null)

        playerClaimed(
            speakerSeat = 3,
            claimType = ClaimType.ROLE_CLAIM,
            questNo = 1,
            claimedRole = RoleId.PERCIVAL,
            targetSeats = listOf(1)
        )
        playerClaimed(
            speakerSeat = 1,
            claimType = ClaimType.SUSPECT,
            questNo = 2,
            targetSeats = listOf(4),
            text = "4 号很可疑"
        )

        privateNoteAdded(
            observerSeat = 1,
            beliefType = BeliefType.SUSPECT,
            targetSeats = listOf(4),
            questNo = 1,
            availableFromOrder = 1,
            text = "私下记：4 号可疑"
        )
        privateNoteAdded(
            observerSeat = 1,
            beliefType = BeliefType.TRUST,
            targetSeats = listOf(2),
            questNo = 2,
            availableFromOrder = 99
        )
    }

    private fun trace(
        playerSeat: Int,
        atOrder: Int = AT_END,
        mode: Perspective = Perspective.PUBLIC,
        observerSeat: Int? = null,
        questFilter: Int? = null,
        categoryFilter: Set<PlayerTraceCategory>? = null,
        events: List<com.roundtrace.app.core.model.MatchEvent> = scenario().events(),
        playerCount: Int = PLAYER_COUNT
    ): List<PlayerTraceItem> = PlayerTraceBuilder.build(
        events = events,
        playerSeat = playerSeat,
        atOrder = atOrder,
        mode = mode,
        observerSeat = observerSeat,
        playerCount = playerCount,
        questFilter = questFilter,
        categoryFilter = categoryFilter
    )

    // ————————————————————————————————————————————————————————————
    // F02：同一任务多提案 —— 提名队伍与执行队伍各自准确
    // ————————————————————————————————————————————————————————————

    @Test
    fun nominationAndMissionTeamAreNotTheSameVehicle() {
        val items = trace(playerSeat = 4)

        val nominated = items.filter { it.category == PlayerTraceCategory.NOMINATED }
        val mission = items.filter { it.category == PlayerTraceCategory.MISSION_MEMBER }

        // 4 号被提名两次：任务 1 第 2 次提案（2/3/4）、任务 2 第 1 次提案（4/5/6）
        assertEquals("被提名的项目数不对：${nominated.map { it.title }}", 2, nominated.size)
        // 4 号实际在任务 1 第 2 次提案对应的任务结果里（2/3/4/5），任务 2 结果里也在（4/5/6）
        assertEquals("任务队员的项目数不对：${mission.map { it.title }}", 2, mission.size)

        val quest1Nomination = nominated.single { it.questNo == 1 }
        val quest1Mission = mission.single { it.questNo == 1 }

        assertTrue(
            "提名条目必须写明只是被提议：${quest1Nomination.detail}",
            quest1Nomination.detail.contains("被提议上车") && quest1Nomination.detail.contains("2 / 3 / 4")
        )
        assertFalse(
            "提名条目不得混入其它提案 / 实际队伍：${quest1Nomination.detail}",
            quest1Nomination.detail.contains("5 号")
        )
        assertTrue(
            "执行任务条目必须写明是记录到的任务队伍：${quest1Mission.detail}",
            quest1Mission.detail.contains("2 / 3 / 4 / 5")
        )
        assertTrue(
            "执行任务条目必须标注未经核实：${quest1Mission.detail}",
            quest1Mission.detail.contains("未核实")
        )
        assertNotEquals(
            "被提议上车与实际执行任务必须是不同关系",
            quest1Nomination.relationship,
            quest1Mission.relationship
        )
    }

    // ————————————————————————————————————————————————————————————
    // F03：逐人票可归属；汇总票不分摊到个人
    // ————————————————————————————————————————————————————————————

    @Test
    fun onlySummaryVoteNeverProducesPerSeatVotes() {
        // 第 3 个事件只有汇总票（赞成 5 / 反对 3），任何座位都不得出现个人投票记录
        (1..PLAYER_COUNT).forEach { seat ->
            val summaryBased = trace(playerSeat = seat).filter {
                it.category == PlayerTraceCategory.PERSONAL_VOTE && it.occurredOrder == 3
            }
            assertTrue(
                "$seat 号不得凭汇总票产生个人投票记录：${summaryBased.map { it.title }}",
                summaryBased.isEmpty()
            )
        }
    }

    @Test
    fun perSeatVotesAreAttributedToTheRightSeat() {
        val one = trace(playerSeat = 1).single { it.category == PlayerTraceCategory.PERSONAL_VOTE }
        assertEquals(7, one.occurredOrder)
        assertTrue("1 号记录为赞成：${one.title}", one.title.contains("赞成"))
        assertFalse("不得把别人的票算到 1 号头上：${one.title}", one.title.contains("反对"))
        assertEquals(PlayerTraceFactLevel.RECORDED, one.factLevel)

        val two = trace(playerSeat = 2).single { it.category == PlayerTraceCategory.PERSONAL_VOTE }
        assertTrue("2 号记录为反对：${two.title}", two.title.contains("反对"))

        val three = trace(playerSeat = 3).single { it.category == PlayerTraceCategory.PERSONAL_VOTE }
        assertTrue("3 号记录为未知：${three.title}", three.title.contains("未知"))
        assertEquals(
            "记录为「未知」的票是 UNKNOWN 事实，不得当成赞成或反对",
            PlayerTraceFactLevel.UNKNOWN,
            three.factLevel
        )
        assertFalse("未知票不得被解释成反对", three.title.contains("反对"))
    }

    // ————————————————————————————————————————————————————————————
    // F04：发言者 ≠ 被提及者；声称 ≠ 身份事实
    // ————————————————————————————————————————————————————————————

    @Test
    fun speakerAndMentionedAreSeparateRelations() {
        val items = trace(playerSeat = 1)

        val spoken = items.filter { it.category == PlayerTraceCategory.SPEAKER }
        val mentioned = items.filter { it.category == PlayerTraceCategory.MENTIONED }

        assertEquals("1 号只发过一次言", 1, spoken.size)
        assertEquals("1 号只被提及过一次", 1, mentioned.size)
        assertEquals(16, spoken.single().occurredOrder)
        assertEquals(15, mentioned.single().occurredOrder)

        assertTrue("发言条目应以发言者为主语：${spoken.single().title}", spoken.single().title.contains("1 号"))
        assertTrue(
            "被提及条目必须点明提及者：${mentioned.single().detail}",
            mentioned.single().title.contains("3 号") && mentioned.single().title.contains("提及")
        )
        assertNotEquals(
            "发言者与被提及者必须是不同关系",
            spoken.single().relationship,
            mentioned.single().relationship
        )
    }

    @Test
    fun claimedRoleIsNeverWrittenAsIdentityFact() {
        val claim = trace(playerSeat = 3).single { it.category == PlayerTraceCategory.SPEAKER }

        assertTrue("必须写明是「声称」：${claim.title}", claim.title.contains("声称"))
        assertTrue("声称内容必须可读：${claim.title}", claim.title.contains("派西维尔"))
        assertTrue(
            "必须显式声明声称不等于身份事实：${claim.detail}",
            claim.detail.contains("不等于身份事实")
        )
        assertNotEquals("不得直接把声称写成身份事实", "3 号是派西维尔", claim.title)
    }

    // ————————————————————————————————————————————————————————————
    // F05：视角隔离 —— PUBLIC 无私有笔记 / OBSERVER 只见自己的 / GOD 可见
    // ————————————————————————————————————————————————————————————

    @Test
    fun publicPerspectiveSeesNoPrivateNotes() {
        (1..PLAYER_COUNT).forEach { seat ->
            val notes = trace(playerSeat = seat, mode = Perspective.PUBLIC)
                .filter { it.category == PlayerTraceCategory.PRIVATE_NOTE }
            assertTrue("公开视角下 $seat 号不得看到任何私人笔记", notes.isEmpty())
        }
    }

    @Test
    fun observerSeesOnlyOwnPrivateNotes() {
        val own = trace(
            playerSeat = 1,
            mode = Perspective.OBSERVER_AT_TIME,
            observerSeat = 1
        ).filter { it.category == PlayerTraceCategory.PRIVATE_NOTE }
        assertEquals("1 号应看到自己两条已生效的笔记", 2, own.size)
        assertTrue("笔记内容应可读：${own.map { it.detail }}", own.any { it.detail.contains("4 号可疑") })

        // 1 号的笔记不得出现在 2 号的轨迹里（即使 2 号是被判断对象）
        val other = trace(
            playerSeat = 2,
            mode = Perspective.OBSERVER_AT_TIME,
            observerSeat = 2
        ).filter { it.category == PlayerTraceCategory.PRIVATE_NOTE }
        assertTrue("观察者不得看到别人的私人笔记", other.isEmpty())

        // 观察者不是 1 号时，1 号的笔记对 1 号的轨迹也不可见
        val wrongObserver = trace(
            playerSeat = 1,
            mode = Perspective.OBSERVER_AT_TIME,
            observerSeat = 3
        ).filter { it.category == PlayerTraceCategory.PRIVATE_NOTE }
        assertTrue("非本人观察者视角不得看到任何私人笔记", wrongObserver.isEmpty())
    }

    @Test
    fun godViewShowsPrivateNotesOnlyOnTheOwnersTrace() {
        val own = trace(playerSeat = 1, mode = Perspective.GOD_AFTER_REVEAL)
            .filter { it.category == PlayerTraceCategory.PRIVATE_NOTE }
        assertEquals("上帝视角下 1 号能看到自己的两条笔记", 2, own.size)

        val other = trace(playerSeat = 2, mode = Perspective.GOD_AFTER_REVEAL)
            .filter { it.category == PlayerTraceCategory.PRIVATE_NOTE }
        assertTrue(
            "别人的私人判断即使上帝视角也不进入 2 号的参与记录（那是 1 号的行为）",
            other.isEmpty()
        )
    }

    // ————————————————————————————————————————————————————————————
    // F06：B31 —— 私人笔记的时间边界只看 availableFromOrder
    // ————————————————————————————————————————————————————————————

    @Test
    fun privateNoteVisibilityUsesAvailableFromOrderOnly() {
        fun notesAt(atOrder: Int) = trace(
            playerSeat = 1,
            atOrder = atOrder,
            mode = Perspective.OBSERVER_AT_TIME,
            observerSeat = 1
        ).filter { it.category == PlayerTraceCategory.PRIVATE_NOTE }

        // 第 6 个事件之前：笔记 17（occurredOrder 17 / availableFromOrder 1）已可读
        val early = notesAt(5)
        assertEquals("回填笔记按 availableFromOrder 生效，不看 occurredOrder", 1, early.size)
        assertEquals("回填笔记的定位点必须是它的权威可见时刻", 1, early.single().occurredOrder)
        assertTrue(
            "定位点不得越过当前复盘位置",
            early.single().occurredOrder <= 5
        )

        // 未到 99 之前，第二条笔记不出现
        assertTrue("availableFromOrder 未到不得提前显示", notesAt(98).none { it.title.contains("信任") })
        // 到 99 之后才出现
        assertTrue("availableFromOrder 到点后必须出现", notesAt(99).any { it.title.contains("信任") })
    }

    // ————————————————————————————————————————————————————————————
    // F07：修订 —— retracted 旧版本不出现，同一逻辑位置只有一条 active
    // ————————————————————————————————————————————————————————————

    @Test
    fun retractedVersionsAreNotShown() {
        val old = TeamProposed(
            eventId = EventId("old-proposal"),
            occurredOrder = 5,
            recordedAt = 1L,
            visibility = EventVisibility.PUBLIC,
            retracted = true,
            questNo = 1,
            proposalNo = 1,
            leaderSeat = 2,
            teamSeats = listOf(2, 3, 4)
        )
        val active = old.copy(
            eventId = EventId("active-proposal"),
            recordedAt = 2L,
            supersedesId = old.eventId,
            retracted = false,
            teamSeats = listOf(2, 3, 5)
        )

        val items = trace(playerSeat = 5, events = listOf(old, active))

        assertEquals("同一逻辑位置只应有一条 active 记录", 1, items.size)
        assertEquals(EventId("active-proposal"), items.single().sourceEventId)
        assertTrue("修订后的队伍应生效：${items.single().detail}", items.single().detail.contains("2 / 3 / 5"))
    }

    // ————————————————————————————————————————————————————————————
    // F08：非法座位 / 未记录队伍 / null ≠ 0
    // ————————————————————————————————————————————————————————————

    @Test
    fun illegalSeatReturnsEmptyListInsteadOfThrowing() {
        listOf(0, -1, PLAYER_COUNT + 1, 99).forEach { seat ->
            assertTrue("非法座位 $seat 应返回空列表", trace(playerSeat = seat).isEmpty())
        }
        assertTrue(
            "人数为 0 时任何座位都不合法",
            trace(playerSeat = 1, playerCount = 0).isEmpty()
        )
    }

    @Test
    fun unknownMissionTeamMeansNoMissionMember() {
        val items = trace(playerSeat = 5)
        // 任务 3 的结果没有记录队伍（teamSeats = null），不得猜人
        assertTrue(
            "没有 teamSeats 就不能推断谁执行了任务",
            items.none { it.occurredOrder == 14 && it.category == PlayerTraceCategory.MISSION_MEMBER }
        )
    }

    @Test
    fun missionFailCountUnknownIsNotZero() {
        // 5 号只在任务 1 第 2 次结果里（失败牌 0）——只出现一条任务队员记录
        val zero = trace(playerSeat = 5, questFilter = 1)
            .single { it.category == PlayerTraceCategory.MISSION_MEMBER }
        assertTrue("0 张失败牌是已知事实：${zero.detail}", zero.detail.contains("0"))

        val unknown = trace(playerSeat = 4, questFilter = 2)
            .single { it.category == PlayerTraceCategory.MISSION_MEMBER }
        assertTrue("未记录必须写「未记录」：${unknown.detail}", unknown.detail.contains("未记录"))
        assertNotEquals(
            "null 与 0 的展示必须不同",
            zero.detail,
            unknown.detail
        )

        // 失败牌数已知也不能推断是谁出的
        val failed = trace(playerSeat = 1, questFilter = 1)
            .single { it.category == PlayerTraceCategory.MISSION_MEMBER }
        assertTrue("失败牌数应展示：${failed.detail}", failed.detail.contains("1"))
        assertTrue(
            "不得从失败牌推测出牌者：${failed.detail}",
            failed.detail.contains("不推断")
        )
    }

    // ————————————————————————————————————————————————————————————
    // 时间上界 / 排序 / 永不产出推测
    // ————————————————————————————————————————————————————————————

    @Test
    fun eventsAfterTheTimeBoundAreExcluded() {
        val items = trace(playerSeat = 1, atOrder = 2)
        assertTrue("时间上界之后的事件不得出现：${items.map { it.occurredOrder }}", items.all { it.occurredOrder <= 2 })
        assertTrue("上界内应能看到队长与提名记录", items.any { it.category == PlayerTraceCategory.LEADER })
        assertTrue(
            "第 3 个事件的投票在上界之后，不得出现",
            items.none { it.occurredOrder >= 3 }
        )
    }

    @Test
    fun itemsAreSortedByOccurredOrder() {
        val orders = trace(playerSeat = 1).map { it.occurredOrder }
        assertEquals("必须按 occurredOrder 升序", orders.sorted(), orders)
    }

    @Test
    fun builderNeverEmitsInferredItems() {
        (1..PLAYER_COUNT).forEach { seat ->
            listOf(
                Perspective.PUBLIC,
                Perspective.OBSERVER_AT_TIME,
                Perspective.GOD_AFTER_REVEAL
            ).forEach { mode ->
                val inferred = trace(playerSeat = seat, mode = mode, observerSeat = 1)
                    .filter { it.factLevel == PlayerTraceFactLevel.INFERRED }
                assertTrue("不得把推测当事实展示（$seat 号 / $mode）：${inferred.map { it.title }}", inferred.isEmpty())
            }
        }
    }

    // ————————————————————————————————————————————————————————————
    // 筛选
    // ————————————————————————————————————————————————————————————

    @Test
    fun questAndCategoryFiltersNarrowTheList() {
        val quest2 = trace(playerSeat = 1, questFilter = 2)
        assertTrue("任务筛选后只剩任务 2", quest2.isNotEmpty() && quest2.all { it.questNo == 2 })

        val speakers = trace(playerSeat = 1, categoryFilter = setOf(PlayerTraceCategory.SPEAKER))
        assertEquals(1, speakers.size)
        assertTrue("类别筛选后只剩发言", speakers.all { it.category == PlayerTraceCategory.SPEAKER })

        val emptyFilter = trace(playerSeat = 1, categoryFilter = emptySet())
        assertEquals("空集合等于不过滤", trace(playerSeat = 1).size, emptyFilter.size)
    }

    // ————————————————————————————————————————————————————————————
    // 队长记录不得推断阵营
    // ————————————————————————————————————————————————————————————

    @Test
    fun leaderRecordDoesNotImplyAlignment() {
        val leaders = trace(playerSeat = 1).filter { it.category == PlayerTraceCategory.LEADER }
        assertTrue("1 号既是任务 1 的队长也是提案者", leaders.size >= 2)
        leaders.forEach { item ->
            assertFalse("队长记录不得推断阵营：${item.detail}", item.detail.contains("好人"))
            assertFalse("队长记录不得推断阵营：${item.detail}", item.detail.contains("坏人"))
        }
    }
}
