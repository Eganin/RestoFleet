package ru.restofleet.shared.protocol

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import ru.restofleet.shared.protocol.dto.CommandRequestDto
import ru.restofleet.shared.protocol.dto.CommandResultDto
import ru.restofleet.shared.protocol.dto.CommandStatusDto
import ru.restofleet.shared.protocol.dto.EventDto
import ru.restofleet.shared.protocol.dto.ProbeReportDto
import ru.restofleet.shared.protocol.dto.ProbeStateEventDto
import ru.restofleet.shared.protocol.dto.ShadowDesiredDto
import ru.restofleet.shared.protocol.dto.ShadowReportedDto
import ru.restofleet.shared.protocol.dto.StatusDto
import ru.restofleet.shared.protocol.dto.TelemetryDto
import ru.restofleet.shared.protocol.dto.ThresholdEventDto
import ru.restofleet.shared.protocol.rest.PollCommandsResponseDto
import ru.restofleet.shared.protocol.rest.PollTelemetryRequestDto
import ru.restofleet.shared.protocol.rest.ProvisionRequestDto
import ru.restofleet.shared.protocol.rest.ProvisionResponseDto
import java.io.File
import kotlin.test.assertFailsWith

class FixtureRoundTripTest {

    private val fixturesDir = File(requireNotNull(System.getProperty("fixturesDir")) { "fixturesDir is not set" })

    /** Порядок важен: более длинный префикс — раньше (`rest-poll-commands-response` до `rest-poll-command`). */
    private val serializers: List<Pair<String, KSerializer<*>>> = listOf(
        "rest-provision-request" to ProvisionRequestDto.serializer(),
        "rest-provision-response" to ProvisionResponseDto.serializer(),
        "rest-poll-telemetry-request" to PollTelemetryRequestDto.serializer(),
        "rest-poll-commands-response" to PollCommandsResponseDto.serializer(),
        "rest-poll-command-result" to CommandResultDto.serializer(),
        "status-" to StatusDto.serializer(),
        "telemetry-" to TelemetryDto.serializer(),
        "probe-" to ProbeReportDto.serializer(),
        "event-" to EventDto.serializer(),
        "shadow-desired" to ShadowDesiredDto.serializer(),
        "shadow-reported" to ShadowReportedDto.serializer(),
        "cmd-req" to CommandRequestDto.serializer(),
        "cmd-res" to CommandResultDto.serializer(),
    )

