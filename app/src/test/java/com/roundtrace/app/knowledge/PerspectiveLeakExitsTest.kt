package com.roundtrace.app.knowledge

import com.roundtrace.app.core.knowledge.AvalonKnowledgeProvider
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.GamePosition
import com.roundtrace.app.core.model.KnowledgeLabel
import com.roundtrace.app.core.model.Perspective
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.util.MatchBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-1 护栏：观察者**本人**的 `CLAIMED_*` 标签既不能丢、也不能外溢。
 *
 * ## 背景
 *
 * RT-005 把 `claimLabels` 的事件源一刀切成 `publicOnlyEvents` 来堵泄漏，
 * 副作用是「本人私下记的一句『我跳梅林』」在本人视角里也消失了——
 * `seatKnowledge[4].labels` 只剩 `[SELF]`。
 *
 * 修法是给 `buildSeatKnowledge` 传两份事件：
 * - 所有座位 → 公开可见事件（`publicOnlyEvents`）；
 * - **观察者本人那一格** → 当前视角可见事件（`observerVisibleEvents`）。
 *
 * 三条断言分别守住三件事：
 * 1. 本人看得到自己的（不丢）；
 * 2. 别人看不到本人的（不外溢）；
 * 3. 公开事实列表一个字段都不放宽（RT-005 的成果不能被顺手放宽）。
 */
class PerspectiveLeakExitsTest {

    private val provider = AvalonKnowledgeProvider(AvalonScenario.reducer())

    private companion object {
        const val OBSERVER = 4
        const val OTHER_SEAT = 3
        const val PRIVATE_CLAIM_ID = "p2-private-claim-4"
    }

    /** 4 号私下记录「我声称自己是梅林」；同时保留 7 号的**公开**声称作为对照。 */
    private fun builderWithObserverPrivateClaim(): MatchBuilder {
        val builder = AvalonScenario.fullMatch()
        builder.appendRaw(
            PlayerClaimed(
                eventId = EventId(PRIVATE_CLAIM_ID),
                occurredOrder = 900,
                recordedAt = 0L,
                visibility = EventVisibility.PRIVATE_OBSERVER,
                questNo = 2,
                speakerSeat = OBSERVER,
                claimType = ClaimType.ROLE_CLAIM,
                claimedRole = RoleId.MERLIN
            )
        )
        return builder
    }

    private fun endOf(builder: MatchBuilder) =
        GamePosition(builder.build().events.maxOf { it.occurredOrder })

    @Test
    fun observerKeepsOwnPrivateClaimLabel() {
        val builder = builderWithObserverPrivateClaim()
        val snapshot = provider.snapshot(
            match = builder.build(),
            observer = OBSERVER,
            at = endOf(builder),
            mode = Perspective.OBSERVER_AT_TIME
        )

        val ownLabels = snapshot.seatKnowledge.getValue(OBSERVER).labels
        assertTrue(
            "观察者本人必须看到自己那条私有声称产生的 CLAIMED_MERLIN，实际=$ownLabels",
            ownLabels.contains(KnowledgeLabel.CLAIMED_MERLIN)
        )
        assertTrue(
            "SELF 标签不能因为修复而丢失",
            ownLabels.contains(KnowledgeLabel.SELF)
        )
    }

    @Test
    fun observersPrivateClaimDoesNotLeakToOtherSeats() {
        val builder = builderWithObserverPrivateClaim()
        val snapshot = provider.snapshot(
            match = builder.build(),
            observer = OBSERVER,
            at = endOf(builder),
            mode = Perspective.OBSERVER_AT_TIME
        )

        // 3 号在真实角色表里是莫甘娜，本身与「声称梅林」无关；
        // 这里真正要守的是「别人的格子不能被观察者的私有声称污染」。
        val otherLabels = snapshot.seatKnowledge.getValue(3).labels
        assertFalse(
            "3 号不得因为 4 号的私有声称而获得 CLAIMED_MERLIN，实际=$otherLabels",
            otherLabels.contains(KnowledgeLabel.CLAIMED_MERLIN)
        )

        // 换个视角看同一份数据：2 号观察时，4 号的私有声称对他不可见
        val asOtherObserver = provider.snapshot(
            match = builder.build(),
            observer = 2,
            at = endOf(builder),
            mode = Perspective.OBSERVER_AT_TIME
        )
        assertFalse(
            "2 号视角下 4 号不得出现 CLAIMED_MERLIN",
            asOtherObserver.seatKnowledge.getValue(OBSERVER)
                .labels.contains(KnowledgeLabel.CLAIMED_MERLIN)
        )
    }

    @Test
    fun publicFactsStillExcludeThePrivateClaim() {
        val builder = builderWithObserverPrivateClaim()
        val snapshot = provider.snapshot(
            match = builder.build(),
            observer = null,
            at = endOf(builder),
            mode = Perspective.PUBLIC
        )

        assertTrue(
            "RT-005 的成果不得被顺手放宽：公开事实里仍然不能有私有声称",
            snapshot.publicFacts.none { it.id == PRIVATE_CLAIM_ID }
        )
        assertFalse(
            "公开视角下 4 号不得有 CLAIMED_MERLIN",
            snapshot.seatKnowledge.getValue(OBSERVER)
                .labels.contains(KnowledgeLabel.CLAIMED_MERLIN)
        )
        assertTrue(
            "对照组：7 号的公开声称仍应生效",
            snapshot.seatKnowledge.getValue(7).labels.contains(KnowledgeLabel.CLAIMED_PERCIVAL)
        )
    }

    @Test
    fun godViewSeesEveryonesClaims() {
        val builder = builderWithObserverPrivateClaim()
        val revealed = builder.build(revealedAtEpochMs = 1_700_000_100_000L)
        val snapshot = provider.snapshot(
            match = revealed,
            observer = null,
            at = GamePosition(revealed.events.maxOf { it.occurredOrder }),
            mode = Perspective.GOD_AFTER_REVEAL
        )

        assertEquals(
            "上帝视角没有观察者，seatKnowledge 不应因此报错或丢座位",
            revealed.config.playerCount,
            snapshot.seatKnowledge.size
        )
        assertTrue(
            "上帝视角下 7 号的公开声称仍存在",
            snapshot.seatKnowledge.getValue(7).labels.contains(KnowledgeLabel.CLAIMED_PERCIVAL)
        )
    }
}
