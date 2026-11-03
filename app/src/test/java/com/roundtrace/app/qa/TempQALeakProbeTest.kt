package com.roundtrace.app.qa

import com.roundtrace.app.core.model.BeliefType
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.model.PrivateNoteAdded
import com.roundtrace.app.core.model.QuestStarted
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.replay.PlayerTraceBuilder
import com.roundtrace.app.core.replay.PlayerTraceCategory
import com.roundtrace.app.core.replay.VisibleEventPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA 临时探针（team-lead 追加项）：
 * A. PRIVATE_OBSERVER 的 PlayerClaimed 是否从 PUBLIC 视角漏出「被提及」条目；
 * B. B31 数值边界（occurredOrder >> at，availableFromOrder <= at）。
 */
class TempQALeakProbeTest {

    private val n = 8
    private val end = 999

    private fun trace(
        events: List<MatchEvent>,
        seat: Int,
        at: Int = end,
        mode: Perspective = Perspective.PUBLIC,
        observer: Int? = null
    ) = PlayerTraceBuilder.build(events, seat, at, mode, observer, n)

    private fun publicFiller(orders: IntRange): List<QuestStarted> = orders.map { o ->
        QuestStarted(
            eventId = EventId("f-$o"), occurredOrder = o, recordedAt = o * 1_000L,
            questNo = 1, leaderSeat = 2
        )
    }

    /** 观察者 1 号私录的声称：声称自己是梅林，点名 5 号，正文含私密内容。 */
    private fun privateClaim(
        id: String = "claim-p",
        speaker: Int = 1,
        targets: List<Int> = listOf(5),
        order: Int = 4,
        role: RoleId? = RoleId.MERLIN,
        text: String = "私密：5 号看着像坏人"
    ) = PlayerClaimed(
        eventId = EventId(id),
        occurredOrder = order,
        recordedAt = order * 1_000L,
        visibility = EventVisibility.PRIVATE_OBSERVER,
        questNo = 1,
        speakerSeat = speaker,
        claimType = ClaimType.ROLE_CLAIM,
        claimedRole = role,
        targetSeats = targets,
        text = text
    )

    private fun note(id: String, order: Int, availableFrom: Int, observer: Int = 1) = PrivateNoteAdded(
        eventId = EventId(id), occurredOrder = order, recordedAt = order * 1_000L,
        visibility = EventVisibility.PRIVATE_OBSERVER, questNo = 1, observerSeat = observer,
        beliefType = BeliefType.SUSPECT, targetSeats = listOf(5), text = "私密笔记QA",
        availableFromOrder = availableFrom
    )

    // ————— A1：PUBLIC 视角硬红线 —————
    @Test
    fun privateClaimNeverLeaksUnderPublicPerspective() {
        val claim = privateClaim()
        val events: List<MatchEvent> = publicFiller(1..8) + claim

        // 先确认策略层：PUBLIC 下这条私有声称确实不可见（ownerSeatOf 判给 speakerSeat=1）
        assertFalse(
            "PUBLIC 下该私有声称必须不可见",
            VisibleEventPolicy.isVisibleTo(claim, Perspective.PUBLIC, 1)
        )

        (1..n).forEach { seat ->
            val items = trace(events, seat, mode = Perspective.PUBLIC, observer = 1)
            assertEquals(
                "PUBLIC 下 $seat 号不得因私有声称产出 SPEAKER/MENTIONED：${items.map { it.title }}",
                0,
                items.count {
                    it.category == PlayerTraceCategory.SPEAKER ||
                        it.category == PlayerTraceCategory.MENTIONED
                }
            )
            items.forEach { item ->
                assertFalse(
                    "PUBLIC 泄漏了私有声称正文：${item.title} / ${item.detail}",
                    item.title.contains("私密") || item.detail.contains("私密")
                )
                assertFalse(
                    "PUBLIC 泄漏了声称的角色：${item.title} / ${item.detail}",
                    item.detail.contains("梅林")
                )
            }
        }
        println("[E] PUBLIC 视角下 私有声称 → 各座位 SPEAKER/MENTIONED 条数 = " +
            (1..n).map { s ->
                s to trace(events, s, mode = Perspective.PUBLIC, observer = 1).count {
                    it.category == PlayerTraceCategory.SPEAKER || it.category == PlayerTraceCategory.MENTIONED
                }
            })
    }

