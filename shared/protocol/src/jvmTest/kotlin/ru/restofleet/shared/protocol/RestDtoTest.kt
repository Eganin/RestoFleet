package ru.restofleet.shared.protocol

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import ru.restofleet.shared.protocol.dto.CommandResultDto
import ru.restofleet.shared.protocol.dto.CommandStatusDto
import ru.restofleet.shared.protocol.dto.DeviceInfoDto
import ru.restofleet.shared.protocol.dto.TelemetryDto
import ru.restofleet.shared.protocol.rest.MqttEndpointDto
import ru.restofleet.shared.protocol.rest.PollCommandResultDto
import ru.restofleet.shared.protocol.rest.PollCommandsResponseDto
import ru.restofleet.shared.protocol.rest.PollTelemetryRequestDto
import ru.restofleet.shared.protocol.rest.ProvisionRequestDto
import ru.restofleet.shared.protocol.rest.ProvisionResponseDto
import ru.restofleet.shared.protocol.rest.RestPaths

/** REST-контракт провижининга и polling (ТЗ 8.2, P9, реш. 222): канонический вид и пути. */
class RestDtoTest {

    private fun <T> keysOf(serializer: KSerializer<T>, value: T): Set<String> =
        (RfJson.encodeToJsonElement(serializer, value) as JsonObject).keys

    @Test
    fun provisionRequestCarriesCodeDeviceInfoAndAgentVersion() {
        val dto = ProvisionRequestDto(
            code = "c",
            deviceInfo = DeviceInfoDto(model = "m", manufacturer = "g", osVersion = "16", apiLevel = 36),
            agentVersion = "1.0.0",
        )
        assertThat(keysOf(ProvisionRequestDto.serializer(), dto)).containsExactly("code", "deviceInfo", "agentVersion")
    }

    @Test
    fun provisionResponseCarriesBrokerEndpoint() {
        val dto = ProvisionResponseDto(
            deviceId = "d", password = "p", restaurantId = "r", role = "hall",
            mqtt = MqttEndpointDto(host = "h", port = 8883),
        )
        assertThat(keysOf(ProvisionResponseDto.serializer(), dto))
            .containsExactly("deviceId", "password", "restaurantId", "role", "mqtt")
    }

    @Test
    fun provisionResponseToStringHidesPassword() {
        val dto = ProvisionResponseDto(
            deviceId = "d", password = "s3cr3t", restaurantId = "r", role = "hall",
            mqtt = MqttEndpointDto(host = "h", port = 8883),
        )
        assertThat(dto.toString()).doesNotContain("s3cr3t")
        assertThat(dto.toString()).contains("deviceId=d")
    }

    @Test
    fun minimalPollTelemetryHasEmptyEventsAndNoProbe() {
        val dto = PollTelemetryRequestDto(telemetry = TelemetryDto(ts = 1, seq = 1))
        assertThat(keysOf(PollTelemetryRequestDto.serializer(), dto)).containsExactly("telemetry", "events")
    }

    @Test
    fun emptyPollCommandsResponseWritesEmptyList() {
        val dto = PollCommandsResponseDto()
        assertThat(keysOf(PollCommandsResponseDto.serializer(), dto)).containsExactly("commands")
    }

    @Test
    fun pollCommandResultIsCmdRes() {
        val dto: PollCommandResultDto = CommandResultDto(cmdId = "c", status = CommandStatusDto.RECEIVED, ts = 1)
        assertThat(RfJson.encodeToString(CommandResultDto.serializer(), dto))
            .isEqualTo("""{"cmdId":"c","status":"received","ts":1}""")
    }

    @Test
    fun pathsAreUnderApiV1() {
        assertThat(RestPaths.PROVISION).isEqualTo("/api/v1/provision")
        assertThat(RestPaths.POLL_TELEMETRY).isEqualTo("/api/v1/poll/telemetry")
        assertThat(RestPaths.POLL_COMMANDS).isEqualTo("/api/v1/poll/commands")
        assertThat(RestPaths.POLL_COMMAND_RESULT).isEqualTo("/api/v1/poll/commands/result")
    }
}
