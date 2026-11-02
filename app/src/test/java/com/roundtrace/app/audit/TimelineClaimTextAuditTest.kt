package com.roundtrace.app.audit

import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.replay.TimelineBuilder
import com.roundtrace.app.core.replay.VisibleEventPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA 阶段 D · §7 复验：**F4（声称原文进入时间线 detail）**。
 *
 * 阶段 D 的 P2-2 指出：`PlayerClaimed.text` 从不进入 `title`/`detail`，
 * 于是「关键发言」在记录页与复盘页都回放不出原文。F4 把原文放进了 `detail`。
 *
 * 这里不只验「文本出现了」，还验 F4 **没有顺手改变可见性**：
 * 私有声称在公开视角仍然不出现（`VisibleEventPolicy` 是唯一出口，F4 只改展示）。
 */
class TimelineClaimTextAuditTest {

    private fun claim(
        id: String = "claim-1",
        text: String? = null,
        targetSeats: List<Int> = emptyList(),
        visibility: EventVisibility = EventVisibility.PUBLIC,
        claimedRole: RoleId? = null
    ) = PlayerClaimed(
        eventId = EventId(id),
        occurredOrder = 3,
        recordedAt = 0L,
        visibility = visibility,
        questNo = 1,
        speakerSeat = 2,
        claimType = if (claimedRole != null) ClaimType.ROLE_CLAIM else ClaimType.SUSPECT,
        claimedRole = claimedRole,
        targetSeats = targetSeats,
        text = text
    )

    @Test
    fun f4_claim_text_appears_in_detail() {
        val entry = TimelineBuilder.toEntry(claim(text = "我觉得 3 号在保 5 号"))
        println("[AUDIT-D] claim 条目：title=${entry.title} detail=${entry.detail}")

        assertTrue(
            "F4：原文必须出现在 detail 里，实际=${entry.detail}",
            entry.detail.contains("我觉得 3 号在保 5 号")
        )
    }

    @Test
    fun f4_long_text_is_truncated_with_ellipsis() {
        val long = "这是一段很长的发言".repeat(20) // 180 字，远超上限 60
        val entry = TimelineBuilder.toEntry(claim(text = long))

        assertTrue(
            "截断后仍要保留开头（否则用户看不出内容）",
            entry.detail.contains(long.take(20))
        )
        assertTrue("截断必须带省略号，实际=${entry.detail}", entry.detail.contains("…"))
        assertFalse(
            "不得把整段原文塞进 detail（时间线卡片会被撑爆）",
            entry.detail.contains(long)
        )
        assertTrue(
            "detail 长度应受控（<= 元信息 + 60 + 省略号 + 分隔符 的宽松上限），实际=${entry.detail.length}",
            entry.detail.length <= 120
        )
    }

    @Test
    fun f4_claim_without_text_still_renders_meta() {
        val withTarget = TimelineBuilder.toEntry(claim(text = null, targetSeats = listOf(3)))
        assertTrue(
            "没有原文时仍要显示类型与目标，实际=${withTarget.detail}",
            withTarget.detail.contains("3 号")
        )

        // 只有空白原文时：detail 不得为空，也不得把空白原样带出来。
        //
        // 注意：`claimDetail` 末尾的 `ifEmpty { "公开发言" }` 实际上是**不可达兜底**——
        // `claimTypeLabel(claimType)` 永远非空，所以 meta 部分不会为空。
        // 这里不断言「公开发言」，只断言「非空且不含空白原文」。
        val bare = TimelineBuilder.toEntry(claim(text = "   ", targetSeats = emptyList()))
        println("[AUDIT-D] 无原文条目 detail=「${bare.detail}」")
        assertTrue("detail 不得为空", bare.detail.isNotBlank())
        assertFalse(
            "不得把纯空白原文带出来，实际=「${bare.detail}」",
            bare.detail.trim() != bare.detail
        )
    }

    /** 角色声称：原文与「声称梅林」都要在（Claim 不是事实）。 */
    @Test
    fun f4_role_claim_keeps_claim_wording() {
        val entry = TimelineBuilder.toEntry(
            claim(text = "我才是真的梅林", claimedRole = RoleId.MERLIN)
        )
        println("[AUDIT-D] 角色声称：title=${entry.title} detail=${entry.detail}")
        assertTrue("必须保留原文", entry.detail.contains("我才是真的梅林"))
        assertTrue(
            "必须保留「声称」语义（不能渲染成事实），实际=${entry.title}",
            entry.title.contains("声称") || entry.detail.contains("声称")
        )
    }

    /**
     * F4 不得改变可见性：私有声称经过 `VisibleEventPolicy` 后仍然不进公开时间线。
     * （这条同时是阶段 D P2-2 的回归护栏——改展示容易顺手放宽过滤。）
     */
    @Test
    fun f4_does_not_change_visibility() {
        val privateClaim = claim(
            id = "claim-private",
            text = "私下说：我要跳梅林",
            visibility = EventVisibility.PRIVATE_OBSERVER,
            claimedRole = RoleId.MERLIN
        )

        val publicTimeline = TimelineBuilder.build(
            VisibleEventPolicy.forPerspective(listOf(privateClaim), Perspective.PUBLIC, null)
        )
        assertTrue("公开视角的时间线必须为空", publicTimeline.isEmpty())

        val ownTimeline = TimelineBuilder.build(
            VisibleEventPolicy.forPerspective(listOf(privateClaim), Perspective.OBSERVER_AT_TIME, 2)
        )
        assertEquals("观察者本人应看到自己的私有声称", 1, ownTimeline.size)
        assertTrue(
            "而且原文也在（F4 对私有事件同样生效）",
            ownTimeline.first().detail.contains("私下说：我要跳梅林")
        )

        val otherTimeline = TimelineBuilder.build(
            VisibleEventPolicy.forPerspective(listOf(privateClaim), Perspective.OBSERVER_AT_TIME, 5)
        )
        assertTrue("其他观察者看不到", otherTimeline.isEmpty())
    }
}