    // ————— A2：OBSERVER 视角下的「被提及」是否成立、是否泄漏 —————
    /**
     * 【断言已按 P1 修复改写】
     * 旧期望：观察者本人（也是声称者）可在 5 号轨迹里看到「被提及」1 条。
     * 新期望（更严格）：私有声称 `PRIVATE_OBSERVER` 只在**发言者本人**的轨迹里出现，
     * 不得从「被提及」这条侧门进到别人的参与记录（与 `PrivateNoteAdded` 同一保守口径）。
     */
    @Test
    fun observerPerspectiveMentionedItemContent() {
        val events: List<MatchEvent> = publicFiller(1..8) + privateClaim()

        // 观察者本人（1 号，也是声称者）看 5 号：不得产出「被提及」
        assertEquals(
            "私有声称不得进到 5 号的参与记录",
            0,
            trace(events, 5, mode = Perspective.OBSERVER_AT_TIME, observer = 1)
                .count { it.category == PlayerTraceCategory.MENTIONED }
        )
        // 本人自己的轨迹里：有且仅有 1 条 SPEAKER，并带私有来源标记
        val own = trace(events, 1, mode = Perspective.OBSERVER_AT_TIME, observer = 1)
            .filter { it.category == PlayerTraceCategory.SPEAKER }
        assertEquals("1 号本人应看到自己那条私有声称", 1, own.size)
        assertEquals(EventVisibility.PRIVATE_OBSERVER, own.single().sourceVisibility)
        println("[E] OBSERVER(1号) 自己的私有声称条目 = title[${own.single().title}] " +
            "detail[${own.single().detail}] factLevel=${own.single().factLevel} " +
            "sourceVisibility=${own.single().sourceVisibility}")

        // 非归属观察者：看不到
        assertEquals("观察者 2 号看不到 1 号的私有声称", 0,
            trace(events, 5, mode = Perspective.OBSERVER_AT_TIME, observer = 2)
                .count { it.category == PlayerTraceCategory.MENTIONED })
        // 别人私录的、点名我的声称，不得泄漏给我
        val claimBy2 = privateClaim(id = "claim-2", speaker = 2, targets = listOf(1), order = 6)
        assertEquals("别人私录的声称不得泄漏给被点名者", 0,
            trace(listOf(claimBy2), 1, mode = Perspective.OBSERVER_AT_TIME, observer = 1)
                .count { it.category == PlayerTraceCategory.MENTIONED })
        // GOD：私有声称仍只在本人轨迹，不进 5 号
        assertEquals("GOD 下私有声称也不得进 5 号轨迹", 0,
            trace(events, 5, mode = Perspective.GOD_AFTER_REVEAL)
                .count { it.category == PlayerTraceCategory.MENTIONED })
        assertEquals("GOD 下 1 号本人仍可见自己的私有声称", 1,
            trace(events, 1, mode = Perspective.GOD_AFTER_REVEAL)
                .count { it.category == PlayerTraceCategory.SPEAKER })

        println("[E] 私有声称可见性矩阵（MENTIONED 条数） = " + listOf(
            "PUBLIC" to trace(events, 5, end, Perspective.PUBLIC, 1),
            "OBS1→5号" to trace(events, 5, end, Perspective.OBSERVER_AT_TIME, 1),
            "OBS2→5号" to trace(events, 5, end, Perspective.OBSERVER_AT_TIME, 2),
            "GOD→5号" to trace(events, 5, end, Perspective.GOD_AFTER_REVEAL, null)
        ).joinToString(" ; ") { (k, v) -> "$k=${v.count { it.category == PlayerTraceCategory.MENTIONED }}" })
        println("[E] 私有声称可见性矩阵（1 号本人 SPEAKER 条数） = " + listOf(
            "OBS1→1号" to trace(events, 1, end, Perspective.OBSERVER_AT_TIME, 1),
            "GOD→1号" to trace(events, 1, end, Perspective.GOD_AFTER_REVEAL, null)
        ).joinToString(" ; ") { (k, v) -> "$k=${v.count { it.category == PlayerTraceCategory.SPEAKER }}" })
    }

