@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.roundtrace.app.core.export

import com.roundtrace.app.core.data.MatchBundle
import com.roundtrace.app.core.data.ImportBundle
import com.roundtrace.app.core.model.Alignment
import com.roundtrace.app.core.model.AssassinationRecorded
import com.roundtrace.app.core.model.BeliefType
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventType
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.GameConfig
import com.roundtrace.app.core.model.GameType
import com.roundtrace.app.core.model.MatchAbandoned
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MatchFinalized
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.Participant
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.model.PrivateNoteAdded
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.QuestStarted
import com.roundtrace.app.core.model.RevealState
import com.roundtrace.app.core.model.RoleAssignment
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.RoleSlotSpec
import com.roundtrace.app.core.model.RoleSource
import com.roundtrace.app.core.model.SeatId
import com.roundtrace.app.core.model.TeamProposalResolved
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteChoice
import com.roundtrace.app.core.model.TeamVoteRecorded
import com.roundtrace.app.core.model.WinReason
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * 导出 / 导入的唯一编解码出口（TASKS.md T59②③④）。
 *
 * 硬约束：
 * 1. `import` **绝不抛异常**：所有失败都返回 [ImportResult.Failure]；
 * 2. 失败原因是**枚举 code**，不是中文句子（D6：领域层不拼 UI 文案，由 UI 映射资源）；
 * 3. 未知 `schemaVersion` / 缺失必填字段 / 类型错误三类必须可区分（T64③）；
 * 4. `null` 与 `0` 严格区分：`JsonNull` 解码为 `null`，`0` 解码为 `0`；
 * 5. 纯 Kotlin（无 Android / Room 依赖），JVM 单测可跑。
 */
