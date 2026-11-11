package ru.restofleet.shared.protocol

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import ru.restofleet.shared.protocol.dto.AgentLifecycleEventDto
import ru.restofleet.shared.protocol.dto.AppVersionDto
import ru.restofleet.shared.protocol.dto.BatteryDto
import ru.restofleet.shared.protocol.dto.CapabilityCheckEventDto
import ru.restofleet.shared.protocol.dto.CommandRequestDto
import ru.restofleet.shared.protocol.dto.DeviceInfoDto
import ru.restofleet.shared.protocol.dto.DeviceStateDto
import ru.restofleet.shared.protocol.dto.EventDto
import ru.restofleet.shared.protocol.dto.FgAbsentEventDto
import ru.restofleet.shared.protocol.dto.FgChangedEventDto
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
import ru.restofleet.shared.protocol.dto.ThresholdEventDto
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
        assertThat(keysOf(DeviceInfoDto.serializer(), dto))
            .containsExactly("model", "manufacturer", "osVersion", "apiLevel")
    }

    @Test
    fun minimalProbeResultHasOnlyRequiredKeys() {
        val report = ProbeReportDto(ts = 1, results = listOf(ProbeResultDto(id = "a", ok = true, totalMs = 3)))
        val json = RfJson.encodeToJsonElement(ProbeReportDto.serializer(), report) as JsonObject
        val result = (json["results"] as JsonArray).single() as JsonObject
        assertThat(result.keys).containsExactly("id", "ok", "totalMs")
    }

    @Test
    fun eventsCarryTypeDiscriminator() {
        val events: List<EventDto> = listOf(
            AgentLifecycleEventDto(ts = 1, phase = "start"),
            PackageChangedEventDto(ts = 1, pkg = "p", change = "removed"),
            NetChangedEventDto(ts = 1, net = NetDto(type = "wifi", validated = true)),
            CapabilityCheckEventDto(ts = 1, checkId = "R1", status = "OK"),
            ThresholdEventDto(ts = 1, metric = "battery_low", state = "enter", value = 19.0, limit = 20.0),
            FgChangedEventDto(ts = 1, from = "a.b", to = "c.d"),
            FgAbsentEventDto(ts = 1, pkg = "a.b", sinceTs = 0, limitMin = 10),
        )
        val types = events.map {
            (RfJson.encodeToJsonElement(EventDto.serializer(), it) as JsonObject)["type"]
        }
        assertThat(types)
            .containsExactly(
                JsonPrimitive("agent_lifecycle"),
                JsonPrimitive("package_changed"),
                JsonPrimitive("net_changed"),
                JsonPrimitive("capability_check"),
                JsonPrimitive("threshold"),
                JsonPrimitive("fg_changed"),
                JsonPrimitive("fg_absent"),
            )
            .inOrder()
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

        val target = ProbeTargetDto(
            id = "a", type = "http", url = "https://x", intervalSec = 30, timeoutMs = 5_000, failThreshold = 3,
        )
        assertThat(keysOf(ProbeTargetDto.serializer(), target)).contains("reference")
        assertThat(keysOf(ProbeTargetDto.serializer(), target)).doesNotContain("expectedStatus")
    }

    @Test
    fun minimalFgEventsOmitUnknownApps() {
        val changed = FgChangedEventDto(ts = 1)
        assertThat(keysOf(EventDto.serializer(), changed)).containsExactly("type", "ts")
        val absent = FgAbsentEventDto(ts = 1, pkg = "a.b", sinceTs = 0, limitMin = 10)
        assertThat(keysOf(EventDto.serializer(), absent)).containsExactly("type", "ts", "pkg", "sinceTs", "limitMin")
    }

    @Test
    fun minimalCommandRequestWritesEmptyParams() {
        val dto = CommandRequestDto(cmdId = "c", type = "ping")
        assertThat(keysOf(CommandRequestDto.serializer(), dto)).containsExactly("cmdId", "type", "params")
    }
}
