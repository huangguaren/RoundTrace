package com.roundtrace.app.core.database

import com.roundtrace.app.core.database.entity.MatchEventEntity
import com.roundtrace.app.core.model.AssassinationRecorded
import com.roundtrace.app.core.model.BeliefType
import com.roundtrace.app.core.model.ClaimType
import com.roundtrace.app.core.model.EventId
import com.roundtrace.app.core.model.EventType
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.MatchAbandoned
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.MatchFinalized
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.model.MissionResolved
import com.roundtrace.app.core.model.PlayerClaimed
import com.roundtrace.app.core.model.PrivateNoteAdded
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.model.QuestStarted
import com.roundtrace.app.core.model.RoleId
import com.roundtrace.app.core.model.SeatId
import com.roundtrace.app.core.model.TeamProposalResolved
import com.roundtrace.app.core.model.TeamProposed
import com.roundtrace.app.core.model.TeamVoteChoice
import com.roundtrace.app.core.model.TeamVoteRecorded
import com.roundtrace.app.core.model.WinReason
import com.roundtrace.app.core.model.Alignment
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * `MatchEvent` ↔ `payloadJson`（ARCHITECTURE §4.5 / TASKS.md T33）。
 *
 * **唯一编解码出口**：禁止在别处拼 payload JSON。
 *
 * 约定：
 * 1. 公共 7 字段（`eventId`/`occurredOrder`/`recordedAt`/`visibility`/`supersedesId`/`retracted`）
 *    走**列**，payload 里**只放事件专有字段**，避免双写漂移；
 * 2. `questNo` 没有独立列（`match_events` 表无该列），因此也放在 payload 里；
 * 3. `payloadVersion = 1`，为未来结构升级留出口；
 * 4. 解码遇到**未知 `eventType`** 必须抛带类型信息的异常（不静默返回 null）；
 * 5. `decode(encode(e)) == e` 对 10 种事件类型成立（由单测钉住）。
 */
