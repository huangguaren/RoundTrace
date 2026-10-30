package com.roundtrace.app.export

import com.roundtrace.app.core.export.ExportOptions
import com.roundtrace.app.core.export.ImportFailureReason
import com.roundtrace.app.core.export.ImportResult
import com.roundtrace.app.core.export.MatchJsonCodec
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.PrivateNoteAdded
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.TeamProposalResolved
import com.roundtrace.app.core.model.TeamVoteRecorded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON 编解码单测（TASKS.md T64 / TDD §10.3 D02、D04）。
 *
 * 断言口径：
 * - 往返后**座位、事件条数、提案/投票/任务结果、真实身份**一致；
 * - 坏输入必须返回可读 `Failure`，且**不抛异常**；
 * - 默认导出必须脱敏（真实身份 + 私人笔记都不出现）。
 */
class JsonCodecTest {

    private val codec = MatchJsonCodec()

    // ————————————————————————————————————————————————————————
    // D02 · 往返一致
    // ————————————————————————————————————————————————————————

    @Test
    fun `round trip keeps events roles and team votes`() {
        val original = ExportFixtures.revealedBundle()
        val text = codec.encode(original, ExportOptions(includeSecrets = true, includePrivateNotes = true))

        val result = codec.decode(text)
        assertTrue("解码应成功：$result", result is ImportResult.Success)
        val imported = (result as ImportResult.Success).bundle

        assertEquals(original.config.playerCount, imported.config.playerCount)
        assertEquals(original.config.mySeat, imported.config.mySeat)
        assertEquals(original.config.initialLeaderSeat, imported.config.initialLeaderSeat)
        assertEquals(original.config.roleSlots, imported.config.roleSlots)
        assertEquals(original.events.size, imported.events.size)

        // 事件顺序与类型逐个对齐
        assertEquals(
            original.events.map { it.type },
            imported.events.map { it.type }
        )

        // 队伍投票：第 1 任务全票通过（questCycle 自带）+ 第 2 任务两次（3:5 否决 / 7:1 通过）
        val votes = imported.events.filterIsInstance<TeamVoteRecorded>()
        assertEquals(3, votes.size)
        assertEquals(8, votes[0].approveCount)
        assertEquals(0, votes[0].rejectCount)
        assertEquals(3, votes[1].approveCount)
        assertEquals(5, votes[1].rejectCount)
        assertEquals(7, votes.last().approveCount)
        assertEquals(1, votes.last().rejectCount)
        // 决议：先全票通过（第 1 任务）、再否决（第 2 任务第 1 次）、后通过（第 2 任务第 2 次）
        val resolved = imported.events.filterIsInstance<TeamProposalResolved>()
        assertEquals(3, resolved.size)
        assertEquals(ProposalDecision.APPROVED, resolved[0].decision)
        assertEquals(ProposalDecision.REJECTED, resolved[1].decision)
        assertEquals(ProposalDecision.APPROVED, resolved.last().decision)

        // 任务结果：1 张失败牌
        val missions = imported.events.filterIsInstance<MissionResolved>()
        assertEquals(2, missions.size)
        assertEquals(0, missions.first().failCount)
        assertEquals(1, missions.last().failCount)
        assertEquals(listOf(2, 3, 4, 6), missions.last().teamSeats)

        // 真实身份（授权导出时可还原）
        val roles = imported.roleAssignments
        assertEquals(RoleId.MERLIN, roles.first { it.seat == 1 }.roleId)
        assertEquals(RoleId.MORDRED, roles.first { it.seat == 4 }.roleId)
        assertEquals(original.roleAssignments.size, roles.size)

        // 私人笔记（授权导出时可还原）
        assertEquals(1, imported.events.filterIsInstance<PrivateNoteAdded>().size)
    }

    @Test
    fun `preview reports counts for two stage import`() {
        val text = codec.encode(
            ExportFixtures.revealedBundle(),
            ExportOptions(includeSecrets = true, includePrivateNotes = true)
        )
        val preview = (codec.decode(text) as ImportResult.Success).preview

        assertEquals(8, preview.playerCount)
        assertEquals(2, preview.questCount)
        assertEquals(ExportFixtures.bundle().events.size, preview.eventCount)
        assertEquals("avalon.standard", preview.rulesetId)
    }

    // ————————————————————————————————————————————————————————
    // 稳定输出
    // ————————————————————————————————————————————————————————

