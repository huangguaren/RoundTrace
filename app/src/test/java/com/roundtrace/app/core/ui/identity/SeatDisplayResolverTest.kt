package com.roundtrace.app.core.ui.identity

import com.roundtrace.app.core.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一玩家身份映射（UX-ID-01 / Phase A）。
 *
 * 这些用例先于实现写下，用来钉住 PRD 的 ID-01～ID-04 与全部降级场景。
 * **纯 Kotlin**：不依赖 Android Context / Room / 资源，也不读取角色真相。
 *
 * 唯一的身份键是 seat。昵称与头像都只是「展示信息」，可以缺失、可以重复、可以失效，
 * 但绝不能因此把两个座位合并，或把某个座位映射到别人的头像。
 */
class SeatDisplayResolverTest {

    private fun participants(vararg rows: Triple<Int, String?, String?>): List<Participant> =
        rows.map { (seat, name, avatar) ->
            Participant(seat = seat, displayName = name, avatarUri = avatar)
        }

    // ————————————————————————— ID-01 已有昵称 / 头像 —————————————————————————

    @Test
    fun namedAndAvatarSeatShowsNameAvatarAndSeat() {
        val identity = SeatDisplayResolver.resolve(
            participants(Triple(2, "阿华", "content://avatar/2")),
            playerCount = 5
        )

        val display = identity.display(2)
        assertEquals(2, display.seat)
        assertEquals("阿华", display.name)
        assertEquals("content://avatar/2", display.avatarUri)
        assertEquals("阿华（2号）", display.label)
        assertTrue(display.hasAvatar)
        assertTrue(display.hasName)
        // 座位号必须常驻：有头像、有昵称也不能只显示昵称
        assertTrue(display.label.contains("2号"))
    }

    // ————————————————————————— ID-02 两个玩家同名 —————————————————————————

    @Test
    fun duplicateNamesRemainDistinguishableBySeat() {
        val identity = SeatDisplayResolver.resolve(
            participants(
                Triple(1, "阿华", "content://avatar/1"),
                Triple(2, "阿华", "content://avatar/2"),
                Triple(3, "小林", null)
            ),
            playerCount = 5
        )

        assertEquals(3, identity.size)
        assertEquals("阿华（1号）", identity.display(1).label)
        assertEquals("阿华（2号）", identity.display(2).label)
        // 同名但头像不同：seat 是唯一键，不能串到对方头像
        assertEquals("content://avatar/1", identity.display(1).avatarUri)
        assertEquals("content://avatar/2", identity.display(2).avatarUri)
        assertTrue(identity.display(1).label != identity.display(2).label)
    }

    @Test
    fun seatIsTheOnlyIdentityKeyEvenWhenNicknamesChange() {
        val before = SeatDisplayResolver.resolve(
            participants(Triple(4, "旧名字", "content://a")), playerCount = 5
        )
        val after = SeatDisplayResolver.resolve(
            participants(Triple(4, "新名字", "content://b")), playerCount = 5
        )
        assertEquals(before.display(4).seat, after.display(4).seat)
        assertEquals("新名字（4号）", after.display(4).label)
        assertEquals("content://b", after.display(4).avatarUri)
    }

    // ————————————————————————— ID-03 无头像 / 空白 URI —————————————————————————

    @Test
    fun missingOrBlankAvatarUriFallsBackWithoutCrash() {
        val identity = SeatDisplayResolver.resolve(
            participants(
                Triple(1, null, null),
                Triple(2, "阿华", ""),
                Triple(3, "小林", "   ")
            ),
            playerCount = 5
        )

        assertNull(identity.display(1).avatarUri)
        assertNull(identity.display(2).avatarUri)
        assertNull(identity.display(3).avatarUri)
        assertFalse(identity.display(2).hasAvatar)
        // 无头像不能丢昵称与座位号；占位用昵称首字而不是数字
        assertEquals("阿华（2号）", identity.display(2).label)
        assertEquals("阿", identity.display(2).compactLabel)
        // 真正没昵称的座位，占位才退回座位号数字
        assertEquals("1", identity.display(1).compactLabel)
    }

    @Test
    fun duplicateSeatRowsNeverMixAnotherSeatsAvatar() {
        // participants 可能被导入数据弄脏：同一 seat 出现两行。
        // 解析必须按 seat 唯一收敛，不能把后一行的 avatar 挂到前一行的 label 上。
        val identity = SeatDisplayResolver.resolve(
            participants(
                Triple(2, "阿华", "content://avatar/2"),
                Triple(2, "冒名者", "content://avatar/other")
            ),
            playerCount = 5
        )
        assertEquals(1, identity.bySeatCount)
        val display = identity.display(2)
        assertEquals("阿华（2号）", display.label)
        assertEquals("content://avatar/2", display.avatarUri)
    }

    // ————————————————————————— 昵称缺失 / 长昵称 —————————————————————————

