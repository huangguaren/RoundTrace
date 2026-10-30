package com.roundtrace.app.data

import com.roundtrace.app.core.data.EventRevisionPolicy
import com.roundtrace.app.core.data.EventRevisionPolicy.RevisionFailure
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.QuestStarted
import com.roundtrace.app.core.model.TeamProposed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R14 的「修订策略」纯逻辑部分（TASKS.md T26①）。
 *
 * 红线：修订必须可追溯（单链），且**不允许跨事件类型/跨任务修订**——
 * 否则「把第 2 轮投票改成任务结果」这类脏数据会静默污染统计。
 */
class EventRevisionPolicyTest {

    private fun questStarted(
        id: String,
        order: Int,
        questNo: Int = 1,
        retracted: Boolean = false,
        supersedesId: EventId? = null
    ): QuestStarted = QuestStarted(
        eventId = EventId(id),
        occurredOrder = order,
        recordedAt = 0L,
        visibility = EventVisibility.PUBLIC,
        supersedesId = supersedesId,
        retracted = retracted,
        questNo = questNo,
        leaderSeat = 1
    )

    private fun teamProposed(
        id: String,
        order: Int,
        questNo: Int = 1,
        teamSeats: List<Int> = listOf(1, 2, 3),
        retracted: Boolean = false,
        supersedesId: EventId? = null
    ): TeamProposed = TeamProposed(
        eventId = EventId(id),
        occurredOrder = order,
        recordedAt = 0L,
        visibility = EventVisibility.PUBLIC,
        supersedesId = supersedesId,
        retracted = retracted,
        questNo = questNo,
        proposalNo = 1,
        leaderSeat = 1,
        teamSeats = teamSeats
    )

    private fun missionResolved(
        id: String,
        order: Int,
        questNo: Int = 1,
        supersedesId: EventId? = null
    ): MissionResolved = MissionResolved(
        eventId = EventId(id),
        occurredOrder = order,
        recordedAt = 0L,
        visibility = EventVisibility.PUBLIC,
        supersedesId = supersedesId,
        retracted = false,
        questNo = questNo,
        teamSeats = listOf(1, 2, 3),
        teamSizeExpected = 3,
        failCount = 1,
        observedOutcome = MissionOutcome.FAILED
    )

    @Test
    fun `R14 同对局同类型且目标未失效时允许修订`() {
        val target = teamProposed("t1", order = 2, teamSeats = listOf(1, 2, 3))
        val replacement = teamProposed(
            "r1",
            order = 9,
            teamSeats = listOf(1, 2, 4),
            supersedesId = EventId("t1")
        )

        val check = EventRevisionPolicy.check(target, replacement, sameMatch = true)
        assertTrue("同类型同任务的修订应被允许，实际失败原因=${check.failure}", check.allowed)
        assertTrue(EventRevisionPolicy.canSupersede(target, replacement, sameMatch = true))
    }

    @Test
    fun `R14 不允许把任务开始事件替换成任务结果事件`() {
        val target: MatchEvent = questStarted("t1", order = 1)
        val replacement: MatchEvent = missionResolved("r1", order = 9, supersedesId = EventId("t1"))

        val check = EventRevisionPolicy.check(target, replacement, sameMatch = true)
        assertFalse("跨事件类型的修订必须被拒绝", check.allowed)
        assertEquals(
            "拒绝原因应为 TYPE_MISMATCH",
            RevisionFailure.TYPE_MISMATCH,
            check.failure
        )
    }

    @Test
    fun `R14 不允许修订已失效的事件`() {
        val target = teamProposed("t1", order = 2, retracted = true)
        val replacement = teamProposed("r1", order = 9, supersedesId = EventId("t1"))

        val check = EventRevisionPolicy.check(target, replacement, sameMatch = true)
        assertFalse("已 retracted 的事件不得再被修订（保持单链）", check.allowed)
        assertEquals(RevisionFailure.TARGET_RETRACTED, check.failure)
    }

    @Test
    fun `R14 不允许跨对局修订`() {
        val target = teamProposed("t1", order = 2)
        val replacement = teamProposed("r1", order = 9, supersedesId = EventId("t1"))
        val check = EventRevisionPolicy.check(target, replacement, sameMatch = false)
        assertFalse(check.allowed)
        assertEquals(RevisionFailure.NOT_SAME_MATCH, check.failure)
    }

    @Test
    fun `R14 不允许事件自我替代`() {
        val target = teamProposed("t1", order = 2)
        val check = EventRevisionPolicy.check(target, target, sameMatch = true)
        assertFalse(check.allowed)
        assertEquals(RevisionFailure.SELF_SUPERSEDE, check.failure)
    }

    @Test
    fun `R14 不允许跨任务修订`() {
        val target = teamProposed("t1", order = 2, questNo = 2)
        val replacement = teamProposed("r1", order = 9, questNo = 4, supersedesId = EventId("t1"))
        val check = EventRevisionPolicy.check(target, replacement, sameMatch = true)
        assertFalse(check.allowed)
        assertEquals(RevisionFailure.QUEST_MISMATCH, check.failure)
    }

    @Test
    fun `R14 替代事件的 supersedesId 必须指向目标`() {
        val target = teamProposed("t1", order = 2)
        val replacement = teamProposed("r1", order = 9, supersedesId = EventId("other"))
        val check = EventRevisionPolicy.check(target, replacement, sameMatch = true)
        assertFalse(check.allowed)
        assertEquals(RevisionFailure.SUPERSEDE_TARGET_MISMATCH, check.failure)
    }

    @Test
    fun `R14 任务结果只能被另一条任务结果修订`() {
        val target = missionResolved("m1", order = 5)
        val good = missionResolved("m2", order = 9, supersedesId = EventId("m1"))
        val bad = teamProposed("p2", order = 9, supersedesId = EventId("m1"))

        assertTrue(EventRevisionPolicy.canSupersede(target, good, sameMatch = true))
        assertFalse(
            "任务结果不能被组队提案替代",
            EventRevisionPolicy.canSupersede(target, bad, sameMatch = true)
        )
    }

    @Test
    fun `R14 失败原因说明不为空便于日志排查`() {
        val target = questStarted("t1", order = 1)
        val replacement = missionResolved("r1", order = 2)
        val check = EventRevisionPolicy.check(target, replacement, sameMatch = true)
        assertTrue("失败原因应有可读说明", check.reasonOrEmpty.isNotEmpty())
    }
}
