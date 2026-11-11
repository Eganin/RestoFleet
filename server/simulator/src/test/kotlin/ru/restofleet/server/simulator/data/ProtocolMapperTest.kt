package ru.restofleet.server.simulator.data

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import ru.restofleet.server.simulator.data.protocol.InboundMapper
import ru.restofleet.server.simulator.data.protocol.ProtocolMapper
import ru.restofleet.server.simulator.domain.model.CommandReply
import ru.restofleet.server.simulator.domain.model.DeviceConfig
import ru.restofleet.server.simulator.domain.model.DeviceRole
import ru.restofleet.server.simulator.domain.model.ProbeReport
import ru.restofleet.server.simulator.domain.model.ProbeResult
import ru.restofleet.server.simulator.domain.model.ReplyStatus
import ru.restofleet.server.simulator.domain.model.ReportedShadow
import ru.restofleet.server.simulator.domain.model.SimEvent
import ru.restofleet.server.simulator.domain.model.StatusInfo
import ru.restofleet.server.simulator.domain.model.TelemetrySample
import ru.restofleet.server.simulator.domain.model.Thresholds
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.dto.CommandResultDto
import ru.restofleet.shared.protocol.dto.EventDto
import ru.restofleet.shared.protocol.dto.ShadowReportedDto
import ru.restofleet.shared.protocol.dto.StatusDto
import ru.restofleet.shared.protocol.rest.PollTelemetryRequestDto
import java.io.File

/** FR-SM-02: сообщения симулятора — DTO протокола; разбор и выпуск сверяются с golden-фикстурами. */
class ProtocolMapperTest {
    private val fixtures = File(System.getProperty("fixturesDir"))

    private fun fixture(name: String) = File(fixtures, name).readText()

    private fun json(text: String) = RfJson.parseToJsonElement(text).jsonObject

    private val sample = TelemetrySample(
        ts = 1_793_865_630_000, seq = 42, batteryLevel = 71, charging = false, plugged = "none", batteryTempC = 33.5,
        thermalStatus = "NONE", rssi = -58, freeMb = 2_048, availMb = 900, uptimeSec = 7_200,
        foregroundApp = "ru.restofleet.dummy.waiter",
    )

    @Test
    fun desiredFixtureIsDecodedIntoAnUpdate() {
        val update = InboundMapper.desired(fixture("shadow-desired.json"))!!
        assertThat(update.version).isEqualTo(12)
        assertThat(update.telemetryIntervalSec).isEqualTo(30)
        assertThat(update.thresholds).isEqualTo(Thresholds(20, 45.0, 10))
        assertThat(update.probes.map { it.id }).containsExactly("api", "kds", "ref").inOrder()
        assertThat(update.probes.last().reference).isTrue()
        val expected = InboundMapper.desired(fixture("shadow-desired-expected-status.json"))!!
        assertThat(expected.probes.first().expectedStatus).isEqualTo(204)
        assertThat(InboundMapper.desired("{not json")).isNull()
        assertThat(InboundMapper.desired("""{"version":1}""")).isNull()
    }

    @Test
    fun reportedEchoesTheAppliedConfigLikeTheFixture() {
        val update = InboundMapper.desired(fixture("shadow-desired.json"))!!
        val config = DeviceConfig(
            12, update.telemetryIntervalSec, update.thresholds, listOf("com.android.chrome"), update.probes.take(1),
        )
        val dto = ProtocolMapper.reported(ReportedShadow(config, 12, 1_793_865_605_000, emptyList()))
        val expected = RfJson.decodeFromString(ShadowReportedDto.serializer(), fixture("shadow-reported.json"))
        assertThat(dto).isEqualTo(expected)
    }

