package com.roundtrace.app.core.ui.identity

import com.roundtrace.app.core.model.Participant
import com.roundtrace.app.core.model.SeatId

/**
 * 某个座位的**只读展示信息**（UX-ID-01 / Phase A）。
 *
 * 这里是「座位号—昵称—头像」的唯一格式化出口，供复盘、发言、揭晓三处复用。
 *
 * ## 三条硬规则
 * 1. **seat 是唯一身份键**。昵称可以重复、可以改、可以缺失，都不能用来识别玩家；
 * 2. **只承载展示信息**。本类型没有 roleId / alignment / 阵营 / 真相字段，
 *    昵称里出现的角色词也只是用户自己取的名字，不构成身份事实；
 * 3. **降级是确定性的**。任一字段缺失都产出可直接渲染的文案，不依赖图片能否加载成功。
 *
 * 本文件是纯 Kotlin：不 import Android、Room、Context 或资源，也不读取 `role_assignments`。
 * 头像 URI 只做「空值/空白」归一化；URI 是否被撤权、能否解码由 [SeatAvatarImage][*] 在 UI 层
 * 负责回退，纯函数不假装能验证图片。
 *
 * [*]: com.roundtrace.app.core.ui.avatar.SeatAvatarImage
 *
 * @param seat 座位号（唯一身份键）
 * @param name 规范化后的昵称；`null` = 未命名
 * @param avatarUri 规范化后的头像 URI；`null` = 无头像 / 空白 URI
 */
data class SeatDisplay(
    val seat: SeatId,
    val name: String?,
    val avatarUri: String?
) {
    /** 是否有可用昵称。 */
    val hasName: Boolean get() = name != null

    /** 是否提供了头像 URI（不代表一定能解码成功）。 */
    val hasAvatar: Boolean get() = avatarUri != null

    /** 「N号」，座位的常驻标识，任何降级路径都必须保留。 */
    val seatNumberText: String get() = "${seat}号"

    /**
     * 主展示文案：有昵称「阿华（2号）」，无昵称「2号」。
     *
     * 座位号始终拼在末尾，因此同名玩家天然可区分（ID-02）。
     */
    val label: String get() = if (name != null) "$name（${seatNumberText}）" else seatNumberText

    /** 头像占位用的短标识：昵称首字；无昵称时为座位号数字。 */
    val compactLabel: String get() = name?.take(1) ?: seat.toString()

    /**
     * 无障碍说明：含**完整**昵称与座位号。
     *
     * UI 对长昵称做单行省略，但不能因此让读屏用户丢掉完整名字（PRD §B）。
     */
    val contentDescription: String get() =
        if (name != null) "$name，${seatNumberText}座位" else "${seatNumberText}座位"

    companion object {
        /** 参与者缺失或越界时的确定性占位；不读取任何身份真相。 */
        fun placeholder(seat: SeatId): SeatDisplay = SeatDisplay(seat, null, null)
    }
}

/**
 * 一场对局的只读座位身份映射。
 *
 * 由 [SeatDisplayResolver.resolve] 产出，之后不可变。下游只通过 [display] / [formatSeat]
 * 读取，避免各处自己 `associate` 出不同的降级口径。
 *
 * @property bySeatCount 有效（在 1..playerCount 内、按 seat 去重后）的条目数
 */
class SeatIdentity internal constructor(
    private val bySeat: Map<SeatId, SeatDisplay>,
    val playerCount: Int
) {

    val size: Int get() = bySeat.size

    val bySeatCount: Int get() = bySeat.size

    /** 只有解析到的座位才有值；缺失/越界返回 null，不臆造参与者。 */
    operator fun get(seat: SeatId): SeatDisplay? = bySeat[seat]

    /** 任何座位都有确定展示：缺失时返回占位（「N号」），绝不抛异常。 */
    fun display(seat: SeatId): SeatDisplay =
        bySeat[seat] ?: SeatDisplay.placeholder(seat)

    /** 单个 seat 的结构化展示文案，可直接作为 Builder 的格式化回调。 */
    fun formatSeat(seat: SeatId): String = display(seat).label

    /**
     * 座位列表的展示文案（队伍名单 / 目标名单）。
     *
     * **只在模型里的 seat 字段上调用**，绝不用于替换自由文本：
     * 玩家原话「2号很可疑」、任务序号、票数、失败牌数都不是座位引用。
     */
    fun formatSeats(seats: List<SeatId>): String =
        if (seats.isEmpty()) "未记录" else seats.joinToString(" / ") { formatSeat(it) }

    /** 供既有 `SeatPicker` 使用的昵称映射（无昵称给 null，由组件自行降级）。 */
    val seatLabels: Map<SeatId, String?> get() = bySeat.mapValues { it.value.name }

    /** 供既有 `SeatPicker` / `SeatAvatarImage` 使用的头像映射。 */
    val seatAvatars: Map<SeatId, String?> get() = bySeat.mapValues { it.value.avatarUri }

    /** 全部有效座位（升序），用于 5–10 人横滑列表。 */
    val seats: List<SeatId> get() = (1..playerCount).toList()
}

/**
 * 从 [MatchBundle.participants][*] 建只读展示映射的纯函数。
 *
 * [*]: com.roundtrace.app.core.data.MatchBundle
 *
 * 归一化顺序（保证脏数据也不会串头像）：
 * 1. 丢弃 `seat !in 1..playerCount` 的行（导入数据可能越界）；
 * 2. 同一 seat 出现多行时**只取第一行**（`associate` 会覆盖，这里显式收敛）；
 * 3. 昵称 / URI 去空白，空白视为缺失；
 * 4. 缺失的座位不进映射，由 [SeatIdentity.display] 返回占位。
 */
object SeatDisplayResolver {

    fun resolve(participants: List<Participant>, playerCount: Int): SeatIdentity {
        val bySeat = LinkedHashMap<SeatId, SeatDisplay>()
        if (playerCount > 0) {
            participants.forEach { participant ->
                val seat = participant.seat
                if (seat in 1..playerCount && !bySeat.containsKey(seat)) {
                    bySeat[seat] = SeatDisplay(
                        seat = seat,
                        name = participant.displayName?.trim()?.takeIf { it.isNotEmpty() },
                        avatarUri = participant.avatarUri?.trim()?.takeIf { it.isNotEmpty() }
                    )
                }
            }
        }
        return SeatIdentity(bySeat, playerCount)
    }

    /** 没有任何参与者时的空映射；所有座位走占位。 */
    fun empty(playerCount: Int): SeatIdentity = resolve(emptyList(), playerCount)
}
