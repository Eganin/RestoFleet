package ru.restofleet.agent.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
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
}