    @Test
    fun commandsAndResultsMatchFixtures() {
        val command = InboundMapper.command(fixture("cmd-req-ping.json"), receivedAtNanos = 9, byteArrayOf(7))!!
        assertThat(command.cmdId).isEqualTo("c-01J9Z5")
        assertThat(command.type).isEqualTo("ping")
        assertThat(command.expiresAt).isEqualTo(1_793_866_100_000)
        assertThat(command.receivedAtNanos).isEqualTo(9)
        assertThat(InboundMapper.command(fixture("cmd-req-run-probe.json"), 0)!!.params).containsKey("target")
        assertThat(InboundMapper.command("[]", 0)).isNull()

        val reply = CommandReply(
            "c-01J9Z5", ReplyStatus.SUCCEEDED, 1_793_865_802_000, mapOf("deviceTime" to "1793865802000"),
        )
        val expected = RfJson.decodeFromString(CommandResultDto.serializer(), fixture("cmd-res-succeeded.json"))
        assertThat(ProtocolMapper.commandResult(reply)).isEqualTo(expected)
        ReplyStatus.entries.forEach { status ->
            val dto = ProtocolMapper.commandResult(reply.copy(status = status))
            val encoded = RfJson.encodeToString(CommandResultDto.serializer(), dto)
            assertThat(json(encoded)["status"]!!.jsonPrimitive.content).isEqualTo(status.code)
        }
    }

    @Test
    fun pollTelemetryHasTheSameShapeAsTheRestFixture() {
        val event = SimEvent.Threshold(1_793_865_625_000, "battery_low", "exit", 22.0, 20.0)
        val probe = ProbeReport(1_793_865_630_000, listOf(ProbeResult("api", true, 200, null, 120)))
        val dto = ProtocolMapper.pollTelemetry(sample, probe, listOf(event))
        val encoded = json(RfJson.encodeToString(PollTelemetryRequestDto.serializer(), dto))
        val fixture = json(fixture("rest-poll-telemetry-request.json"))
        assertThat(encoded.keys).isEqualTo(fixture.keys)
        assertThat(encoded["events"]).isEqualTo(fixture["events"])
        assertThat(encoded["probe"]).isEqualTo(fixture["probe"])
        val telemetry = encoded["telemetry"] as JsonObject
        assertThat(telemetry.keys).containsAtLeastElementsIn((fixture["telemetry"] as JsonObject).keys)
        assertThat(telemetry["foregroundApp"]!!.jsonPrimitive.content).isEqualTo("ru.restofleet.dummy.waiter")
    }

    @Test
    fun eventsCarryTheirTypeDiscriminator() {
        val probeState = SimEvent.ProbeState(1, "api", "down", "timeout", 3)
        val encoded = json(RfJson.encodeToString(EventDto.serializer(), ProtocolMapper.event(probeState)))
        assertThat(encoded["type"]!!.jsonPrimitive.content).isEqualTo("probe_state")
        val fixture = json(fixture("event-probe-state.json"))
        assertThat(encoded.keys).isEqualTo(fixture.keys)
    }

    @Test
    fun statusOnlineAndOfflineFollowTheFixtures() {
        val online = ProtocolMapper.online(StatusInfo(DeviceRole.KITCHEN, "simulator-1.0", "b-1"), "k-1", 5)
        val encoded = json(RfJson.encodeToString(StatusDto.serializer(), online))
        assertThat(encoded.keys).isEqualTo(json(fixture("status-online.json")).keys)
        assertThat(encoded["role"]!!.jsonPrimitive.content).isEqualTo("kitchen")
        val lwt = json(RfJson.encodeToString(StatusDto.serializer(), ProtocolMapper.offline("k-1", 5, "lwt")))
        assertThat(lwt.keys).isEqualTo(json(fixture("status-offline-lwt.json")).keys)
    }

    @Test
    fun pollCommandsResponseIsDecoded() {
        val commands = InboundMapper.pollCommands(fixture("rest-poll-commands-response.json"), receivedAtNanos = 3)!!
        assertThat(commands).isNotEmpty()
        assertThat(commands.all { it.receivedAtNanos == 3L }).isTrue()
        assertThat(InboundMapper.pollCommands("nope", 0)).isNull()
    }
}
