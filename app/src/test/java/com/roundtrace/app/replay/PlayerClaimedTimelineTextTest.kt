package com.roundtrace.app.replay

import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventType
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.replay.TimelineBuilder
import com.roundtrace.app.core.replay.VisibleEventPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F4：`PlayerClaimed.text`（用户在「关键言论」里写的话）必须能在时间线上看到。
 *
 * 原来 `TimelineBuilder` 只拼「发言类型 · 目标」，`text` 从未进入 title/detail；
 * `TimelineEventCard` / `RecordTimelineSection` 又只渲染这两个字段，
 * 于是用户写的话在记录页和复盘页都消失（FR-06 要求回放关键发言）。
 *
 * 第三条用例守的是边界：把 `text` 放进 detail **不能**变成「绕过可见性」的后门——
 * 公开视角拿到的是已经过 [VisibleEventPolicy] 过滤的事件列表，私有声称根本不进来。
 */
class PlayerClaimedTimelineTextTest {

    private fun claim(
        id: String,
        text: String?,
        visibility: EventVisibility = EventVisibility.PUBLIC,
        speakerSeat: Int = 3,
        occurredOrder: Int = 2
    ): PlayerClaimed = PlayerClaimed(
        eventId = EventId(id),
        occurredOrder = occurredOrder,
        recordedAt = 1_700_000_000_000L,
        visibility = visibility,
        supersedesId = null,
        retracted = false,
        questNo = 1,
        speakerSeat = speakerSeat,
        claimType = ClaimType.KEY_STATEMENT,
        text = text
    )

    @Test
    fun claimTextAppearsInTimelineDetail() {
        val events = listOf(claim("c1", "3 号：我觉得 5 号在带节奏，第一轮就想推 1-2-5"))
        val timeline = TimelineBuilder.build(events)

        assertEquals(1, timeline.size)
        val entry = timeline.single()
        assertEquals(EventType.PLAYER_CLAIMED, entry.eventType)
        assertTrue(
            "时间线 detail 必须包含用户录入的原文，实际=${entry.detail}",
            entry.detail.contains("3 号：我觉得 5 号在带节奏，第一轮就想推 1-2-5")
        )
        assertTrue(
            "发言类型仍要在 detail 里（方便扫读），实际=${entry.detail}",
            entry.detail.contains("关键言论")
        )
    }

    @Test
    fun longClaimTextIsTruncatedWithEllipsis() {
        val longText = "啊".repeat(TimelineBuilder.MAX_CLAIM_TEXT_LENGTH + 20)
        val entry = TimelineBuilder.build(listOf(claim("c2", longText))).single()

        assertTrue(
            "超长文本必须截断，实际长度=${entry.detail.length}",
            entry.detail.length < longText.length + 10
        )
        assertTrue("截断后要有省略号", entry.detail.contains("…"))
        assertFalse(
            "不得把整段话原样塞进卡片",
            entry.detail.contains(longText)
        )
    }

    @Test
    fun blankClaimTextDoesNotProduceDanglingSeparator() {
        val entry = TimelineBuilder.build(listOf(claim("c3", "   "))).single()
        assertEquals("空白文本等同没写，detail 只保留类型", "关键言论", entry.detail)
    }

    @Test
    fun privateClaimTextNeverReachesPublicTimeline() {
        val events: List<MatchEvent> = listOf(
            claim("pub", "公开说的话"),
            claim("priv", "只有我记得的悄悄话", visibility = EventVisibility.PRIVATE_OBSERVER)
        )

        // 复盘页走的就是这条路径：先按视角过滤，再建时间线
        val publicEvents = VisibleEventPolicy.forPerspective(
            events = events,
            mode = Perspective.PUBLIC,
            observerSeat = null
        )
        val publicTimeline = TimelineBuilder.build(publicEvents)

        assertEquals("公开视角只能看到 1 条声称", 1, publicTimeline.size)
        assertTrue(publicTimeline.single().detail.contains("公开说的话"))
        assertTrue(
            "私有声称的文本不得出现在公开时间线，实际=${publicTimeline.map { it.detail }}",
            publicTimeline.none { it.detail.contains("悄悄话") }
        )

        // 对照：本人视角能看到自己的那条（证明过滤条件没写反）
        val observerTimeline = TimelineBuilder.build(
            VisibleEventPolicy.forPerspective(events, Perspective.OBSERVER_AT_TIME, 3)
        )
        assertTrue(
            "声称者本人应当看得到自己写的私有声称",
            observerTimeline.any { it.detail.contains("悄悄话") }
        )
    }
}
