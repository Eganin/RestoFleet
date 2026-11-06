package ru.restofleet.shared.protocol

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.KSerializer
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
import java.io.File
import kotlin.test.assertFailsWith

class FixtureRoundTripTest {

    private val fixturesDir = File(requireNotNull(System.getProperty("fixturesDir")) { "fixturesDir is not set" })

    private val serializers: List<Pair<String, KSerializer<*>>> = listOf(
        "status-" to StatusDto.serializer(),
        "telemetry-" to TelemetryDto.serializer(),
        "probe-" to ProbeReportDto.serializer(),
        "event-" to EventDto.serializer(),
        "shadow-desired" to ShadowDesiredDto.serializer(),
        "shadow-reported" to ShadowReportedDto.serializer(),
        "cmd-req" to CommandRequestDto.serializer(),
        "cmd-res" to CommandResultDto.serializer(),
    )

    private val fixtures: List<File> get() = fixturesDir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }

    private fun serializerFor(file: File): KSerializer<*>? = serializers.firstOrNull { file.name.startsWith(it.first) }?.second

    @Test
    fun fixturesArePresent() {
        assertThat(fixtures.size).isAtLeast(15)
    }

    @Test
    fun everyFixtureHasSerializer() {
        fixtures.forEach { assertWithMessage(it.name).that(serializerFor(it)).isNotNull() }
    }

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
        val event = RfJson.decodeFromString(EventDto.serializer(), File(fixturesDir, "event-probe-state.json").readText())
        assertThat(event).isEqualTo(ProbeStateEventDto(ts = 1793865700000, target = "api", state = "down", error = "timeout", failures = 3))
    }

    @Test
    fun commandDefaultsAreApplied() {
        val dto = RfJson.decodeFromString(CommandRequestDto.serializer(), """{ "cmdId": "c-1", "type": "ping" }""")
        assertThat(dto.params).isEqualTo(JsonObject(emptyMap()))
        assertThat(dto.expiresAt).isNull()
    }

    @Test
    fun commandStatusUsesLowercaseWireNames() {
        val dto = CommandResultDto(cmdId = "c-1", status = CommandStatusDto.SUCCEEDED, ts = 5)
        val json = RfJson.parseToJsonElement(RfJson.encodeToString(CommandResultDto.serializer(), dto)) as JsonObject
        assertThat(json["status"]).isEqualTo(JsonPrimitive("succeeded"))
    }
}
