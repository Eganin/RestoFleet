package ru.restofleet.shared.protocol

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import ru.restofleet.shared.protocol.dto.AgentLifecycleEventDto
import ru.restofleet.shared.protocol.dto.AppVersionDto
import ru.restofleet.shared.protocol.dto.BatteryDto
import ru.restofleet.shared.protocol.dto.CapabilityCheckEventDto
import ru.restofleet.shared.protocol.dto.CommandRequestDto
import ru.restofleet.shared.protocol.dto.DeviceInfoDto
import ru.restofleet.shared.protocol.dto.DeviceStateDto
import ru.restofleet.shared.protocol.dto.EventDto
import ru.restofleet.shared.protocol.dto.MemoryDto
import ru.restofleet.shared.protocol.dto.NetChangedEventDto
import ru.restofleet.shared.protocol.dto.NetDto
import ru.restofleet.shared.protocol.dto.PackageChangedEventDto
import ru.restofleet.shared.protocol.dto.ProbeReportDto
import ru.restofleet.shared.protocol.dto.ProbeResultDto
import ru.restofleet.shared.protocol.dto.ProbeTargetDto
import ru.restofleet.shared.protocol.dto.ShadowDesiredDto
import ru.restofleet.shared.protocol.dto.ShadowReportedDto
import ru.restofleet.shared.protocol.dto.StatusDto
import ru.restofleet.shared.protocol.dto.StorageDto
import ru.restofleet.shared.protocol.dto.TelemetryConfigDto
import ru.restofleet.shared.protocol.dto.TelemetryDto
import ru.restofleet.shared.protocol.dto.ThermalDto
import ru.restofleet.shared.protocol.dto.ThresholdsDto

/**
 * Контракт канонического вида: DTO, собранный только из обязательных полей, кодируется
 * без `null` (explicitNulls=false), а значения по умолчанию у коллекций и флагов пишутся явно (encodeDefaults=true).
 */
class MinimalDtoEncodingTest {

    private fun <T> keysOf(serializer: KSerializer<T>, value: T): Set<String> =
        (RfJson.encodeToJsonElement(serializer, value) as JsonObject).keys

    @Test
    fun minimalStatusHasOnlyRequiredKeys() {
        val dto = StatusDto(state = DeviceStateDto.ONLINE, ts = 1, connId = "k")
        assertThat(keysOf(StatusDto.serializer(), dto)).containsExactly("state", "ts", "connId")
    }

    @Test
    fun minimalTelemetryWritesEmptyAppsAndNoNulls() {
        val dto = TelemetryDto(ts = 1, seq = 2)
        assertThat(keysOf(TelemetryDto.serializer(), dto)).containsExactly("ts", "seq", "apps")
    }

    @Test
    fun nestedTelemetryPartsOmitAbsentValues() {
        val dto = TelemetryDto(
            ts = 1, seq = 2,
            battery = BatteryDto(level = 50, charging = false),
            thermal = ThermalDto(),
            net = NetDto(type = "none", validated = false),
            storage = StorageDto(freeMb = 1), memory = MemoryDto(availMb = 1),
            apps = listOf(AppVersionDto(pkg = "p", versionCode = 1)),
        )
        val json = RfJson.encodeToJsonElement(TelemetryDto.serializer(), dto) as JsonObject
        assertThat((json["battery"] as JsonObject).keys).containsExactly("level", "charging")
        assertThat((json["thermal"] as JsonObject).keys).isEmpty()
        assertThat((json["net"] as JsonObject).keys).containsExactly("type", "validated")
    }

    @Test
    fun deviceInfoHasAllFields() {
        val dto = DeviceInfoDto(model = "m", manufacturer = "g", osVersion = "8.0", apiLevel = 26)
        assertThat(keysOf(DeviceInfoDto.serializer(), dto)).containsExactly("model", "manufacturer", "osVersion", "apiLevel")
    }

    @Test
    fun minimalProbeResultHasOnlyRequiredKeys() {
        val report = ProbeReportDto(ts = 1, results = listOf(ProbeResultDto(id = "a", ok = true, totalMs = 3)))
        val json = RfJson.encodeToJsonElement(ProbeReportDto.serializer(), report) as JsonObject
        val result = (json["results"] as kotlinx.serialization.json.JsonArray).single() as JsonObject
        assertThat(result.keys).containsExactly("id", "ok", "totalMs")
    }

    @Test
    fun eventsCarryTypeDiscriminator() {
        val events: List<EventDto> = listOf(
            AgentLifecycleEventDto(ts = 1, phase = "start"),
            PackageChangedEventDto(ts = 1, pkg = "p", change = "removed"),
            NetChangedEventDto(ts = 1, net = NetDto(type = "wifi", validated = true)),
            CapabilityCheckEventDto(ts = 1, checkId = "R1", status = "OK"),
        )
        val types = events.map { (RfJson.encodeToJsonElement(EventDto.serializer(), it) as JsonObject)["type"].toString() }
        assertThat(types).containsExactly("\"agent_lifecycle\"", "\"package_changed\"", "\"net_changed\"", "\"capability_check\"").inOrder()
    }

    @Test
    fun shadowDefaultsAreWrittenExplicitly() {
        val desired = ShadowDesiredDto(
            version = 1,
            telemetry = TelemetryConfigDto(intervalSec = 30),
            thresholds = ThresholdsDto(batteryLowPct = 20, tempHighC = 45.0, fgAbsentMin = 10),
        )
        assertThat(keysOf(ShadowDesiredDto.serializer(), desired))
            .containsExactly("version", "telemetry", "thresholds", "watchedApps", "probes")

        val reported = ShadowReportedDto(
            version = 1, telemetry = desired.telemetry, thresholds = desired.thresholds,
            appliedVersion = 1, appliedAt = 2,
        )
        assertThat(keysOf(ShadowReportedDto.serializer(), reported)).contains("errors")

        val target = ProbeTargetDto(id = "a", type = "http", url = "https://x", intervalSec = 30, timeoutMs = 5_000, failThreshold = 3)
        assertThat(keysOf(ProbeTargetDto.serializer(), target)).contains("reference")
    }

    @Test
    fun minimalCommandRequestWritesEmptyParams() {
        val dto = CommandRequestDto(cmdId = "c", type = "ping")
        assertThat(keysOf(CommandRequestDto.serializer(), dto)).containsExactly("cmdId", "type", "params")
    }
}