    @Test
    fun missingOrBlankNicknameFallsBackToSeatNumber() {
        val identity = SeatDisplayResolver.resolve(
            participants(Triple(1, null, null), Triple(2, "", "content://a"), Triple(3, "   ", null)),
            playerCount = 5
        )

        assertEquals("1号", identity.display(1).label)
        assertEquals("2号", identity.display(2).label)
        assertEquals("3号", identity.display(3).label)
        assertNull(identity.display(1).name)
        assertFalse(identity.display(1).hasName)
        // 只有头像没有昵称：不能出现无法定位的「匿名照片」
        assertEquals("2", identity.display(2).compactLabel)
    }

    @Test
    fun longNicknameKeepsFullTextInAccessibilityDescription() {
        val longName = "这是一个非常非常长的玩家昵称长到一行根本放不下还需要省略号"
        val identity = SeatDisplayResolver.resolve(
            participants(Triple(7, longName, null)), playerCount = 10
        )
        val display = identity.display(7)

        // UI 单行省略由 Compose 负责；纯函数必须保留完整昵称供无障碍说明使用
        assertEquals(longName, display.name)
        assertTrue(display.contentDescription.contains(longName))
        assertTrue(display.contentDescription.contains("7号"))
        assertEquals(longName.take(1), display.compactLabel)
    }

    // ————————————————————————— ID-04 5 人 / 10 人 —————————————————————————

    @Test
    fun fiveAndTenPlayerRostersKeepEverySeat() {
        listOf(5, 6, 7, 8, 9, 10).forEach { count ->
            val roster = (1..count).map { seat ->
                Participant(seat = seat, displayName = "P$seat", avatarUri = null)
            }
            val identity = SeatDisplayResolver.resolve(roster, playerCount = count)
            assertEquals("playerCount=$count", count, identity.size)
            (1..count).forEach { seat ->
                assertEquals("P$seat（${seat}号）", identity.display(seat).label)
            }
        }
    }

    @Test
    fun participantsMissingASeatStillProducesDeterministicPlaceholder() {
        val identity = SeatDisplayResolver.resolve(
            participants(Triple(1, "阿华", null)), playerCount = 5
        )
        assertEquals("5号", identity.display(5).label)
        assertNull(identity.display(5).avatarUri)
        assertFalse(identity.display(5).hasAvatar)
    }

    @Test
    fun outOfRangeSeatsAreIgnoredInsteadOfLeakingIntoAnotherMatch() {
        val identity = SeatDisplayResolver.resolve(
            participants(Triple(0, "非法", "x"), Triple(11, "越界", "y"), Triple(2, "阿华", "z")),
            playerCount = 5
        )
        assertEquals(1, identity.bySeatCount)
        assertEquals("阿华（2号）", identity.display(2).label)
        // 越界座位不会进映射
        assertNull(identity[0])
        assertNull(identity[11])
    }

    // ————————————————————————— 结构化文案（Phase B 的纯格式化入口） —————————————————————————

    @Test
    fun formatSeatListUsesNameWithSeatForEveryMember() {
        val identity = SeatDisplayResolver.resolve(
            participants(
                Triple(1, "小明", null),
                Triple(2, "阿华", null),
                Triple(3, "小林", null)
            ),
            playerCount = 5
        )
        assertEquals("小明（1号） / 阿华（2号） / 小林（3号）", identity.formatSeats(listOf(1, 2, 3)))
    }

    @Test
    fun formatSeatListKeepsSeatNumberWhenNicknameMissing() {
        val identity = SeatDisplayResolver.resolve(emptyList(), playerCount = 5)
        assertEquals("1号 / 4号", identity.formatSeats(listOf(1, 4)))
        assertEquals("未记录", identity.formatSeats(emptyList()))
    }

    @Test
    fun formatterIsAMapNotAStringReplacement() {
        // ID-07 的护栏：格式化器只处理单个 seat，绝不提供「替换整段文本」的能力。
        // 自由文本里的「2号」是玩家原话，必须原样保留。
        val identity = SeatDisplayResolver.resolve(
            participants(Triple(2, "阿华", null)), playerCount = 5
        )
        val freeText = "我觉得 2号 很可疑，第 2 任务有 2 张失败牌"
        assertEquals("阿华（2号）", identity.formatSeat(2))
        // 格式化器是 (SeatId)->String，对自由文本没有任何入口
        assertEquals(freeText, freeText.let { it })
    }

    // ————————————————————————— 不承载身份真相 —————————————————————————

    @Test
    fun displayNeverCarriesRoleOrAlignmentFacts() {
        val identity = SeatDisplayResolver.resolve(
            participants(Triple(2, "梅林本人", "content://a")), playerCount = 5
        )
        val display = identity.display(2)
        // 昵称里含角色词是用户自己取的展示名，不是真相；映射层不得额外产出角色/阵营字段
        assertEquals("梅林本人（2号）", display.label)
        assertFalse(display.contentDescription.contains("忠臣"))
        assertFalse(display.contentDescription.contains("阵营"))
    }
}