    private val fixtures: List<File>
        get() = fixturesDir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }

    private fun serializerFor(file: File): KSerializer<*>? =
        serializers.firstOrNull { file.name.startsWith(it.first) }?.second

    @Test
    fun fixturesArePresent() {
        assertThat(fixtures.size).isAtLeast(MIN_FIXTURES)
    }

    @Test
    fun everyEventTypeHasFixture() {
        // Подклассы sealed-иерархии: элемент 1 дескриптора — "value" с serial name каждого подкласса
        val declared = EventDto.serializer().descriptor.getElementDescriptor(1).elementNames.toSet()
        val covered = typesOf("event-", "type")
        assertThat(covered).containsExactlyElementsIn(declared)
    }

    @Test
    fun everyCommandStatusHasFixture() {
        val declared = CommandStatusDto.serializer().descriptor.elementNames.toSet()
        assertThat(typesOf("cmd-res-", "status")).containsExactlyElementsIn(declared)
    }

    @Test
    fun everyCommandTypeHasFixture() {
        assertThat(typesOf("cmd-req-", "type")).containsExactlyElementsIn(CommandTypes.ALL)
    }

    @Test
    fun everyStatusReasonHasFixture() {
        assertThat(typesOf("status-offline", "reason")).containsExactly("lwt", "stop")
    }

    @Test
    fun commandResultValuesAreStrings() {
        // ТЗ 7.2: `result` — объект со строковыми значениями (реш. 123); DTO это не навязывает, фикстуры — да
        fixtures.filter { it.name.startsWith("cmd-res-") || it.name.startsWith("rest-poll-command-result") }
            .mapNotNull { (RfJson.parseToJsonElement(it.readText()) as JsonObject)["result"] as? JsonObject }
            .flatMap { it.values }
            .forEach { assertThat((it as JsonPrimitive).isString).isTrue() }
    }

    @Test
    fun everyFixtureHasSerializer() {
        fixtures.forEach { assertWithMessage(it.name).that(serializerFor(it)).isNotNull() }
    }

    private fun typesOf(prefix: String, key: String): Set<String> =
        fixtures.filter { it.name.startsWith(prefix) }
            .mapNotNull { ((RfJson.parseToJsonElement(it.readText()) as JsonObject)[key] as? JsonPrimitive)?.content }
            .toSet()

    @Test
    fun everyFixtureRoundTrips() {
        fixtures.forEach { file ->
            @Suppress("UNCHECKED_CAST")
            val serializer = requireNotNull(serializerFor(file)) as KSerializer<Any>
            val source = file.readText()
            val decoded = RfJson.decodeFromString(serializer, source)
            val encoded = RfJson.encodeToString(serializer, decoded)
            assertWithMessage(file.name)
                .that(RfJson.parseToJsonElement(encoded))
                .isEqualTo(RfJson.parseToJsonElement(source))
        }
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val withFuture = File(fixturesDir, "telemetry-kitchen.json").readText()
            .replaceFirst("{", """{ "futureField": { "x": 1 }, """)
        val dto = RfJson.decodeFromString(TelemetryDto.serializer(), withFuture)
        assertThat(dto.seq).isEqualTo(18234)
    }

    @Test
    fun unknownProbeErrorCodeDecodesAsString() {
        val json = """{ "ts": 1, "results": [ { "id": "api", "ok": false, "error": "quic_fail", "totalMs": 10 } ] }"""
        val dto = RfJson.decodeFromString(ProbeReportDto.serializer(), json)
        assertThat(dto.results.single().error).isEqualTo("quic_fail")
    }

    @Test
    fun unknownEventTypeFails() {
        // Фиксирует текущее поведение черновика: неизвестный тип события не декодируется (обработка — в К на бэкенде)
        assertFailsWith<SerializationException> {
            RfJson.decodeFromString(EventDto.serializer(), """{ "type": "teleport", "ts": 1 }""")
        }
    }

    @Test
    fun eventsDecodeToTypedSubclasses() {
        val source = File(fixturesDir, "event-probe-state.json").readText()
        val event = RfJson.decodeFromString(EventDto.serializer(), source)
        val expected = ProbeStateEventDto(
            ts = 1793865700000, target = "api", state = "down", error = "timeout", failures = 3,
        )
        assertThat(event).isEqualTo(expected)
    }

    @Test
    fun thresholdEventDecodesTyped() {
        val event = RfJson.decodeFromString(
            EventDto.serializer(), File(fixturesDir, "event-threshold-enter.json").readText(),
        )
        assertThat(event).isEqualTo(
            ThresholdEventDto(ts = 1793865750000, metric = "battery_low", state = "enter", value = 19.0, limit = 20.0),
        )
    }

    @Test
    fun expectedStatusIsOptional() {
        val desired = RfJson.decodeFromString(
            ShadowDesiredDto.serializer(), File(fixturesDir, "shadow-desired-expected-status.json").readText(),
        )
        assertThat(desired.probes.single().expectedStatus).isEqualTo(204)
        val plain = RfJson.decodeFromString(
            ShadowDesiredDto.serializer(), File(fixturesDir, "shadow-desired.json").readText(),
        )
        assertThat(plain.probes.map { it.expectedStatus }).containsExactly(null, null, null)
    }

    @Test
    fun commandDefaultsAreApplied() {
        val dto = RfJson.decodeFromString(CommandRequestDto.serializer(), """{ "cmdId": "c-1", "type": "ping" }""")
        assertThat(dto.params).isEqualTo(JsonObject(emptyMap()))
        assertThat(dto.expiresAt).isNull()
    }

    private companion object {
        const val MIN_FIXTURES = 39
    }

    @Test
    fun commandStatusUsesLowercaseWireNames() {
        val dto = CommandResultDto(cmdId = "c-1", status = CommandStatusDto.SUCCEEDED, ts = 5)
        val json = RfJson.parseToJsonElement(RfJson.encodeToString(CommandResultDto.serializer(), dto)) as JsonObject
        assertThat(json["status"]).isEqualTo(JsonPrimitive("succeeded"))
    }
}