class MatchJsonCodec(
    private val json: Json = DEFAULT_JSON
) {

    // ————————————————————————————————————————————————————————
    // 导出
    // ————————————————————————————————————————————————————————

    /** 把一场对局编码为稳定格式的 JSON 文本（默认脱敏）。 */
    fun encode(bundle: MatchBundle, options: ExportOptions = ExportOptions.DEFAULT): String {
        val dto = buildBundle(bundle, options)
        return json.encodeToString(ExportBundle.serializer(), dto)
    }

    /** 导出 DTO（测试与摘要预览用，不经过字符串往返）。 */
    internal fun buildBundle(bundle: MatchBundle, options: ExportOptions): ExportBundle {
        val filtered = VisibilityFilter.apply(bundle, options)
        val summary = filtered.summary
        val config = filtered.config

        return ExportBundle(
            gameType = config.gameType.name,
            ruleset = ExportRuleset(id = config.rulesetId, version = config.rulesetVersion),
            match = ExportMatch(
                id = summary.matchId.raw,
                title = summary.title,
                playerCount = summary.playerCount,
                mySeat = config.mySeat,
                initialLeaderSeat = config.initialLeaderSeat,
                presetId = config.presetId,
                status = summary.status.name,
                createdAt = config.createdAtEpochMs,
                updatedAt = summary.updatedAtEpochMs,
                revealedAt = filtered.revealedAt,
                roleSlots = config.roleSlots.map { ExportRoleSlot(it.roleId.name, it.count) }
            ),
            participants = filtered.participants.map {
                ExportParticipant(seat = it.seat, displayName = it.displayName, colorKey = it.colorKey)
            },
            roles = filtered.roleAssignments.entries
                .sortedBy { it.key }
                .map { (seat, role) ->
                    ExportRole(
                        seat = seat,
                        roleId = role.roleId.name,
                        source = role.source.name,
                        revealState = role.revealState.name
                    )
                },
            events = filtered.events.map { toDto(it) },
            exportOptions = options
        )
    }

    private fun toDto(event: MatchEvent): ExportEvent = ExportEvent(
        eventId = event.eventId.raw,
        occurredOrder = event.occurredOrder,
        recordedAt = event.recordedAt,
        visibility = event.visibility.name,
        supersedesId = event.supersedesId?.raw,
        retracted = event.retracted,
        type = event.type.name,
        payload = payloadOf(event)
    )

    /** 只编码事件专有字段（键名 = 领域字段名，便于人工核对）。 */
    private fun payloadOf(event: MatchEvent): JsonObject = when (event) {
        is QuestStarted -> buildJsonObject {
            putInt("questNo", event.questNo)
            putInt("leaderSeat", event.leaderSeat)
        }

        is TeamProposed -> buildJsonObject {
            putInt("questNo", event.questNo)
            putInt("proposalNo", event.proposalNo)
            putInt("leaderSeat", event.leaderSeat)
            put("teamSeats", seatArray(event.teamSeats))
        }

        is TeamVoteRecorded -> buildJsonObject {
            putInt("questNo", event.questNo)
            putInt("proposalNo", event.proposalNo)
            val votes = event.votesBySeat
            if (votes == null) {
                put("votesBySeat", JsonNull)
            } else {
                put(
                    "votesBySeat",
                    buildJsonObject {
                        votes.entries.sortedBy { it.key }.forEach { (seat, choice) ->
                            put(seat.toString(), JsonPrimitive(choice.name))
                        }
                    }
                )
            }
            putIntOrNull("approveCount", event.approveCount)
            putIntOrNull("rejectCount", event.rejectCount)
            putIntOrNull("unknownVoteCount", event.unknownVoteCount)
            putInt("playerCountAtVote", event.playerCountAtVote)
        }

        is TeamProposalResolved -> buildJsonObject {
            putInt("questNo", event.questNo)
            putInt("proposalNo", event.proposalNo)
            put("decision", JsonPrimitive(event.decision.name))
            putIntOrNull("approveCount", event.approveCount)
            putIntOrNull("rejectCount", event.rejectCount)
            putInt("playerCountAtVote", event.playerCountAtVote)
            put("verified", JsonPrimitive(event.verified))
        }

        is MissionResolved -> buildJsonObject {
            putInt("questNo", event.questNo)
            val team = event.teamSeats
            if (team == null) put("teamSeats", JsonNull) else put("teamSeats", seatArray(team))
            putInt("teamSizeExpected", event.teamSizeExpected)
            putIntOrNull("failCount", event.failCount)
            val outcome = event.observedOutcome
            if (outcome == null) {
                put("observedOutcome", JsonNull)
            } else {
                put("observedOutcome", JsonPrimitive(outcome.name))
            }
            put("verified", JsonPrimitive(event.verified))
        }

        is PlayerClaimed -> buildJsonObject {
            putIntOrNull("questNo", event.questNo)
            putInt("speakerSeat", event.speakerSeat)
            put("claimType", JsonPrimitive(event.claimType.name))
            val role = event.claimedRole
            if (role == null) put("claimedRole", JsonNull) else put("claimedRole", JsonPrimitive(role.name))
            put("targetSeats", seatArray(event.targetSeats))
            val text = event.text
            if (text == null) put("text", JsonNull) else put("text", JsonPrimitive(text))
            putIntOrNull("proposalNo", event.proposalNo)
        }

        is PrivateNoteAdded -> buildJsonObject {
            putIntOrNull("questNo", event.questNo)
            putInt("observerSeat", event.observerSeat)
            put("beliefType", JsonPrimitive(event.beliefType.name))
            put("targetSeats", seatArray(event.targetSeats))
            val text = event.text
            if (text == null) put("text", JsonNull) else put("text", JsonPrimitive(text))
            putInt("availableFromOrder", event.availableFromOrder)
        }

        is AssassinationRecorded -> buildJsonObject {
            putIntOrNull("questNo", event.questNo)
            putInt("assassinSeat", event.assassinSeat)
            putInt("targetSeat", event.targetSeat)
            val hit = event.hitMerlin
            if (hit == null) put("hitMerlin", JsonNull) else put("hitMerlin", JsonPrimitive(hit))
        }

        is MatchAbandoned -> buildJsonObject {
            val reason = event.reason
            if (reason == null) put("reason", JsonNull) else put("reason", JsonPrimitive(reason))
        }

        is MatchFinalized -> buildJsonObject {
            val winner = event.winner
            if (winner == null) put("winner", JsonNull) else put("winner", JsonPrimitive(winner.name))
            put("winReason", JsonPrimitive(event.winReason.name))
            val note = event.note
            if (note == null) put("note", JsonNull) else put("note", JsonPrimitive(note))
        }
    }

    // ————————————————————————————————————————————————————————
    // 导入
    // ————————————————————————————————————————————————————————

    /**
     * 解析 JSON 文本。
     *
     * 成功时返回 [ImportResult.Success]，其中 `bundle` 已可直接交给
     * `MatchRepository.importBundle`（`matchId` 由仓储重新生成，绝不覆盖原对局）。
     */
    fun decode(text: String): ImportResult {
        val root: JsonElement = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return ImportResult.Failure(ImportFailureReason.INVALID_JSON, e.message)
        }
        if (root !is JsonObject) {
            return ImportResult.Failure(ImportFailureReason.INVALID_JSON, "根节点不是 JSON 对象")
        }

        val versionElement = root["schemaVersion"]
        if (versionElement == null || versionElement is JsonNull) {
            return ImportResult.Failure(ImportFailureReason.MISSING_FIELD, "缺少 schemaVersion")
        }
        val version = (versionElement as? JsonPrimitive)?.intOrNull
            ?: return ImportResult.Failure(
                ImportFailureReason.TYPE_MISMATCH,
                "schemaVersion 不是整数：$versionElement"
            )
        if (version != ExportBundle.SCHEMA_VERSION) {
            return ImportResult.Failure(
                ImportFailureReason.UNSUPPORTED_SCHEMA_VERSION,
                "不支持的 schemaVersion=$version（本版本仅支持 ${ExportBundle.SCHEMA_VERSION}）"
            )
        }

        val appElement = root["app"]
        val appName = (appElement as? JsonPrimitive)?.contentOrNull
        if (appName != null && appName != ExportBundle.APP_NAME) {
            return ImportResult.Failure(
                ImportFailureReason.INVALID_JSON,
                "不是 RoundTrace 导出文件（app=$appName）"
            )
        }

        val dto: ExportBundle = try {
            json.decodeFromJsonElement(ExportBundle.serializer(), root)
        } catch (e: MissingFieldException) {
            return ImportResult.Failure(
                ImportFailureReason.MISSING_FIELD,
                e.missingFields.joinToString(",")
            )
        } catch (e: SerializationException) {
            return ImportResult.Failure(ImportFailureReason.TYPE_MISMATCH, e.message)
        } catch (e: IllegalArgumentException) {
            return ImportResult.Failure(ImportFailureReason.TYPE_MISMATCH, e.message)
        }

        return try {
            ImportResult.Success(bundle = toImportBundle(dto), preview = previewOf(dto))
        } catch (e: ImportParseException) {
            ImportResult.Failure(e.reason, e.detail)
        }
    }

    /** 只做摘要预览（两段式导入的第一步），不产出可写库的 bundle。 */
    fun previewOnly(text: String): ImportResult = decode(text)

    private fun previewOf(dto: ExportBundle): ImportPreview = ImportPreview(
        title = dto.match.title,
        playerCount = dto.match.playerCount,
        eventCount = dto.events.count { !it.retracted },
        questCount = dto.events.filter { !it.retracted && it.type == EventType.QUEST_STARTED.name }.size,
        status = dto.match.status,
        createdAt = dto.match.createdAt,
        rulesetId = dto.ruleset.id
    )

    private fun toImportBundle(dto: ExportBundle): ImportBundle {
        val gameType = GameType.entries.firstOrNull { it.name == dto.gameType }
            ?: throw ImportParseException(ImportFailureReason.TYPE_MISMATCH, "未知 gameType=${dto.gameType}")

        if (dto.match.playerCount !in 1..10) {
            throw ImportParseException(
                ImportFailureReason.RULE_VALIDATION_FAILED,
                "playerCount 越界：${dto.match.playerCount}"
            )
        }

        val config = GameConfig(
            gameType = gameType,
            rulesetId = dto.ruleset.id,
            rulesetVersion = dto.ruleset.version,
            presetId = dto.match.presetId,
            playerCount = dto.match.playerCount,
            roleSlots = dto.match.roleSlots.map {
                RoleSlotSpec(RoleId.fromPersisted(it.roleId), it.count)
            },
            mySeat = dto.match.mySeat,
            initialLeaderSeat = dto.match.initialLeaderSeat,
            title = dto.match.title,
            createdAtEpochMs = dto.match.createdAt
        )

        val participants = dto.participants
            .sortedBy { it.seat }
            .map { Participant(seat = it.seat, displayName = it.displayName, colorKey = it.colorKey) }

        val duplicateSeats = participants.groupBy { it.seat }.filterValues { it.size > 1 }.keys
        if (duplicateSeats.isNotEmpty()) {
            throw ImportParseException(
                ImportFailureReason.RULE_VALIDATION_FAILED,
                "座位重复：${duplicateSeats.sorted()}"
            )
        }

        val roles = dto.roles
            .sortedBy { it.seat }
            .map {
                RoleAssignment(
                    seat = it.seat,
                    roleId = RoleId.fromPersisted(it.roleId),
                    source = enumOr(RoleSource.entries, it.source, RoleSource.IMPORTED),
                    revealState = enumOr(RevealState.entries, it.revealState, RevealState.HIDDEN)
                )
            }

        val events = dto.events
            .filterNot { it.retracted }
            .sortedBy { it.occurredOrder }
            .map { decodeEvent(it) }

        return ImportBundle(
            config = config,
            participants = participants,
            roleAssignments = roles,
            events = events,
            revealedAt = dto.match.revealedAt
        )
    }

    private fun decodeEvent(dto: ExportEvent): MatchEvent {
        val type = EventType.entries.firstOrNull { it.name == dto.type }
            ?: throw ImportParseException(
                ImportFailureReason.TYPE_MISMATCH,
                "未知事件类型 ${dto.type}（eventId=${dto.eventId}）"
            )
        val payload = dto.payload
        val eventId = EventId(dto.eventId)
        val visibility = enumOr(EventVisibility.entries, dto.visibility, EventVisibility.PUBLIC)
        val supersedesId = dto.supersedesId?.let { EventId(it) }

        return when (type) {
            EventType.QUEST_STARTED -> QuestStarted(
                eventId = eventId,
                occurredOrder = dto.occurredOrder,
                recordedAt = dto.recordedAt,
                visibility = visibility,
                supersedesId = supersedesId,
                retracted = false,
                questNo = payload.requireInt("questNo", type, dto.eventId),
                leaderSeat = payload.requireInt("leaderSeat", type, dto.eventId)
            )

            EventType.TEAM_PROPOSED -> TeamProposed(
                eventId = eventId,
                occurredOrder = dto.occurredOrder,
                recordedAt = dto.recordedAt,
                visibility = visibility,
                supersedesId = supersedesId,
                retracted = false,
                questNo = payload.requireInt("questNo", type, dto.eventId),
                proposalNo = payload.requireInt("proposalNo", type, dto.eventId),
                leaderSeat = payload.requireInt("leaderSeat", type, dto.eventId),
                teamSeats = payload.seatList("teamSeats")
            )

            EventType.TEAM_VOTE_RECORDED -> TeamVoteRecorded(
                eventId = eventId,
                occurredOrder = dto.occurredOrder,
                recordedAt = dto.recordedAt,
                visibility = visibility,
                supersedesId = supersedesId,
                retracted = false,
                questNo = payload.requireInt("questNo", type, dto.eventId),
                proposalNo = payload.requireInt("proposalNo", type, dto.eventId),
                votesBySeat = payload.votesBySeat(),
                approveCount = payload.intOrNull("approveCount"),
                rejectCount = payload.intOrNull("rejectCount"),
                unknownVoteCount = payload.intOrNull("unknownVoteCount"),
                playerCountAtVote = payload.requireInt("playerCountAtVote", type, dto.eventId)
            )

            EventType.TEAM_PROPOSAL_RESOLVED -> TeamProposalResolved(
                eventId = eventId,
                occurredOrder = dto.occurredOrder,
                recordedAt = dto.recordedAt,
                visibility = visibility,
                supersedesId = supersedesId,
                retracted = false,
                questNo = payload.requireInt("questNo", type, dto.eventId),
                proposalNo = payload.requireInt("proposalNo", type, dto.eventId),
                decision = payload.requireEnum(
                    "decision",
                    ProposalDecision.entries,
                    type,
                    dto.eventId
                ),
                approveCount = payload.intOrNull("approveCount"),
                rejectCount = payload.intOrNull("rejectCount"),
                playerCountAtVote = payload.requireInt("playerCountAtVote", type, dto.eventId),
                verified = payload.booleanOrNull("verified") ?: false
            )

            EventType.MISSION_RESOLVED -> MissionResolved(
                eventId = eventId,
                occurredOrder = dto.occurredOrder,
                recordedAt = dto.recordedAt,
                visibility = visibility,
                supersedesId = supersedesId,
                retracted = false,
                questNo = payload.requireInt("questNo", type, dto.eventId),
                teamSeats = if (payload.isJsonNull("teamSeats")) null else payload.seatList("teamSeats"),
                teamSizeExpected = payload.requireInt("teamSizeExpected", type, dto.eventId),
                failCount = payload.intOrNull("failCount"),
                observedOutcome = payload.stringOrNull("observedOutcome")
                    ?.let { raw -> MissionOutcome.entries.firstOrNull { it.name == raw } },
                verified = payload.booleanOrNull("verified") ?: false
            )

            EventType.PLAYER_CLAIMED -> PlayerClaimed(
                eventId = eventId,
                occurredOrder = dto.occurredOrder,
                recordedAt = dto.recordedAt,
                visibility = visibility,
                supersedesId = supersedesId,
                retracted = false,
                questNo = payload.intOrNull("questNo"),
                speakerSeat = payload.requireInt("speakerSeat", type, dto.eventId),
                claimType = payload.requireEnum("claimType", ClaimType.entries, type, dto.eventId),
                claimedRole = payload.stringOrNull("claimedRole")?.let { RoleId.fromPersisted(it) },
                targetSeats = payload.seatList("targetSeats"),
                text = payload.stringOrNull("text"),
                proposalNo = payload.intOrNull("proposalNo")
            )

            EventType.PRIVATE_NOTE_ADDED -> PrivateNoteAdded(
                eventId = eventId,
                occurredOrder = dto.occurredOrder,
                recordedAt = dto.recordedAt,
                visibility = visibility,
                supersedesId = supersedesId,
                retracted = false,
                questNo = payload.intOrNull("questNo"),
                observerSeat = payload.requireInt("observerSeat", type, dto.eventId),
                beliefType = payload.requireEnum("beliefType", BeliefType.entries, type, dto.eventId),
                targetSeats = payload.seatList("targetSeats"),
                text = payload.stringOrNull("text"),
                availableFromOrder = payload.requireInt("availableFromOrder", type, dto.eventId)
            )

            EventType.ASSASSINATION_RECORDED -> AssassinationRecorded(
                eventId = eventId,
                occurredOrder = dto.occurredOrder,
                recordedAt = dto.recordedAt,
                visibility = visibility,
                supersedesId = supersedesId,
                retracted = false,
                questNo = payload.intOrNull("questNo"),
                assassinSeat = payload.requireInt("assassinSeat", type, dto.eventId),
                targetSeat = payload.requireInt("targetSeat", type, dto.eventId),
                hitMerlin = payload.booleanOrNull("hitMerlin")
            )

            EventType.MATCH_ABANDONED -> MatchAbandoned(
                eventId = eventId,
                occurredOrder = dto.occurredOrder,
                recordedAt = dto.recordedAt,
                visibility = visibility,
                supersedesId = supersedesId,
                retracted = false,
                questNo = payload.intOrNull("questNo"),
                reason = payload.stringOrNull("reason")
            )

            EventType.MATCH_FINALIZED -> MatchFinalized(
                eventId = eventId,
                occurredOrder = dto.occurredOrder,
                recordedAt = dto.recordedAt,
                visibility = visibility,
                supersedesId = supersedesId,
                retracted = false,
                questNo = payload.intOrNull("questNo"),
                winner = payload.stringOrNull("winner")
                    ?.let { raw -> Alignment.entries.firstOrNull { it.name == raw } },
                winReason = payload.requireEnum("winReason", WinReason.entries, type, dto.eventId),
                note = payload.stringOrNull("note")
            )
        }
    }

    // ————————————————————————————————————————————————————————
    // 内部工具
    // ————————————————————————————————————————————————————————

    private fun <T : Enum<T>> enumOr(values: List<T>, raw: String, fallback: T): T =
        values.firstOrNull { it.name == raw } ?: fallback

    private fun JsonObjectBuilder.putInt(key: String, value: Int) {
        put(key, JsonPrimitive(value))
    }

    private fun JsonObjectBuilder.putIntOrNull(key: String, value: Int?) {
        if (value == null) put(key, JsonNull) else put(key, JsonPrimitive(value))
    }

    private fun seatArray(values: List<SeatId>): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    private fun JsonObject.isJsonNull(key: String): Boolean {
        val element = this[key] ?: return true
        return element is JsonNull
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

    private fun JsonObject.booleanOrNull(key: String): Boolean? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.booleanOrNull
    }

    private fun JsonObject.requireInt(key: String, type: EventType, eventId: String): Int =
        intOrNull(key) ?: throw ImportParseException(
            ImportFailureReason.MISSING_FIELD,
            "$type 缺少必填字段 $key（eventId=$eventId）"
        )

    private fun <T : Enum<T>> JsonObject.requireEnum(
        key: String,
        values: List<T>,
        type: EventType,
        eventId: String
    ): T {
        val raw = stringOrNull(key) ?: throw ImportParseException(
            ImportFailureReason.MISSING_FIELD,
            "$type 缺少必填字段 $key（eventId=$eventId）"
        )
        return values.firstOrNull { it.name == raw } ?: throw ImportParseException(
            ImportFailureReason.TYPE_MISMATCH,
            "$type 的 $key 非法：$raw（eventId=$eventId）"
        )
    }

    private fun JsonObject.seatList(key: String): List<SeatId> {
        val element = this[key] ?: return emptyList()
        if (element !is JsonArray) return emptyList()
        return element.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
    }

    private fun JsonObject.votesBySeat(): Map<SeatId, TeamVoteChoice>? {
        val element = this["votesBySeat"] ?: return null
        if (element is JsonNull || element !is JsonObject) return null
        return element.entries.mapNotNull { (seatKey, choiceElement) ->
            val seat = seatKey.toIntOrNull() ?: return@mapNotNull null
            val raw = (choiceElement as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val choice = TeamVoteChoice.entries.firstOrNull { it.name == raw } ?: return@mapNotNull null
            seat to choice
        }.toMap()
    }

    /** 内部解析异常：只在 `decode` 边界捕获，绝不外泄到 UI。 */
    private class ImportParseException(
        val reason: ImportFailureReason,
        val detail: String?
    ) : Exception(detail)

    companion object {

        /** 导出格式：带缩进、字段序固定、null 显式写出（便于人工核对与 diff）。 */
        val DEFAULT_JSON: Json = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = true
        }
    }
}

