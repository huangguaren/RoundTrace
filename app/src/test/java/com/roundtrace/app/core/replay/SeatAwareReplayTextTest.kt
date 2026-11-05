package com.roundtrace.app.core.replay

import com.roundtrace.app.core.model.BeliefType
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.Participant
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.ui.identity.SeatDisplayResolver
import com.roundtrace.app.util.MatchBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复盘文案的结构化座位格式化（UX-ID-02 / Phase B）。
 *
 * 钉住 PRD 的 ID-06 / 07 / 08 / 10 / 11：
 * - 座位引用（队长 / 提案队伍 / 任务队伍 / 发言者 / 被提及 / 私人笔记对象）
 *   必须显示「昵称（N号）」；
 * - **任务序号、票数、失败牌数、玩家自由文本里的「2号」一律不得被改写**（ID-07）；
 * - 视角、B31 可见时刻、修订过滤、事件计数与排序不得因格式化而改变。
 *
 * 格式化能力以**可选纯回调**形式进入 builder，默认 null 时输出与旧版逐字一致，
 * 这样既有 613 条 JVM 用例不会被「顺手美化文案」破坏。
 */
class SeatAwareReplayTextTest {

    private companion object {
        const val PLAYER_COUNT = 8
        const val AT_END = 999
    }

    /** 1 小明 / 2 阿华 / 3 小林 / 4 阿华（与 2 号同名）。 */
    private val identity = SeatDisplayResolver.resolve(
        listOf(
            Participant(seat = 1, displayName = "小明", avatarUri = null),
            Participant(seat = 2, displayName = "阿华", avatarUri = null),
            Participant(seat = 3, displayName = "小林", avatarUri = null),
            Participant(seat = 4, displayName = "阿华", avatarUri = null),
            Participant(seat = 5, displayName = null, avatarUri = null),
            Participant(seat = 6, displayName = "", avatarUri = null),
            Participant(seat = 7, displayName = "小七", avatarUri = null),
            Participant(seat = 8, displayName = "小八", avatarUri = null)
        ),
        PLAYER_COUNT
    )

    // 与 ReplayViewModel 的生产回调**完全一致**（不用 formatSeat）：
    // 有昵称→「阿华（2号）」，无昵称→沿用句式「2 号」。
    private val label: (Int) -> String = identity::formatSeatInSentence

    /** 生产口径：没有任何昵称时 ViewModel 传 null 回调，逐字保持基线。 */
    private val productionLabel: ((Int) -> String)? =
        if (identity.hasAnyNames) identity::formatSeatInSentence else null

    private fun buildTrace(
        playerSeat: Int,
        events: List<com.roundtrace.app.core.model.MatchEvent>,
        mode: Perspective = Perspective.PUBLIC,
        observerSeat: Int? = 1,
        atOrder: Int = AT_END,
        withLabel: Boolean = true
    ): List<PlayerTraceItem> = PlayerTraceBuilder.build(
        events = events,
        playerSeat = playerSeat,
        atOrder = atOrder,
        mode = mode,
        observerSeat = observerSeat,
        playerCount = PLAYER_COUNT,
        seatLabel = if (withLabel) label else null
    )

    // ————————————————————————— ID-06 提案 / 任务队伍名单 —————————————————————————

    @Test
    fun proposalTeamListShowsEveryNicknameWithSeat() {
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            questStarted(1, 1)
            teamProposed(1, 1, 1, listOf(1, 2, 3))
        }.events()

        val item = buildTrace(1, events).single { it.relationship == PlayerTraceRelationship.PROPOSED_TEAM }