class EventCodec(
    private val json: Json = DEFAULT_JSON
) {

    // ————————————————————————————————————————————————————————
    // 编码
    // ————————————————————————————————————————————————————————

    /**
     * 把领域事件编码为实体。
     *
     * @param matchId 所属对局
     * @param occurredOrder **由 DAO 分配的**顺序（领域事件的该字段恒为 0）
     */
    fun encode(event: MatchEvent, matchId: MatchId, occurredOrder: Int): MatchEventEntity =
        MatchEventEntity(
            id = event.eventId.raw,
            matchId = matchId.raw,
            occurredOrder = occurredOrder,
            phaseKey = phaseKeyOf(event),
            eventType = event.type.name,
            payloadVersion = PAYLOAD_VERSION,
            payloadJson = encodePayload(event),
            visibility = event.visibility.name,
            recordedAt = event.recordedAt,
            supersedesId = event.supersedesId?.raw,
            retracted = event.retracted
        )

    /** 只编码事件专有字段。 */
    internal fun encodePayload(event: MatchEvent): String {
        val obj: JsonObject = when (event) {
            is QuestStarted -> buildJsonObject {
                putInt("questNo", event.questNo)
                putInt("leaderSeat", event.leaderSeat)
            }

            is TeamProposed -> buildJsonObject {
                putInt("questNo", event.questNo)
                putInt("proposalNo", event.proposalNo)
                putInt("leaderSeat", event.leaderSeat)
                put("teamSeats", intArray(event.teamSeats))
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
                if (team == null) {
                    put("teamSeats", JsonNull)
                } else {
                    put("teamSeats", intArray(team))
                }
                putInt("teamSizeExpected", event.teamSizeExpected)
                putIntOrNull("failCount", event.failCount)
                val observed = event.observedOutcome
                if (observed == null) {
                    put("observedOutcome", JsonNull)
                } else {
                    put("observedOutcome", JsonPrimitive(observed.name))
                }
                put("verified", JsonPrimitive(event.verified))
            }

            is PlayerClaimed -> buildJsonObject {
                putIntOrNull("questNo", event.questNo)
                putInt("speakerSeat", event.speakerSeat)
                put("claimType", JsonPrimitive(event.claimType.name))
                val role = event.claimedRole
                if (role == null) {
                    put("claimedRole", JsonNull)
                } else {
                    put("claimedRole", JsonPrimitive(role.name))
                }
                put("targetSeats", intArray(event.targetSeats))
                val text = event.text
                if (text == null) {
                    put("text", JsonNull)
                } else {
                    put("text", JsonPrimitive(text))
                }
                putIntOrNull("proposalNo", event.proposalNo)
            }

            is PrivateNoteAdded -> buildJsonObject {
                putIntOrNull("questNo", event.questNo)
                putInt("observerSeat", event.observerSeat)
                put("beliefType", JsonPrimitive(event.beliefType.name))
                put("targetSeats", intArray(event.targetSeats))
                val text = event.text
                if (text == null) {
                    put("text", JsonNull)
                } else {
                    put("text", JsonPrimitive(text))
                }
                putInt("availableFromOrder", event.availableFromOrder)
            }

            is AssassinationRecorded -> buildJsonObject {
                putIntOrNull("questNo", event.questNo)
                putInt("assassinSeat", event.assassinSeat)
                putInt("targetSeat", event.targetSeat)
                val hit = event.hitMerlin
                if (hit == null) {
                    put("hitMerlin", JsonNull)
                } else {
                    put("hitMerlin", JsonPrimitive(hit))
                }
            }

            is MatchAbandoned -> buildJsonObject {
                val reason = event.reason
                if (reason == null) {
                    put("reason", JsonNull)
                } else {
                    put("reason", JsonPrimitive(reason))
                }
            }

            is MatchFinalized -> buildJsonObject {
                val winner = event.winner
                if (winner == null) {
                    put("winner", JsonNull)
                } else {
                    put("winner", JsonPrimitive(winner.name))
                }
                put("winReason", JsonPrimitive(event.winReason.name))
                val note = event.note
                if (note == null) {
                    put("note", JsonNull)
                } else {
                    put("note", JsonPrimitive(note))
                }
            }
        }
        return obj.toString()
    }

    // ————————————————————————————————————————————————————————
    // 解码
    // ————————————————————————————————————————————————————————

    /**
     * 把实体解码为领域事件。
     *
     * @throws IllegalStateException 未知 `eventType`，或缺少该类型必有字段
     */
    fun decode(entity: MatchEventEntity): MatchEvent {
        val eventType = eventTypeOf(entity.eventType, entity.id)
        val payload = parsePayload(entity.payloadJson, entity.id)
        val base = BaseFields(
            eventId = EventId(entity.id),
            occurredOrder = entity.occurredOrder,
            recordedAt = entity.recordedAt,
            visibility = visibilityOf(entity.visibility),
            supersedesId = entity.supersedesId?.let { EventId(it) },
            retracted = entity.retracted
        )

        return when (eventType) {
            EventType.QUEST_STARTED -> QuestStarted(
                eventId = base.eventId,
                occurredOrder = base.occurredOrder,
                recordedAt = base.recordedAt,
                visibility = base.visibility,
                supersedesId = base.supersedesId,
                retracted = base.retracted,
                questNo = payload.requiredInt("questNo", eventType, entity.id),
                leaderSeat = payload.requiredInt("leaderSeat", eventType, entity.id)
            )

            EventType.TEAM_PROPOSED -> TeamProposed(
                eventId = base.eventId,
                occurredOrder = base.occurredOrder,
                recordedAt = base.recordedAt,
                visibility = base.visibility,
                supersedesId = base.supersedesId,
                retracted = base.retracted,
                questNo = payload.requiredInt("questNo", eventType, entity.id),
                proposalNo = payload.requiredInt("proposalNo", eventType, entity.id),
                leaderSeat = payload.requiredInt("leaderSeat", eventType, entity.id),
                teamSeats = payload.intList("teamSeats")
            )

            EventType.TEAM_VOTE_RECORDED -> TeamVoteRecorded(
                eventId = base.eventId,
                occurredOrder = base.occurredOrder,
                recordedAt = base.recordedAt,
                visibility = base.visibility,
                supersedesId = base.supersedesId,
                retracted = base.retracted,
                questNo = payload.requiredInt("questNo", eventType, entity.id),
                proposalNo = payload.requiredInt("proposalNo", eventType, entity.id),
                votesBySeat = payload.votesBySeat(),
                approveCount = payload.intOrNull("approveCount"),
                rejectCount = payload.intOrNull("rejectCount"),
                unknownVoteCount = payload.intOrNull("unknownVoteCount"),
                playerCountAtVote = payload.requiredInt("playerCountAtVote", eventType, entity.id)
            )

            EventType.TEAM_PROPOSAL_RESOLVED -> TeamProposalResolved(
                eventId = base.eventId,
                occurredOrder = base.occurredOrder,
                recordedAt = base.recordedAt,
                visibility = base.visibility,
                supersedesId = base.supersedesId,
                retracted = base.retracted,
                questNo = payload.requiredInt("questNo", eventType, entity.id),
                proposalNo = payload.requiredInt("proposalNo", eventType, entity.id),
                decision = proposalDecisionOf(
                    payload.stringOrNull("decision"),
                    eventType,
                    entity.id
                ),
                approveCount = payload.intOrNull("approveCount"),
                rejectCount = payload.intOrNull("rejectCount"),
                playerCountAtVote = payload.requiredInt("playerCountAtVote", eventType, entity.id),
                verified = payload.booleanOrNull("verified") ?: false
            )

            EventType.MISSION_RESOLVED -> MissionResolved(
                eventId = base.eventId,
                occurredOrder = base.occurredOrder,
                recordedAt = base.recordedAt,
                visibility = base.visibility,
                supersedesId = base.supersedesId,
                retracted = base.retracted,
                questNo = payload.requiredInt("questNo", eventType, entity.id),
                teamSeats = if (payload.isNull("teamSeats")) null else payload.intList("teamSeats"),
                teamSizeExpected = payload.requiredInt("teamSizeExpected", eventType, entity.id),
                failCount = payload.intOrNull("failCount"),
                observedOutcome = missionOutcomeOf(payload.stringOrNull("observedOutcome")),
                verified = payload.booleanOrNull("verified") ?: false
            )

            EventType.PLAYER_CLAIMED -> PlayerClaimed(
                eventId = base.eventId,
                occurredOrder = base.occurredOrder,
                recordedAt = base.recordedAt,
                visibility = base.visibility,
                supersedesId = base.supersedesId,
                retracted = base.retracted,
                questNo = payload.intOrNull("questNo"),
                speakerSeat = payload.requiredInt("speakerSeat", eventType, entity.id),
                claimType = claimTypeOf(
                    payload.stringOrNull("claimType"),
                    eventType,
                    entity.id
                ),
                claimedRole = payload.stringOrNull("claimedRole")?.let { RoleId.fromPersisted(it) },
                targetSeats = payload.intList("targetSeats"),
                text = payload.stringOrNull("text"),
                proposalNo = payload.intOrNull("proposalNo")
            )

            EventType.PRIVATE_NOTE_ADDED -> PrivateNoteAdded(
                eventId = base.eventId,
                occurredOrder = base.occurredOrder,
                recordedAt = base.recordedAt,
                visibility = base.visibility,
                supersedesId = base.supersedesId,
                retracted = base.retracted,
                questNo = payload.intOrNull("questNo"),
                observerSeat = payload.requiredInt("observerSeat", eventType, entity.id),
                beliefType = beliefTypeOf(
                    payload.stringOrNull("beliefType"),
                    eventType,
                    entity.id
                ),
                targetSeats = payload.intList("targetSeats"),
                text = payload.stringOrNull("text"),
                availableFromOrder = payload.requiredInt(
                    "availableFromOrder",
                    eventType,
                    entity.id
                )
            )

            EventType.ASSASSINATION_RECORDED -> AssassinationRecorded(
                eventId = base.eventId,
                occurredOrder = base.occurredOrder,
                recordedAt = base.recordedAt,
                visibility = base.visibility,
                supersedesId = base.supersedesId,
                retracted = base.retracted,
                questNo = payload.intOrNull("questNo"),
                assassinSeat = payload.requiredInt("assassinSeat", eventType, entity.id),
                targetSeat = payload.requiredInt("targetSeat", eventType, entity.id),
                hitMerlin = payload.booleanOrNull("hitMerlin")
            )

            EventType.MATCH_ABANDONED -> MatchAbandoned(
                eventId = base.eventId,
                occurredOrder = base.occurredOrder,
                recordedAt = base.recordedAt,
                visibility = base.visibility,
                supersedesId = base.supersedesId,
                retracted = base.retracted,
                questNo = payload.intOrNull("questNo"),
                reason = payload.stringOrNull("reason")
            )

            EventType.MATCH_FINALIZED -> MatchFinalized(
                eventId = base.eventId,
                occurredOrder = base.occurredOrder,
                recordedAt = base.recordedAt,
                visibility = base.visibility,
                supersedesId = base.supersedesId,
                retracted = base.retracted,
                questNo = payload.intOrNull("questNo"),
                winner = payload.stringOrNull("winner")?.let { alignmentOf(it) },
                winReason = winReasonOf(
                    payload.stringOrNull("winReason"),
                    eventType,
                    entity.id
                ),
                note = payload.stringOrNull("note")
            )
        }
    }

    // ————————————————————————————————————————————————————————
    // 阶段键
    // ————————————————————————————————————————————————————————

    /** `phaseKey` 由事件类型 + questNo 推导，不单独持久化业务语义。 */
    fun phaseKeyOf(event: MatchEvent): String = when {
        event.type == EventType.ASSASSINATION_RECORDED -> "ASSASSINATION"
        event.questNo != null -> "QUEST_${event.questNo}"
        event.type == EventType.MATCH_ABANDONED || event.type == EventType.MATCH_FINALIZED -> "END"
        else -> "SETUP"
    }

    // ————————————————————————————————————————————————————————
    // 内部工具
    // ————————————————————————————————————————————————————————

    private data class BaseFields(
        val eventId: EventId,
        val occurredOrder: Int,
        val recordedAt: Long,
        val visibility: EventVisibility,
        val supersedesId: EventId?,
        val retracted: Boolean
    )

    private fun parsePayload(payloadJson: String, entityId: String): JsonObject = try {
        json.parseToJsonElement(payloadJson).jsonObject
    } catch (e: Exception) {
        throw IllegalStateException("payloadJson 不是合法 JSON 对象：eventId=$entityId", e)
    }

    private fun eventTypeOf(raw: String, entityId: String): EventType =
        EventType.entries.firstOrNull { it.name == raw }
            ?: throw IllegalStateException(
                "未知的 eventType：\"$raw\"（eventId=$entityId）；" +
                    "已知类型=${EventType.entries.joinToString { it.name }}"
            )

    private fun visibilityOf(raw: String): EventVisibility =
        EventVisibility.entries.firstOrNull { it.name == raw } ?: EventVisibility.PUBLIC

    private fun proposalDecisionOf(raw: String?, type: EventType, entityId: String): ProposalDecision {
        if (raw == null) {
            throw IllegalStateException("$type 缺少必填字段 decision（eventId=$entityId）")
        }
        return ProposalDecision.entries.firstOrNull { it.name == raw }
            ?: throw IllegalStateException("$type 的 decision 非法：$raw（eventId=$entityId）")
    }

    private fun claimTypeOf(raw: String?, type: EventType, entityId: String): ClaimType {
        if (raw == null) {
            throw IllegalStateException("$type 缺少必填字段 claimType（eventId=$entityId）")
        }
        return ClaimType.entries.firstOrNull { it.name == raw }
            ?: throw IllegalStateException("$type 的 claimType 非法：$raw（eventId=$entityId）")
    }

    private fun beliefTypeOf(raw: String?, type: EventType, entityId: String): BeliefType {
        if (raw == null) {
            throw IllegalStateException("$type 缺少必填字段 beliefType（eventId=$entityId）")
        }
        return BeliefType.entries.firstOrNull { it.name == raw }
            ?: throw IllegalStateException("$type 的 beliefType 非法：$raw（eventId=$entityId）")
    }

    private fun winReasonOf(raw: String?, type: EventType, entityId: String): WinReason {
        if (raw == null) {
            throw IllegalStateException("$type 缺少必填字段 winReason（eventId=$entityId）")
        }
        return WinReason.entries.firstOrNull { it.name == raw }
            ?: throw IllegalStateException("$type 的 winReason 非法：$raw（eventId=$entityId）")
    }

    private fun missionOutcomeOf(raw: String?): MissionOutcome? {
        if (raw == null) return null
        return MissionOutcome.entries.firstOrNull { it.name == raw }
    }

    private fun alignmentOf(raw: String): Alignment =
        Alignment.entries.firstOrNull { it.name == raw } ?: Alignment.UNKNOWN

    // —— JsonObjectBuilder / JsonObject 扩展 ——

    private fun JsonObjectBuilder.putInt(key: String, value: Int) {
        put(key, JsonPrimitive(value))
    }

    private fun JsonObjectBuilder.putIntOrNull(key: String, value: Int?) {
        if (value == null) put(key, JsonNull) else put(key, JsonPrimitive(value))
    }

    private fun intArray(values: List<SeatId>): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    private fun JsonObject.isNull(key: String): Boolean = this[key] is JsonNull || this[key] == null

    private fun JsonObject.intOrNull(key: String): Int? {
        val element: JsonElement = this[key] ?: return null
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.intOrNull
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val element: JsonElement = this[key] ?: return null
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.booleanOrNull(key: String): Boolean? {
        val element: JsonElement = this[key] ?: return null
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.booleanOrNull
    }

    private fun JsonObject.requiredInt(key: String, type: EventType, entityId: String): Int =
        intOrNull(key)
            ?: throw IllegalStateException("$type 缺少必填字段 $key（eventId=$entityId）")

    private fun JsonObject.intList(key: String): List<SeatId> {
        val element: JsonElement = this[key] ?: return emptyList()
        if (element !is JsonArray) return emptyList()
        return element.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
    }

    private fun JsonObject.votesBySeat(): Map<SeatId, TeamVoteChoice>? {
        val element: JsonElement = this["votesBySeat"] ?: return null
        if (element is JsonNull) return null
        if (element !is JsonObject) return null
        return element.entries.mapNotNull { (seatKey, choiceElement) ->
            val seat = seatKey.toIntOrNull() ?: return@mapNotNull null
            val choice = (choiceElement as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val parsed = TeamVoteChoice.entries.firstOrNull { it.name == choice }
                ?: return@mapNotNull null
            seat to parsed
        }.toMap()
    }

    companion object {

        /** payload 结构版本。 */
        const val PAYLOAD_VERSION: Int = 1

        /** 默认 Json 配置：宽松解析、序列化默认值。 */
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = true
        }
    }
}