/**
 * 导入失败原因（**枚举 code**，UI 映射为文案，D6）。
 *
 * 对应 UI 需准备的文案键见 `docs/handoff/TODO-dispatch.md` C1 与
 * `DESIGN_SYNC.md §5.5`（`import_error_*`）。
 */
enum class ImportFailureReason {
    /** 根本不是合法 JSON */
    INVALID_JSON,

    /** 缺少必填字段（含根节点 `schemaVersion`） */
    MISSING_FIELD,

    /** 字段类型错误 / 枚举值非法 */
    TYPE_MISMATCH,

    /** schemaVersion 高于或低于本版本支持范围 */
    UNSUPPORTED_SCHEMA_VERSION,

    /** 结构合法但规则校验不通过（人数越界、座位重复等） */
    RULE_VALIDATION_FAILED,

    /** 校验通过但落库失败（磁盘/事务异常）；不产生半场对局 */
    STORAGE_FAILED
}

/** 导入结果。 */
sealed interface ImportResult {

    data class Success(
        val bundle: ImportBundle,
        val preview: ImportPreview
    ) : ImportResult

    /**
     * @param detail 技术细节（可展开查看），**不要**直接当主文案显示
     */
    data class Failure(
        val reason: ImportFailureReason,
        val detail: String? = null
    ) : ImportResult
}

/** 两段式导入第一步要展示的摘要（「1 局 · 8 人 · 12 条事件」）。 */
data class ImportPreview(
    val title: String,
    val playerCount: Int,
    val eventCount: Int,
    val questCount: Int,
    val status: String,
    val createdAt: Long,
    val rulesetId: String
)
