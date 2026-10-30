package com.roundtrace.app.core.export

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * JSON 导出契约（TASKS.md T59① / TDD §3.4）。
 *
 * 设计约定：
 * 1. 这是**对外契约**，与内部 `match_events.payloadJson`（`EventCodec`）不是同一个东西：
 *    内部 payload 是存储细节，可以随重构变化；本文件的结构一旦发布，只能靠
 *    [ExportBundle.SCHEMA_VERSION] 升级 + migration，不得静默改字段语义。
 *    因此这里**刻意不复用** `core/database/EventCodec`——那会把 `androidx.room`
 *    拉进本应是纯 Kotlin 的 `core/export`。
 * 2. 字段顺序 = 声明顺序（`prettyPrint` 后输出稳定，便于 diff 与人工核对）。
 * 3. `null` = 未知，`0` = 已知为零；JSON 里两者绝不混用（`explicitNulls = true`）。
 * 4. 事件专有字段放 [ExportEvent.payload]，公共 7 字段提到外层，避免双写漂移。
 */
@Serializable
data class ExportBundle(
    @SerialName("schemaVersion") val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("app") val app: String = APP_NAME,
    @SerialName("gameType") val gameType: String,
    @SerialName("ruleset") val ruleset: ExportRuleset,
    @SerialName("match") val match: ExportMatch,
    @SerialName("participants") val participants: List<ExportParticipant> = emptyList(),
    @SerialName("roles") val roles: List<ExportRole> = emptyList(),
    @SerialName("events") val events: List<ExportEvent> = emptyList(),
    @SerialName("exportOptions") val exportOptions: ExportOptions = ExportOptions()
) {
    companion object {
        /** 当前导出契约版本。**只在结构不兼容时 +1**。 */
        const val SCHEMA_VERSION: Int = 1

        /** 写入 `app` 字段，导入时用来拒绝别的 App 的文件。 */
        const val APP_NAME: String = "RoundTrace"
    }
}

/** 规则包标识。导入时按 `id + version` 解析，解析不到就拒绝（不猜规则）。 */
@Serializable
data class ExportRuleset(
    @SerialName("id") val id: String,
    @SerialName("version") val version: Int
)

/** 对局元信息 + 开局计划。 */
@Serializable
data class ExportMatch(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("playerCount") val playerCount: Int,
    @SerialName("mySeat") val mySeat: Int? = null,
    @SerialName("initialLeaderSeat") val initialLeaderSeat: Int,
    @SerialName("presetId") val presetId: String,
    @SerialName("status") val status: String,
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("updatedAt") val updatedAt: Long,
    /** 身份揭晓时刻；`null` = 尚未揭晓 */
    @SerialName("revealedAt") val revealedAt: Long? = null,
    /** 开局**计划**角色构成，不等于真实身份（真实身份在 `roles`） */
    @SerialName("roleSlots") val roleSlots: List<ExportRoleSlot> = emptyList()
)

/** 计划角色构成的一项。 */
@Serializable
data class ExportRoleSlot(
    @SerialName("roleId") val roleId: String,
    @SerialName("count") val count: Int
)

/** 座位参与人。 */
@Serializable
data class ExportParticipant(
    @SerialName("seat") val seat: Int,
    @SerialName("displayName") val displayName: String? = null,
    @SerialName("colorKey") val colorKey: String? = null
)

/**
 * 真实身份（脱敏后可能为空列表）。
 *
 * `revealState != REVEALED` 的条目只在 `includeSecrets = true` 时出现。
 */
@Serializable
data class ExportRole(
    @SerialName("seat") val seat: Int,
    @SerialName("roleId") val roleId: String,
    @SerialName("source") val source: String,
    @SerialName("revealState") val revealState: String
)

/**
 * 一条事件。
 *
 * 公共字段留在外层，专有字段进 [payload]（键名与领域模型字段一一对应，
 * 未知键忽略，但**已知键类型错误视为损坏**，由导入拒绝）。
 */
@Serializable
data class ExportEvent(
    @SerialName("eventId") val eventId: String,
    @SerialName("occurredOrder") val occurredOrder: Int,
    @SerialName("recordedAt") val recordedAt: Long,
    @SerialName("visibility") val visibility: String,
    @SerialName("supersedesId") val supersedesId: String? = null,
    @SerialName("retracted") val retracted: Boolean = false,
    @SerialName("type") val type: String,
    @SerialName("payload") val payload: JsonObject = JsonObject(emptyMap())
)

/**
 * 导出脱敏选项。**默认两项都为 false**（PRD FR-09：默认隐去未揭晓秘密与私人笔记）。
 */
@Serializable
data class ExportOptions(
    @SerialName("includePrivateNotes") val includePrivateNotes: Boolean = false,
    @SerialName("includeSecrets") val includeSecrets: Boolean = false
) {
    companion object {
        /** 默认（脱敏）选项。 */
        val DEFAULT: ExportOptions = ExportOptions()
    }
}