        assertTrue(item.detail, item.detail.contains("小明（1号） / 阿华（2号） / 小林（3号）"))
        // 任务序号 / 提案序号不得被当成座位替换
        assertTrue(item.title, item.title.contains("第 1 任务 · 第 1 次提案"))
    }

    @Test
    fun missingNicknameFallsBackToSeatNumberInsideTeamList() {
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            questStarted(1, 1)
            teamProposed(1, 1, 1, listOf(5, 6, 7))
        }.events()

        val item = buildTrace(1, events).single { it.relationship == PlayerTraceRelationship.PROPOSED_TEAM }
        assertTrue(item.detail, item.detail.contains("5 号 / 6 号 / 小七（7号）"))
    }

    @Test
    fun missionTeamListAlsoUsesNicknamesAndKeepsFailCardNumber() {
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            questStarted(1, 1)
            missionResolved(1, listOf(2, 3, 4), failCount = 1, observedOutcome = MissionOutcome.FAILED)
        }.events()

        val item = buildTrace(2, events).single { it.category == PlayerTraceCategory.MISSION_MEMBER }
        assertTrue(item.detail, item.detail.contains("阿华（2号） / 小林（3号） / 阿华（4号）"))
        // 「失败牌数：1」是数字事实，不能被换成昵称
        assertTrue(item.detail, item.detail.contains("失败牌数：1"))
    }

    // ————————————————————————— ID-07 自由文本绝不被改写 —————————————————————————

    @Test
    fun freeTextContainingSeatNumberIsNeverRewritten() {
        val spoken = "我觉得 2号 很可疑，第 2 任务有 2 张失败牌"
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            questStarted(2, 2)
            playerClaimed(
                speakerSeat = 2,
                claimType = ClaimType.SUSPECT,
                questNo = 2,
                targetSeats = listOf(4),
                text = spoken
            )
        }.events()

        val item = buildTrace(2, events).single { it.category == PlayerTraceCategory.SPEAKER }
        // 结构化座位引用要带昵称
        assertTrue(item.title, item.title.contains("阿华（2号）"))
        assertTrue(item.detail, item.detail.contains("阿华（4号）"))
        // 但玩家原话一个字都不能变
        assertTrue(item.detail, item.detail.contains(spoken))
        assertFalse(item.detail.contains("我觉得 阿华"))
    }

    @Test
    fun timelineFreeTextAlsoStaysVerbatim() {
        val spoken = "2号 在保 5号，第 2 任务有 2 张失败牌"
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            playerClaimed(speakerSeat = 2, claimType = ClaimType.SUSPECT, text = spoken)
        }.events()

        val entry = TimelineBuilder.build(events, seatLabel = label).single()
        assertTrue(entry.detail, entry.detail.contains(spoken))
        assertFalse(entry.detail.contains("阿华（2号） 在保"))
        // 结构化标题里的发言者仍要带昵称
        assertTrue(entry.title, entry.title.contains("阿华（2号）"))
    }

    @Test
    fun timelineVoteCountsAndQuestNumbersAreNotSeatReferences() {
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            questStarted(2, 1)
            teamVoteRecorded(2, 1, approveCount = 5, rejectCount = 3)
        }.events()

        val vote = TimelineBuilder.build(events, seatLabel = label)
            .single { it.eventType.name.contains("VOTE") }
        assertTrue(vote.title, vote.title.contains("赞成 5"))
        assertTrue(vote.title, vote.title.contains("反对 3"))
        assertFalse(vote.title.contains("阿华"))
        assertTrue(vote.detail, vote.detail.contains("第 2 任务"))
    }

    // ————————————————————————— ID-08 同名不同 seat —————————————————————————

    @Test
    fun speakerAndMentionedKeepDistinctSeatsWhenNamesCollide() {
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            playerClaimed(
                speakerSeat = 2,
                claimType = ClaimType.SUSPECT,
                targetSeats = listOf(4),
                text = "2号 说的是 4号"
            )
        }.events()

        val speaker = buildTrace(2, events).single { it.category == PlayerTraceCategory.SPEAKER }
        val mentioned = buildTrace(4, events).single { it.category == PlayerTraceCategory.MENTIONED }
        assertTrue(speaker.title, speaker.title.startsWith("阿华（2号）"))
        assertTrue(mentioned.title, mentioned.title.contains("阿华（2号）"))
        // 两人昵称相同，但座位号必须让归属可区分：被提及的是 4 号，不是发言的 2 号
        assertTrue(speaker.title.contains("2号"))
        assertEquals(PlayerTraceRelationship.NAMED_BY_OTHER, mentioned.relationship)
        assertFalse(mentioned.relationship == PlayerTraceRelationship.SPOKE)
    }

    @Test
    fun privateNoteTargetsUseNicknamesButKeepB31VisibilityMoment() {
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            questStarted(1, 1)
            privateNoteAdded(
                observerSeat = 1,
                beliefType = BeliefType.SUSPECT,
                targetSeats = listOf(2, 3),
                questNo = 1,
                availableFromOrder = 1,
                text = "私下记：2号 可疑"
            )
        }.events()

        // B31：availableFromOrder=1，发生在自己 occurredOrder 之前；at=0 看不到，at=1 看得到
        assertEquals(0, buildTrace(1, events, mode = Perspective.OBSERVER_AT_TIME, atOrder = 0).size)
        val atOne = buildTrace(1, events, mode = Perspective.OBSERVER_AT_TIME, atOrder = 1)
            .single { it.category == PlayerTraceCategory.PRIVATE_NOTE }
        assertEquals(1, atOne.occurredOrder)
        assertTrue(atOne.title, atOne.title.contains("阿华（2号） / 小林（3号）"))
        // 自由文本与「自第 N 个事件起可见」不受格式化影响
        assertTrue(atOne.detail, atOne.detail.contains("私下记：2号 可疑"))
        assertTrue(atOne.detail, atOne.detail.contains("自第 1 个事件起可见"))
        assertEquals(EventVisibility.PRIVATE_OBSERVER, atOne.sourceVisibility)
    }

    // ————————————————————————— ID-09 视角权限不因格式化改变 —————————————————————————

    @Test
    fun publicPerspectiveStillCannotSeePrivateNotesWithLabels() {
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            privateNoteAdded(
                observerSeat = 1,
                beliefType = BeliefType.SUSPECT,
                targetSeats = listOf(2),
                availableFromOrder = 1
            )
        }.events()

        val publicTrace = buildTrace(2, events, mode = Perspective.PUBLIC)
        assertTrue(publicTrace.none { it.category == PlayerTraceCategory.PRIVATE_NOTE })
        assertTrue(publicTrace.none { it.sourceVisibility == EventVisibility.PRIVATE_OBSERVER })
    }

    @Test
    fun formattingNeverIntroducesRoleOrAlignmentFacts() {
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            questStarted(1, 1)
            teamProposed(1, 1, 1, listOf(1, 2, 3))
        }.events()

        val item = buildTrace(1, events).single { it.relationship == PlayerTraceRelationship.PROPOSED_TEAM }
        assertTrue(item.detail, item.detail.contains("不推断阵营"))
        assertFalse(item.title.contains("梅林") || item.title.contains("忠臣"))
    }

    // ————————————————————————— ID-10 修订 / 回滚不改变计数与顺序 —————————————————————————

    @Test
    fun formattingDoesNotChangeItemCountOrderOrRetractedFiltering() {
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            questStarted(1, 1)
            teamProposed(1, 1, 1, listOf(1, 2, 3))
            questStarted(2, 4)
        }.events()
        val retracted = events.map { e ->
            if (e is com.roundtrace.app.core.model.TeamProposed) {
                // 直接构造一个撤销副本，不改变事件事实字段
                e.copy(retracted = true) as com.roundtrace.app.core.model.MatchEvent
            } else e
        }

        val plain = buildTrace(1, events, withLabel = false)
        val labelled = buildTrace(1, events, withLabel = true)
        assertEquals(plain.size, labelled.size)
        assertEquals(plain.map { it.occurredOrder }, labelled.map { it.occurredOrder })
        assertEquals(plain.map { it.sourceEventId.raw }, labelled.map { it.sourceEventId.raw })
        assertEquals(
            0,
            buildTrace(1, retracted).count { it.relationship == PlayerTraceRelationship.PROPOSED_TEAM }
        )
    }

    // ————————————————————————— 默认行为兼容 —————————————————————————

    @Test
    fun defaultBuildIsByteIdenticalToLegacyWhenNoLabelProvided() {
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            questStarted(1, 1)
            teamProposed(1, 1, 1, listOf(1, 2, 3))
        }.events()

        val item = buildTrace(1, events, withLabel = false)
            .single { it.relationship == PlayerTraceRelationship.PROPOSED_TEAM }
        assertTrue(item.detail, item.detail.contains("提案队伍：1 / 2 / 3 号"))
        assertFalse(item.detail.contains("小明"))

        val legacyTimeline = TimelineBuilder.build(events)
        val labelledTimeline = TimelineBuilder.build(events, seatLabel = label)
        assertTrue(legacyTimeline.single { it.title.contains("提出队伍") }.title.contains("队长 1 号"))
        assertTrue(labelledTimeline.single { it.title.contains("提出队伍") }.title.contains("小明（1号）"))
    }

    // ————————————————————————— 主时间线座位引用 —————————————————————————

    @Test
    fun productionFormatterIsTheOneUsedByTests() {
        // 钉住 P1：测试与 ReplayViewModel 必须用同一个格式化口径，
        // 防止「生产改了、测试还在验证另一套」造成文案静默变化。
        assertEquals("阿华（2号）", productionLabel!!(2))
        assertEquals("5 号", productionLabel(5))
        assertFalse(productionLabel(5) == identity.formatSeat(5))
    }

    @Test
    fun matchWithoutAnyNicknameKeepsBaselineWordingVerbatim() {
        val emptyIdentity = SeatDisplayResolver.resolve(emptyList(), PLAYER_COUNT)
        assertFalse(emptyIdentity.hasAnyNames)
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            questStarted(1, 1)
            teamProposed(1, 1, 1, listOf(1, 2, 3))
        }.events()

        // 生产路径：hasAnyNames=false → seatLabel=null
        val item = PlayerTraceBuilder.build(
            events = events,
            playerSeat = 1,
            atOrder = AT_END,
            mode = Perspective.PUBLIC,
            observerSeat = 1,
            playerCount = PLAYER_COUNT,
            seatLabel = if (emptyIdentity.hasAnyNames) {
                emptyIdentity::formatSeatInSentence
            } else {
                null
            }
        ).single { it.relationship == PlayerTraceRelationship.PROPOSED_TEAM }

        assertTrue(item.detail, item.detail.contains("提案队伍：1 / 2 / 3 号"))
        assertFalse(item.detail.contains("1 号 /"))
        val timeline = TimelineBuilder.build(events, seatLabel = null)
        assertTrue(timeline.single { it.title.contains("提出队伍") }.title.contains("1 号 / 2 号 / 3 号"))
    }

    @Test
    fun timelineQuestStartedAndMissionUseSameFormatting() {
        val events = MatchBuilder.eightPlayerMerlinMordred().apply {
            questStarted(2, 2)
            missionResolved(2, listOf(2, 3), failCount = 0, observedOutcome = MissionOutcome.SUCCESS)
        }.events()

        val entries = TimelineBuilder.build(events, seatLabel = label)
        val quest = entries.single { it.title.contains("任务开始") }
        assertTrue(quest.title, quest.title.contains("阿华（2号）"))
        assertTrue(quest.title, quest.title.contains("第 2 任务"))
        val mission = entries.single { it.title.contains("任务牌结果") }
        assertTrue(mission.detail, mission.detail.contains("阿华（2号） / 小林（3号）"))
        assertTrue(mission.detail, mission.detail.contains("失败牌 0"))
    }
}