    @Test
    fun `export writes fixed field order and app name`() {
        val text = codec.encode(ExportFixtures.revealedBundle())
        val lines = text.lines()

        assertEquals("{", lines.first())
        assertTrue("首字段必须是 schemaVersion：${lines[1]}", lines[1].trim().startsWith("\"schemaVersion\""))
        assertTrue(text.contains("\"app\": \"RoundTrace\""))
        assertTrue(text.contains("\"schemaVersion\": 1"))
        // 缩进稳定（2 空格）
        assertTrue(lines[1].startsWith("  \""))
    }

    @Test
    fun `unknown keys are ignored on import`() {
        val text = codec.encode(ExportFixtures.revealedBundle(), ExportOptions(includeSecrets = true))
            .replace("\"app\": \"RoundTrace\",", "\"app\": \"RoundTrace\",\n  \"futureField\": 42,")

        val result = codec.decode(text)
        assertTrue("新增未知字段不应导致失败：$result", result is ImportResult.Success)
    }

    // ————————————————————————————————————————————————————————
    // 默认脱敏
    // ————————————————————————————————————————————————————————

    @Test
    fun `default options strip secrets and private notes`() {
        val unrevealed = ExportFixtures.bundle(revealed = false)
        val text = codec.encode(unrevealed) // 默认 ExportOptions()
        val result = codec.decode(text) as ImportResult.Success

        assertTrue("未揭晓身份不得导出", result.bundle.roleAssignments.isEmpty())
        assertEquals(0, result.bundle.events.filterIsInstance<PrivateNoteAdded>().size)
        assertFalse(text.contains(ExportFixtures.PRIVATE_NOTE_TEXT))
        assertFalse("未揭晓时不得导出任何真实身份", text.contains("\"roles\": [\n"))
        assertTrue(text.contains("\"roles\": []"))
    }

    @Test
    fun `revealed roles survive default options`() {
        val text = codec.encode(ExportFixtures.revealedBundle())
        val result = codec.decode(text) as ImportResult.Success

        // 已揭晓 = 公开事实，默认导出保留
        assertEquals(8, result.bundle.roleAssignments.size)
        // 但私人笔记仍不导出
        assertEquals(0, result.bundle.events.filterIsInstance<PrivateNoteAdded>().size)
    }

    @Test
    fun `null and zero fail count stay distinct`() {
        val text = codec.encode(ExportFixtures.revealedBundle(), ExportOptions(includeSecrets = true))
        val result = codec.decode(text) as ImportResult.Success
        val missions = result.bundle.events.filterIsInstance<MissionResolved>()

        assertEquals("0 = 已知零失败牌", 0, missions.first().failCount)
        assertEquals("1 = 已知一张", 1, missions.last().failCount)
        assertNotNull(missions.first().failCount)
    }

    // ————————————————————————————————————————————————————————
    // D04 · 坏输入
    // ————————————————————————————————————————————————————————

    @Test
    fun `broken json returns invalid json failure`() {
        val result = codec.decode("{ not really json")
        assertTrue(result is ImportResult.Failure)
        assertEquals(ImportFailureReason.INVALID_JSON, (result as ImportResult.Failure).reason)
    }

    @Test
    fun `unknown schema version is rejected`() {
        val text = codec.encode(ExportFixtures.revealedBundle()).replace(
            "\"schemaVersion\": 1",
            "\"schemaVersion\": 99"
        )
        val result = codec.decode(text) as ImportResult.Failure
        assertEquals(ImportFailureReason.UNSUPPORTED_SCHEMA_VERSION, result.reason)
    }

    @Test
    fun `missing required field is rejected`() {
        val result = codec.decode("""{"schemaVersion":1,"app":"RoundTrace"}""")
        val failure = result as ImportResult.Failure
        assertEquals(ImportFailureReason.MISSING_FIELD, failure.reason)
    }

    @Test
    fun `missing schema version is rejected`() {
        val result = codec.decode("""{"app":"RoundTrace"}""") as ImportResult.Failure
        assertEquals(ImportFailureReason.MISSING_FIELD, result.reason)
    }

    @Test
    fun `type mismatch is rejected`() {
        val result = codec.decode("""{"schemaVersion":"one"}""") as ImportResult.Failure
        assertEquals(ImportFailureReason.TYPE_MISMATCH, result.reason)
    }

    @Test
    fun `foreign app file is rejected`() {
        val text = codec.encode(ExportFixtures.revealedBundle())
            .replace("\"app\": \"RoundTrace\"", "\"app\": \"OtherApp\"")
        val result = codec.decode(text) as ImportResult.Failure
        assertEquals(ImportFailureReason.INVALID_JSON, result.reason)
    }
}
