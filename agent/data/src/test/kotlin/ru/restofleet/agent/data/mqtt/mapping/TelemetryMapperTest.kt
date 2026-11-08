package ru.restofleet.agent.data.mqtt.mapping

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.domain.model.AppVersion
import ru.restofleet.agent.domain.model.BatteryState
import ru.restofleet.agent.domain.model.DeviceResources
import ru.restofleet.agent.domain.model.ForegroundApp
import ru.restofleet.agent.domain.model.NetworkState
import ru.restofleet.agent.domain.model.NetworkType
import ru.restofleet.agent.domain.model.PowerSource
import ru.restofleet.agent.domain.model.TelemetrySnapshot
import ru.restofleet.agent.domain.model.ThermalState
import ru.restofleet.agent.domain.model.ThermalStatus
import ru.restofleet.agent.domain.model.UnavailableReason
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.dto.TelemetryDto

class TelemetryMapperTest {
    private val resources = DeviceResources(
        freeStorageMb = 5120,
        availMemoryMb = 1430,
        uptimeSec = 360021,
        osVersion = "15",
        apiLevel = 35,
        model = "Pixel",
        manufacturer = "Google",
    )

    private val kitchen = TelemetrySnapshot(
        ts = 1793865630000,
        seq = 18234,
        battery = BatteryState(levelPct = 87, charging = true, source = PowerSource.AC, tempC = 38.4),
        thermal = ThermalState(ThermalStatus.LIGHT, 0.62),
        network = NetworkState(NetworkType.WIFI, rssiDbm = -67, validated = true),
        resources = resources,
        foregroundApp = ForegroundApp("com.android.chrome", sinceMillis = 1793865000000),
        apps = listOf(AppVersion("com.android.chrome", "130.0", 0)),
        unavailable = emptyMap(),
    )

    private val api26 = TelemetrySnapshot(
        ts = 1793865630000,
        seq = 1,
        battery = BatteryState(levelPct = 54, charging = false, source = PowerSource.NONE, tempC = 31.0),
        thermal = null,
        network = NetworkState(NetworkType.WIFI, rssiDbm = null, validated = false),
        resources = resources.copy(freeStorageMb = 812, availMemoryMb = 640, uptimeSec = 120),
        foregroundApp = null,
        apps = emptyList(),
        unavailable = mapOf(
            "thermal" to UnavailableReason.ApiLevelTooLow(required = 29, actual = 26),
            "foregroundApp" to UnavailableReason.PermissionMissing("android.permission.PACKAGE_USAGE_STATS"),
        ),
    )

    @Test
    fun kitchenSnapshotMatchesFixture() {
        assertMatchesFixture("telemetry-kitchen", TelemetryDto.serializer(), TelemetryMapper.toDto(kitchen))
    }

    @Test
    fun api26SnapshotMatchesFixtureWithoutUnavailableReadings() {
        assertMatchesFixture("telemetry-api26-minimal", TelemetryDto.serializer(), TelemetryMapper.toDto(api26))
    }

    @Test
    fun everyPowerSourceAndNetworkTypeHasLowercaseWireName() {
        val plugged = PowerSource.entries.map {
            TelemetryMapper.toDto(kitchen.copy(battery = kitchen.battery!!.copy(source = it))).battery!!.plugged
        }
        val nets = NetworkType.entries.map {
            TelemetryMapper.netToDto(NetworkState(it, null, true)).type
        }

        assertThat(plugged).containsExactly("ac", "usb", "wireless", "dock", "none").inOrder()
        assertThat(nets).containsExactly("wifi", "cellular", "ethernet", "none", "other").inOrder()
    }

    @Test
    fun thermalWithoutAnyValueIsOmitted() {
        val dto = TelemetryMapper.toDto(kitchen.copy(thermal = ThermalState(status = null, headroom = null)))

        assertThat(dto.thermal).isNull()
    }

    @Test
    fun thermalWithOnlyHeadroomKeepsIt() {
        val dto = TelemetryMapper.toDto(kitchen.copy(thermal = ThermalState(status = null, headroom = 0.5)))

        assertThat(dto.thermal!!.status).isNull()
        assertThat(dto.thermal!!.headroom).isEqualTo(0.5)
    }

    @Test
    fun missingResourcesOmitStorageMemoryAndUptime() {
        val dto = TelemetryMapper.toDto(kitchen.copy(resources = null))

        assertThat(dto.storage).isNull()
        assertThat(dto.memory).isNull()
        assertThat(dto.uptimeSec).isNull()
    }

    @Test
    fun deviceModelAndOsAreNotSentInTelemetry() {
        val json = RfJson.encodeToString(TelemetryDto.serializer(), TelemetryMapper.toDto(kitchen))

        assertThat(json).doesNotContain("Pixel")
        assertThat(json).doesNotContain("manufacturer")
    }
}
