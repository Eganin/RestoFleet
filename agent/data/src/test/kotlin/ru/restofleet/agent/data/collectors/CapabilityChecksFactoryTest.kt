package ru.restofleet.agent.data.collectors

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.domain.model.AppVersion
import ru.restofleet.agent.domain.model.BatteryState
import ru.restofleet.agent.domain.model.DeviceResources
import ru.restofleet.agent.domain.model.ForegroundApp
import ru.restofleet.agent.domain.model.NetworkState
import ru.restofleet.agent.domain.model.NetworkType
import ru.restofleet.agent.domain.model.PowerSource
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.ThermalState
import ru.restofleet.agent.domain.model.ThermalStatus
import ru.restofleet.agent.domain.model.UnavailableReason

class CapabilityChecksFactoryTest {
    private val battery = BatteryState(80, true, PowerSource.AC, 31.5)

    @Test
    fun batteryTempIsExtracted() {
        assertThat(CheckValues.batteryTemp(Reading.Available(battery))).isEqualTo(Reading.Available(31.5))
    }

    @Test
    fun batteryWithoutTempIsFailed() {
        val reading = CheckValues.batteryTemp(Reading.Available(battery.copy(tempC = null)))
        assertThat(reading).isEqualTo(Reading.Unavailable(UnavailableReason.Failed("no temperature in broadcast")))
    }

    @Test
    fun unavailableBatteryIsPassedThrough() {
        val reading = CheckValues.batteryTemp(Reading.Unavailable(UnavailableReason.NoSensor))
        assertThat(reading).isEqualTo(Reading.Unavailable(UnavailableReason.NoSensor))
    }

    @Test
    fun batteryRaw() {
        assertThat(CheckValues.batteryRaw(battery))
            .containsExactly("level", "80", "charging", "true", "source", "AC")
    }

    @Test
    fun thermalRaw() {
        assertThat(CheckValues.thermalRaw(ThermalState(ThermalStatus.LIGHT, 0.5)))
            .containsExactly("status", "LIGHT", "headroom", "0.5")
    }

    @Test
    fun networkRaw() {
        assertThat(CheckValues.networkRaw(NetworkState(NetworkType.WIFI, -50, true)))
            .containsExactly("type", "WIFI", "rssiDbm", "-50", "validated", "true")
    }

    @Test
    fun resourcesRaw() {
        val raw = CheckValues.resourcesRaw(DeviceResources(10, 20, 30, "14", 34, "Pixel", "Google"))
        assertThat(raw).containsExactly(
            "freeStorageMb", "10", "availMemoryMb", "20", "uptimeSec", "30",
            "os", "14", "api", "34", "model", "Pixel", "manufacturer", "Google",
        )
    }

    @Test
    fun appsRawMarksMissingPackages() {
        val raw = CheckValues.appsRaw(listOf("a", "b"), listOf(AppVersion("a", "1.2", 12)))
        assertThat(raw).containsExactly("a", "1.2 (12)", "b", "not installed")
    }

    @Test
    fun foregroundRaw() {
        assertThat(CheckValues.foregroundRaw(ForegroundApp("p", 5)))
            .containsExactly("pkg", "p", "since", "5")
    }

    @Test
    fun motionSupportBothSensors() {
        assertThat(CheckValues.motionSupport(significant = true, accelerometer = true))
            .isEqualTo(Reading.Available(mapOf("significantMotion" to "true", "accelerometer" to "true")))
    }

    @Test
    fun motionSupportNamesMissingSensors() {
        assertThat(CheckValues.motionSupport(significant = false, accelerometer = true))
            .isEqualTo(Reading.Unavailable(UnavailableReason.Failed("missing sensor: significant_motion")))
        assertThat(CheckValues.motionSupport(significant = true, accelerometer = false))
            .isEqualTo(Reading.Unavailable(UnavailableReason.Failed("missing sensor: accelerometer")))
        assertThat(CheckValues.motionSupport(significant = false, accelerometer = false))
            .isEqualTo(
                Reading.Unavailable(UnavailableReason.Failed("missing sensor: significant_motion, accelerometer")),
            )
    }
}
