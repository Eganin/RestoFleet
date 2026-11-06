package ru.restofleet.agent.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.domain.port.SequenceSource
import kotlin.test.assertFailsWith

class ReadingTest {

    @Test
    fun batteryLevelAboveHundredIsRejected() {
        assertFailsWith<IllegalArgumentException> { BatteryState(101, true, PowerSource.AC, null) }
    }

    @Test
    fun batteryLevelBelowZeroIsRejected() {
        assertFailsWith<IllegalArgumentException> { BatteryState(-1, true, PowerSource.AC, null) }
    }

    @Test
    fun batteryLevelBoundsAreAccepted() {
        assertThat(BatteryState(0, false, PowerSource.NONE, 30.5).levelPct).isEqualTo(0)
        assertThat(BatteryState(100, true, PowerSource.USB, null).levelPct).isEqualTo(100)
    }

    @Test
    fun availableReadingHoldsValueAndIsComparableByValue() {
        val reading: Reading<NetworkState> = Reading.Available(NetworkState(NetworkType.WIFI, -60, true))

        assertThat(reading).isEqualTo(Reading.Available(NetworkState(NetworkType.WIFI, -60, true)))
        assertThat((reading as Reading.Available).value.type).isEqualTo(NetworkType.WIFI)
    }

    @Test
    fun unavailableReadingCarriesReason() {
        val reading: Reading<ThermalState> = 
            Reading.Unavailable(UnavailableReason.ApiLevelTooLow(required = 29, actual = 26))

        assertThat(reading).isEqualTo(Reading.Unavailable(UnavailableReason.ApiLevelTooLow(29, 26)))
        assertThat(UnavailableReason.NoSensor).isNotEqualTo(UnavailableReason.EmulatorStub)
    }

    @Test
    fun snapshotKeepsUnavailableReasonsByKey() {
        val snapshot = TelemetrySnapshot(
            ts = 1L, seq = 2L, battery = null, thermal = null, network = null, resources = null,
            foregroundApp = null, apps = listOf(AppVersion("ru.x", null, 3L)),
            unavailable = mapOf("thermal" to UnavailableReason.PermissionMissing("p")),
        )

        assertThat(snapshot.unavailable["thermal"]).isEqualTo(UnavailableReason.PermissionMissing("p"))
        assertThat(snapshot.copy(seq = 3L).seq).isEqualTo(3L)
    }

    @Test
    fun thermalStatusesAreOrderedBySeverity() {
        assertThat(ThermalStatus.entries.first()).isEqualTo(ThermalStatus.NONE)
        assertThat(ThermalStatus.SEVERE.ordinal).isGreaterThan(ThermalStatus.MODERATE.ordinal)
        assertThat(ThermalStatus.entries.last()).isEqualTo(ThermalStatus.SHUTDOWN)
        assertThat(ThermalState(null, null)).isEqualTo(ThermalState(status = null, headroom = null))
    }

    @Test
    fun everyUnavailableReasonIsDistinguishable() {
        val reasons = listOf(
            UnavailableReason.NoSensor,
            UnavailableReason.RequiresDeviceOwner,
            UnavailableReason.EmulatorStub,
            UnavailableReason.Failed("boom"),
            UnavailableReason.PermissionMissing("android.permission.X"),
            UnavailableReason.ApiLevelTooLow(required = 29, actual = 26),
        )

        assertThat(reasons.toSet()).hasSize(reasons.size)
        assertThat(UnavailableReason.Failed("boom")).isEqualTo(UnavailableReason.Failed("boom"))
    }

    @Test
    fun deviceResourcesAreComparedByValue() {
        val resources = DeviceResources(1024, 512, 3600, "14", 34, "Pixel", "Google")

        assertThat(resources.copy(uptimeSec = 7200).uptimeSec).isEqualTo(7200)
        assertThat(resources).isEqualTo(DeviceResources(1024, 512, 3600, "14", 34, "Pixel", "Google"))
        assertThat(resources).isNotEqualTo(resources.copy(model = "Other"))
    }

    @Test
    fun appAndSensorValuesAreComparedByValue() {
        assertThat(ForegroundApp("ru.x", 5L)).isEqualTo(ForegroundApp("ru.x", 5L))
        assertThat(LightLevel(12.5f)).isEqualTo(LightLevel(12.5f))
        val temps = HardwareTemps(cpuC = listOf(40f), skinC = listOf(35f), batteryC = emptyList())
        assertThat(temps).isEqualTo(HardwareTemps(listOf(40f), listOf(35f), emptyList()))
        assertThat(temps.hashCode()).isEqualTo(HardwareTemps(listOf(40f), listOf(35f), emptyList()).hashCode())
    }

    @Test
    fun sequenceSourceIsReplaceableByLambda() {
        var n = 0L
        val source = SequenceSource { ++n }
        assertThat(source.next()).isEqualTo(1L)
    }
}
