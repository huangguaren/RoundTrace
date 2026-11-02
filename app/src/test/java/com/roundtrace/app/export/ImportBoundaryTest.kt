package com.roundtrace.app.export

import com.roundtrace.app.core.data.ImportBundle
import com.roundtrace.app.core.data.MatchRepository
import com.roundtrace.app.core.export.DefaultImportExportService
import com.roundtrace.app.core.export.ExportBundle
import com.roundtrace.app.core.export.ExportOptions
import com.roundtrace.app.core.export.ImportFailureReason
import com.roundtrace.app.core.export.ImportLimits
import com.roundtrace.app.core.export.ImportOutcome
import com.roundtrace.app.core.export.ImportResult
import com.roundtrace.app.core.export.MatchJsonCodec
import com.roundtrace.app.core.model.MatchId
import com.roundtrace.app.core.model.Participant
import com.roundtrace.app.core.rules.avalon.AvalonRules
import com.roundtrace.app.util.FakeMatchRepository
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ImportBoundaryTest {
    private val codec = MatchJsonCodec()
    private val source = ExportFixtures.revealedBundle()

    private fun text(): String = codec.encode(source, ExportOptions(includeSecrets = true))

    private fun mutate(block: MutableMap<String, kotlinx.serialization.json.JsonElement>.() -> Unit): String {
        val fields = Json.parseToJsonElement(text()).jsonObject.toMutableMap()
        fields.block()
        return JsonObject(fields).toString()
    }

    private fun rejected(text: String, reason: ImportFailureReason = ImportFailureReason.RESOURCE_LIMIT_EXCEEDED) {
        val result = codec.decode(text)
        assertTrue("应拒绝：$result", result is ImportResult.Failure)
        assertEquals(reason, (result as ImportResult.Failure).reason)
    }

    @Test
    fun versionTwoRoundTripPreservesNormalizedLayoutAndAvatarReference() {
        val participants = listOf(Participant(1, "甲", "rose", 0.2f, 0.8f, "content://images/avatar-1"))
        val exported = codec.encode(source.copy(participants = participants))
        assertTrue(exported.contains("\"schemaVersion\": ${ExportBundle.SCHEMA_VERSION}"))
        val imported = codec.decode(exported) as ImportResult.Success
        assertEquals(participants, imported.bundle.participants)
    }

    @Test
    fun versionOneWithoutNewFieldsUsesParticipantDefaults() {
        val legacy = mutate {
            put("schemaVersion", JsonPrimitive(1))
            put("participants", JsonArray(listOf(JsonObject(mapOf("seat" to JsonPrimitive(1))))))
        }
        val imported = codec.decode(legacy) as ImportResult.Success
        assertEquals(Participant(1), imported.bundle.participants.single())
    }

    @Test
    fun utf8ByteBudgetIsCheckedBeforeJsonParsing() {
        rejected("中".repeat(ImportLimits.MAX_BYTES / 3 + 1))
    }

    @Test
    fun deeplyNestedInputIsRejectedBeforeFullParsing() {
        rejected("{\"x\":".repeat(ImportLimits.MAX_DEPTH + 1) + "0" + "}".repeat(ImportLimits.MAX_DEPTH + 1))
    }

    @Test
    fun bracketsInsideEscapedJsonStringsAreNotCountedAsContainers() {
        val withExtension = mutate { put("futureField", JsonPrimitive("\"[{}]\\".repeat(100))) }
        assertTrue(codec.decode(withExtension) is ImportResult.Success)
    }

    @Test
    fun excessiveEventCountIsRejectedBeforeEventDecoding() {
        rejected(mutate {
            put("events", JsonArray(List(ImportLimits.MAX_EVENTS + 1) { JsonObject(emptyMap()) }))
        })
    }

    @Test
    fun excessiveParticipantCountIsRejected() {
        rejected(mutate {
            put("participants", JsonArray(List(ImportLimits.MAX_PARTICIPANTS + 1) {
                JsonObject(mapOf("seat" to JsonPrimitive(it + 1)))
            }))
        })
    }

    @Test
    fun longEventOrExtensionTextIsRejected() {
        rejected(mutate { put("futureField", JsonPrimitive("x".repeat(ImportLimits.MAX_TEXT_CHARS + 1))) })
    }

    @Test
    fun layoutOutsideNormalizedRangeIsRejected() {
        rejected(
            codec.encode(source.copy(participants = listOf(Participant(1, layoutX = 2f, layoutY = 0f)))),
            ImportFailureReason.TYPE_MISMATCH
        )
    }

    private suspend fun importedAvatars(
        allowed: (String) -> Boolean,
        vararg uris: String
    ): Pair<ImportOutcome, ImportBundle?> {
        var captured: ImportBundle? = null
        val fake = FakeMatchRepository(config = source.config)
        val recording = object : MatchRepository by fake {
            override suspend fun importBundle(bundle: ImportBundle): MatchId {
                captured = bundle
                return MatchId("imported")
            }
        }
        val service = DefaultImportExportService(recording, AvalonRules(), canRestoreAvatarUri = allowed)
        val payload = codec.encode(source.copy(participants = uris.mapIndexed { i, uri -> Participant(i + 1, avatarUri = uri) }))
        return service.importFromJson(payload) to captured
    }

    @Test
    fun actualImportRestoresOnlyApprovedContentReferences() = runTest {
        val (outcome, imported) = importedAvatars(
            { it == "content://images/approved" },
            "content://images/approved", "content://images/foreign", "file:///data/private.png", "https://example.test/a.png"
        )
        assertTrue(outcome is ImportOutcome.Imported)
        assertEquals(3, (outcome as ImportOutcome.Imported).skippedAvatarCount)
        assertEquals(listOf("content://images/approved", null, null, null), imported!!.participants.map { it.avatarUri })
    }

    @Test
    fun platformPermissionFailureClearsReferenceWithoutDiscardingMatch() = runTest {
        val (outcome, imported) = importedAvatars({ throw SecurityException("revoked") }, "content://images/a")
        assertTrue(outcome is ImportOutcome.Imported)
        assertNull(imported!!.participants.single().avatarUri)
    }

    @Test
    fun oversizedImportNeverReachesStorage() = runTest {
        var written = false
        val repository = object : MatchRepository by FakeMatchRepository(config = source.config) {
            override suspend fun importBundle(bundle: ImportBundle): MatchId {
                written = true
                return MatchId("unexpected")
            }
        }
        val service = DefaultImportExportService(repository, AvalonRules())
        val outcome = service.importFromJson("x".repeat(ImportLimits.MAX_BYTES + 1))
        assertEquals(ImportFailureReason.RESOURCE_LIMIT_EXCEEDED, (outcome as ImportOutcome.Rejected).reason)
        assertFalse(written)
    }

    @Test
    fun cancelledStorageIsNotReportedAsImportFailure() = runTest {
        val repository = object : MatchRepository by FakeMatchRepository(config = source.config) {
            override suspend fun importBundle(bundle: ImportBundle): MatchId {
                throw CancellationException("cancelled")
            }
        }
        val service = DefaultImportExportService(repository, AvalonRules())
        try {
            service.importFromJson(text())
            fail("取消必须传播")
        } catch (_: CancellationException) {
            // expected
        }
    }
}