    /**
     * 【断言已按 P1 修复改写】
     * 旧期望：私有来源的「被提及」条目与公开来源文案完全一致（即：泄漏且无标注，QA 记录的缺陷）。
     * 新期望（更严格）：私有声称不再产出任何「被提及」条目；同时条目的 `sourceVisibility`
     * 必须原样带上来源事件的可见性，供 UI 渲染「来自私有记录 · 仅本人可见」。
     */
    @Test
    fun mentionedItemDoesNotMarkPrivateSource() {
        val privateEvents: List<MatchEvent> = publicFiller(1..8) + privateClaim()
        val publicClaim = privateClaim(id = "claim-pub").copy(visibility = EventVisibility.PUBLIC)
        val publicEvents: List<MatchEvent> = publicFiller(1..8) + publicClaim

        // 私有声称：5 号轨迹里 0 条（比「有条目但加标注」更严格）
        assertEquals(
            "私有声称不得产出「被提及」条目",
            0,
            trace(privateEvents, 5, end, Perspective.OBSERVER_AT_TIME, 1)
                .count { it.category == PlayerTraceCategory.MENTIONED }
        )
        // 私有声称只在本人轨迹里出现，且必须带私有来源标记
        val ownPrivate = trace(privateEvents, 1, end, Perspective.OBSERVER_AT_TIME, 1)
            .single { it.category == PlayerTraceCategory.SPEAKER }
        assertEquals(EventVisibility.PRIVATE_OBSERVER, ownPrivate.sourceVisibility)

        // 公开声称：仍产出「被提及」，来源是 PUBLIC（不得误挂私有标记）
        val fromPublic = trace(publicEvents, 5, end, Perspective.PUBLIC, 1)
            .single { it.category == PlayerTraceCategory.MENTIONED }
        assertEquals(EventVisibility.PUBLIC, fromPublic.sourceVisibility)

        println("[E] 私有来源条目 sourceVisibility = ${ownPrivate.sourceVisibility}；" +
            "公开来源条目 sourceVisibility = ${fromPublic.sourceVisibility}")
    }

    // ————— B：B31 数值边界 —————
    @Test
    fun backfilledNoteNeverPointsBeyondAt() {
        val noteEvents: List<MatchEvent> = publicFiller(1..8) + note("n", order = 900, availableFrom = 3)
        // 不提前泄漏
        listOf(0, 1, 2).forEach { at ->
            assertEquals("at=$at < availableFromOrder=3 时不得出现", 0,
                trace(noteEvents, 1, at, Perspective.OBSERVER_AT_TIME, 1)
                    .count { it.category == PlayerTraceCategory.PRIVATE_NOTE })
        }
        // 出现时定位点 = availableFromOrder，且永不晚于 at
        listOf(3, 5, 100, 900).forEach { at ->
            val notes = trace(noteEvents, 1, at, Perspective.OBSERVER_AT_TIME, 1)
                .filter { it.category == PlayerTraceCategory.PRIVATE_NOTE }
            assertEquals("at=$at 应可见", 1, notes.size)
            assertEquals("at=$at 定位点必须恒为 availableFromOrder=3", 3, notes.single().occurredOrder)
            assertTrue("定位点不得晚于 at", notes.single().occurredOrder <= at)
        }
        // 全列表里不得出现任何晚于 at 的定位点
        (1..n).forEach { seat ->
            val at = 5
            assertTrue(
                "at=$at 时 $seat 号出现越界定位点：" +
                    trace(noteEvents, seat, at, Perspective.OBSERVER_AT_TIME, 1).map { it.occurredOrder },
                trace(noteEvents, seat, at, Perspective.OBSERVER_AT_TIME, 1).all { it.occurredOrder <= at }
            )
        }
        // 反向：occurredOrder 早、availableFromOrder 晚
        val late = publicFiller(1..8) + note("l", order = 2, availableFrom = 900)
        assertEquals("availableFromOrder=900 时 at=5 不得提前泄漏", 0,
            trace(late, 1, 5, Perspective.OBSERVER_AT_TIME, 1)
                .count { it.category == PlayerTraceCategory.PRIVATE_NOTE })
        println("[E] B31: 笔记(occurredOrder=900/availableFromOrder=3) at=2/3/5/100 -> " +
            listOf(2, 3, 5, 100).map { at ->
                trace(noteEvents, 1, at, Perspective.OBSERVER_AT_TIME, 1)
                    .filter { it.category == PlayerTraceCategory.PRIVATE_NOTE }
                    .let { "at$at:${it.size}条@${it.map { x -> x.occurredOrder }}" }
            })
    }
}
