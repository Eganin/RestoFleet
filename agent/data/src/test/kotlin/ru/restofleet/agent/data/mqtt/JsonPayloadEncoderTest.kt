package ru.restofleet.agent.data.mqtt

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import ru.restofleet.agent.data.mqtt.mapping.EventMapper
import ru.restofleet.agent.data.mqtt.mapping.Fixtures
import ru.restofleet.agent.data.mqtt.mapping.ProbeMapper
import ru.restofleet.agent.data.mqtt.mapping.ShadowMapper
import ru.restofleet.agent.data.mqtt.mapping.TelemetryMapper
import ru.restofleet.agent.domain.command.CommandOutcome
import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.model.ProbeError
import ru.restofleet.agent.domain.model.ProbePhases
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.agent.domain.model.ProbeTarget
import ru.restofleet.agent.domain.model.TelemetrySnapshot
import ru.restofleet.agent.domain.model.Thresholds
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.dto.EventDto
import ru.restofleet.shared.protocol.dto.ProbeReportDto
import ru.restofleet.shared.protocol.dto.ShadowDesiredDto
import ru.restofleet.shared.protocol.dto.TelemetryDto
import org.junit.Assert.assertThrows

class JsonPayloadEncoderTest {
    private val encoder = JsonPayloadEncoder()

    private fun json(bytes: ByteArray) = RfJson.parseToJsonElement(bytes.decodeToString()) as JsonObject

    @Test
    fun receivedMatchesFixtureAndHasNoResult() {
        val bytes = encoder.commandResult("c-01J9Z3", "received", null, 1_793_865_800_000)

        assertThat(json(bytes)).isEqualTo(Fixtures.json("cmd-res-received"))
        assertThat(json(bytes).containsKey("result")).isFalse()
    }

    @Test
    fun succeededWithMapHasStringValues() {
        val bytes = encoder.commandResult(
            "c-1",
            "succeeded",
            CommandOutcome.Succeeded(mapOf("versionCode" to "14", "package" to "ru.x")),
            5,
        )

        assertThat(json(bytes)["result"])
            .isEqualTo(JsonObject(mapOf("versionCode" to JsonPrimitive("14"), "package" to JsonPrimitive("ru.x"))))
        assertThat(json(bytes)["status"]).isEqualTo(JsonPrimitive("succeeded"))
    }

    @Test
    fun succeededWithoutDataIsEmptyObject() {
        val bytes = encoder.commandResult("c-1", "succeeded", CommandOutcome.Succeeded(), 5)

        assertThat(json(bytes)["result"]).isEqualTo(JsonObject(emptyMap()))
    }

    @Test
    fun failedAndUnsupportedCarryReasonWithoutResult() {
        val failed = json(encoder.commandResult("c-1", "failed", CommandOutcome.Failed("expired"), 5))
        val unsupported =
            json(encoder.commandResult("c-2", "unsupported", CommandOutcome.Unsupported("requires_device_owner"), 5))

        assertThat(failed["reason"]).isEqualTo(JsonPrimitive("expired"))
        assertThat(failed.containsKey("result")).isFalse()
        assertThat(unsupported["reason"]).isEqualTo(JsonPrimitive("requires_device_owner"))
        assertThat(unsupported).isEqualTo(Fixtures.json("cmd-res-unsupported").let { it as JsonObject }.let { fixture ->
            JsonObject(fixture + mapOf("cmdId" to JsonPrimitive("c-2"), "ts" to JsonPrimitive(5)) +
                ("reason" to JsonPrimitive("requires_device_owner")))
        })
    }

    @Test
    fun unknownStatusIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { encoder.commandResult("c-1", "weird", null, 5) }
    }

    @Test
    fun reportedMatchesFixture() {
        val shadow = DesiredShadow(
            version = 12,
            telemetryIntervalSec = 30,
            watchedApps = listOf("com.android.chrome"),
            probes = listOf(ProbeTarget("api", "https://api.company.example/health", 5000, 30, 3, false)),
            thresholds = Thresholds(20, 45.0, 10),
        )

        val bytes = encoder.reported(shadow, 1_793_865_605_000, emptyList())

        assertThat(json(bytes)).isEqualTo(Fixtures.json("shadow-reported"))
    }

    @Test
    fun reportedEchoesTheDesiredProbesIncludingExpectedStatus() {
        // Эхо desired → reported без потерь: delta(desired, reported) пуста (реш. 219, 224)
        val desired = Fixtures.decode("shadow-desired-expected-status", ShadowDesiredDto.serializer())
        val shadow = ShadowMapper.fromDto(desired)

        val reported = json(encoder.reported(shadow, 7, emptyList()))
        val desiredJson = Fixtures.json("shadow-desired-expected-status") as JsonObject

        listOf("version", "telemetry", "thresholds", "watchedApps", "probes").forEach { key ->
            assertWithMessage(key).that(reported[key]).isEqualTo(desiredJson[key])
        }
    }

    @Test
    fun reportedWithoutExpectedStatusOmitsTheField() {
        val shadow = DesiredShadow(3, 30, emptyList(), listOf(ProbeTarget("api", "https://a.example/", 5000)))

        val probe = json(encoder.reported(shadow, 7, emptyList()))["probes"].toString()

        assertThat(probe).doesNotContain("expectedStatus")
    }

    @Test
    fun reportedCarriesErrors() {
        val shadow = DesiredShadow(3, 30, emptyList(), emptyList())

        val bytes = encoder.reported(shadow, 7, listOf("telemetryIntervalSec:out_of_range"))

        assertThat(json(bytes)["errors"].toString()).contains("telemetryIntervalSec:out_of_range")
        assertThat(json(bytes)["appliedVersion"]).isEqualTo(JsonPrimitive(3))
    }

    @Test
    fun telemetryEqualsMapperOutput() {
        val snapshot = TelemetrySnapshot(
            ts = 10, seq = 4, battery = null, thermal = null, network = null, resources = null,
            foregroundApp = null, apps = emptyList(), unavailable = emptyMap(),
        )

        val decoded = RfJson.decodeFromString(TelemetryDto.serializer(), encoder.telemetry(snapshot).decodeToString())

        assertThat(decoded).isEqualTo(TelemetryMapper.toDto(snapshot))
    }

    @Test
    fun eventEqualsMapperOutputWithTypeDiscriminator() {
        val event = AgentEvent.Lifecycle(1_793_865_590_000, AgentEvent.Lifecycle.Phase.BOOT)

        val bytes = encoder.event(event)

        assertThat(json(bytes)).isEqualTo(Fixtures.json("event-agent-lifecycle"))
        assertThat(RfJson.decodeFromString(EventDto.serializer(), bytes.decodeToString()))
            .isEqualTo(EventMapper.toDto(event))
    }

    @Test
    fun probesUseLatestResultTimestamp() {
        val results = listOf(probe("a", ts = 100), probe("b", ts = 250))

        val decoded = RfJson.decodeFromString(ProbeReportDto.serializer(), encoder.probes(results).decodeToString())

        assertThat(decoded).isEqualTo(ProbeMapper.toDto(250, results))
    }

    private fun probe(id: String, ts: Long) = ProbeResult(
        targetId = id, ok = false, httpStatus = null, error = ProbeError.TIMEOUT, totalMs = 5000,
        phases = ProbePhases(null, null, null, null), ts = ts,
    )
}
