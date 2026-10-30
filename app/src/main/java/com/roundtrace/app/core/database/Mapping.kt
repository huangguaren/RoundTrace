package com.roundtrace.app.core.database

import com.roundtrace.app.core.database.dao.MatchSummaryRow
import com.roundtrace.app.core.database.entity.MatchEntity
import com.roundtrace.app.core.database.entity.ParticipantEntity
import com.roundtrace.app.core.database.entity.RoleAssignmentEntity
import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.GameConfig
import com.roundtrace.app.core.model.GameType
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MatchStatus
import com.roundtrace.app.core.model.MatchSummary
import com.roundtrace.app.core.model.Participant
import com.roundtrace.app.core.model.RevealState
import com.roundtrace.app.core.model.RoleAssignment
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.RoleSlotSpec
import com.roundtrace.app.core.model.RoleSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Entity ↔ Domain 双向映射（ARCHITECTURE §8.9 / TASKS.md T33③）。
 *
 * 本文件是 Room 与领域层之间**唯一的**转换出口：
 * feature 层与 core/rules 层永远不该看到 Entity。
 *
 * `GameConfig` 以 JSON 存放在 `matches.configJson`（含 roleSlots / title / initialLeaderSeat），
 * 编解码实现在此文件内部，避免再引入一套 Codec 类。
 */
object Mapping {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = true
    }

    // ————————————————————————————————————————————————————————
    // matches
    // ————————————————————————————————————————————————————————

    /** 由配置构造新对局实体（创建时 `status` 恒为 DRAFT）。 */
    fun toMatchEntity(
        matchId: MatchId,
        config: GameConfig,
        now: Long,
        status: MatchStatus = MatchStatus.DRAFT,
        revealedAt: Long? = null
    ): MatchEntity = MatchEntity(
        id = matchId.raw,
        gameType = config.gameType.name,
        rulesetId = config.rulesetId,
        rulesetVersion = config.rulesetVersion,
        presetId = config.presetId,
        playerCount = config.playerCount,
        configJson = encodeConfig(config),
        status = status.name,
        title = config.title,
        mySeat = config.mySeat,
        initialLeaderSeat = config.initialLeaderSeat,
        createdAt = config.createdAtEpochMs,
        updatedAt = now,
        revealedAt = revealedAt,
        schemaVersion = 1
    )

    /** 由实体还原配置（`configJson` 是权威，列只作冗余检索）。 */
    fun toConfig(entity: MatchEntity): GameConfig = decodeConfig(entity.configJson)

    /** 首页投影 → 领域摘要。 */
    fun toSummary(row: MatchSummaryRow): MatchSummary = MatchSummary(
        matchId = MatchId(row.matchId),
        title = row.title,
        playerCount = row.playerCount,
        status = matchStatusOf(row.status),
        rulesetId = row.rulesetId,
        updatedAtEpochMs = row.updatedAt,
        questsStarted = row.questsStarted,
        mySeat = row.mySeat
    )

    /** 实体 → 领域摘要（没有 `questsStarted` 时用 0；首页列表请走投影）。 */
    fun toSummary(entity: MatchEntity, questsStarted: Int = 0): MatchSummary = MatchSummary(
        matchId = MatchId(entity.id),
        title = entity.title,
        playerCount = entity.playerCount,
        status = matchStatusOf(entity.status),
        rulesetId = entity.rulesetId,
        updatedAtEpochMs = entity.updatedAt,
        questsStarted = questsStarted,
        mySeat = entity.mySeat
    )

    // ————————————————————————————————————————————————————————
    // participants
    // ————————————————————————————————————————————————————————

    fun toParticipantEntity(
        participantId: String,
        matchId: MatchId,
        participant: Participant
    ): ParticipantEntity = ParticipantEntity(
        id = participantId,
        matchId = matchId.raw,
        seatNumber = participant.seat,
        displayName = participant.displayName,
        colorKey = participant.colorKey,
        layoutX = participant.layoutX,
        layoutY = participant.layoutY,
        avatarUri = participant.avatarUri
    )

    fun toParticipant(entity: ParticipantEntity): Participant = Participant(
        seat = entity.seatNumber,
        displayName = entity.displayName,
        colorKey = entity.colorKey,
        layoutX = entity.layoutX,
        layoutY = entity.layoutY,
        avatarUri = entity.avatarUri
    )

    // ————————————————————————————————————————————————————————
    // role_assignments
    // ————————————————————————————————————————————————————————

    fun toRoleEntity(
        assignmentId: String,
        matchId: MatchId,
        assignment: RoleAssignment
    ): RoleAssignmentEntity = RoleAssignmentEntity(
        id = assignmentId,
        matchId = matchId.raw,
        seatNumber = assignment.seat,
        roleId = assignment.roleId.name,
        source = assignment.source.name,
        revealState = assignment.revealState.name,
        assignedFrom = assignment.assignedFrom
    )

    fun toRoleAssignment(entity: RoleAssignmentEntity): RoleAssignment = RoleAssignment(
        seat = entity.seatNumber,
        roleId = RoleId.fromPersisted(entity.roleId),
        source = roleSourceOf(entity.source),
        revealState = revealStateOf(entity.revealState),
        assignedFrom = entity.assignedFrom
    )

    // ————————————————————————————————————————————————————————
    // 枚举解析（脏数据一律降级，绝不抛异常）
    // ————————————————————————————————————————————————————————

    fun matchStatusOf(raw: String): MatchStatus =
        MatchStatus.entries.firstOrNull { it.name == raw } ?: MatchStatus.DRAFT

    fun roleSourceOf(raw: String): RoleSource =
        RoleSource.entries.firstOrNull { it.name == raw } ?: RoleSource.PRESET_MANUAL

    fun revealStateOf(raw: String): RevealState =
        RevealState.entries.firstOrNull { it.name == raw } ?: RevealState.HIDDEN

    fun gameTypeOf(raw: String): GameType =
        GameType.entries.firstOrNull { it.name == raw } ?: GameType.AVALON

    fun alignmentOf(raw: String?): Alignment {
        if (raw == null) return Alignment.UNKNOWN
        return Alignment.entries.firstOrNull { it.name == raw } ?: Alignment.UNKNOWN
    }

    // ————————————————————————————————————————————————————————
    // GameConfig JSON
    // ————————————————————————————————————————————————————————

    /** 序列化配置；字段顺序固定，便于人工对比与 diff。 */
    fun encodeConfig(config: GameConfig): String {
        val obj = buildJsonObject {
            put("gameType", JsonPrimitive(config.gameType.name))
            put("rulesetId", JsonPrimitive(config.rulesetId))
            put("rulesetVersion", JsonPrimitive(config.rulesetVersion))
            put("presetId", JsonPrimitive(config.presetId))
            put("playerCount", JsonPrimitive(config.playerCount))
            val mySeat = config.mySeat
            if (mySeat == null) put("mySeat", JsonNull) else put("mySeat", JsonPrimitive(mySeat))
            put("initialLeaderSeat", JsonPrimitive(config.initialLeaderSeat))
            put("title", JsonPrimitive(config.title))
            put("createdAtEpochMs", JsonPrimitive(config.createdAtEpochMs))
            put(
                "roleSlots",
                buildJsonArray {
                    config.roleSlots.forEach { slot ->
                        add(
                            buildJsonObject {
                                put("roleId", JsonPrimitive(slot.roleId.name))
                                put("count", JsonPrimitive(slot.count))
                            }
                        )
                    }
                }
            )
        }
        return obj.toString()
    }

    /** 反序列化配置；缺字段时用安全默认值，避免历史数据把 App 打崩。 */
    fun decodeConfig(raw: String): GameConfig {
        val obj = json.parseToJsonElement(raw).jsonObject

        val roleSlots: List<RoleSlotSpec> = (obj["roleSlots"] as? JsonArray)
            ?.mapNotNull { element ->
                val slot = element as? JsonObject ?: return@mapNotNull null
                val roleRaw = slot.stringOrNull("roleId") ?: return@mapNotNull null
                val count = slot.intOrNull("count") ?: return@mapNotNull null
                RoleSlotSpec(roleId = RoleId.fromPersisted(roleRaw), count = count)
            }
            ?: emptyList()

        return GameConfig(
            gameType = gameTypeOf(obj.stringOrNull("gameType") ?: GameType.AVALON.name),
            rulesetId = obj.stringOrNull("rulesetId")
                ?: com.roundtrace.app.core.model.MatchCreationDraft.DEFAULT_RULESET_ID,
            rulesetVersion = obj.intOrNull("rulesetVersion")
                ?: com.roundtrace.app.core.model.MatchCreationDraft.DEFAULT_RULESET_VERSION,
            presetId = obj.stringOrNull("presetId")
                ?: com.roundtrace.app.core.model.MatchCreationDraft.PRESET_CUSTOM,
            playerCount = obj.intOrNull("playerCount") ?: 0,
            roleSlots = roleSlots,
            mySeat = obj.intOrNull("mySeat"),
            initialLeaderSeat = obj.intOrNull("initialLeaderSeat") ?: 1,
            title = obj.stringOrNull("title") ?: "",
            createdAtEpochMs = obj.longOrNull("createdAtEpochMs") ?: 0L
        )
    }

    // —— JSON 小工具 ——

    private fun JsonObject.longOrNull(key: String): Long? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.longOrNull
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.intOrNull
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.contentOrNull
    }
}
