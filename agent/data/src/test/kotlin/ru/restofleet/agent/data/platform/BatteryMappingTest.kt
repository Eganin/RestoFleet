package ru.restofleet.agent.data.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.domain.model.BatteryState
import ru.restofleet.agent.domain.model.PowerSource
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason

class BatteryMappingTest {

    private fun available(r: Reading<BatteryState>): BatteryState = (r as Reading.Available).value

    @Test
    fun typicalCharging() {
        val r = BatteryMapping.from(level = 87, scale = 100, status = 2, plugged = 1, tempTenths = 384)
        assertThat(r).isEqualTo(Reading.Available(BatteryState(87, true, PowerSource.AC, 38.4)))
    }

    @Test
    fun invalidLevelOrScaleIsFailed() {
        listOf(0 to 0, -1 to 100, 50 to -5, 101 to 100).forEach { (level, scale) ->
            val r = BatteryMapping.from(level, scale, status = 2, plugged = 1, tempTenths = 300)
            assertThat(r).isInstanceOf(Reading.Unavailable::class.java)
            assertThat((r as Reading.Unavailable).reason).isInstanceOf(UnavailableReason.Failed::class.java)
        }
    }

    @Test
    fun chargingByStatus() {
        // 2 = BATTERY_STATUS_CHARGING, 5 = BATTERY_STATUS_FULL, 3 = DISCHARGING, 4 = NOT_CHARGING, 1 = UNKNOWN
        val expected = mapOf(1 to false, 2 to true, 3 to false, 4 to false, 5 to true)
        expected.forEach { (status, charging) ->
            assertThat(available(BatteryMapping.from(50, 100, status, 0, 300)).charging).isEqualTo(charging)
        }
    }

    @Test
    fun powerSourceByPlugged() {
        val expected = mapOf(
            1 to PowerSource.AC,
            2 to PowerSource.USB,
            4 to PowerSource.WIRELESS,
            8 to PowerSource.DOCK,
            0 to PowerSource.NONE,
            16 to PowerSource.NONE,
        )
        expected.forEach { (plugged, source) ->
            assertThat(available(BatteryMapping.from(50, 100, 2, plugged, 300)).source).isEqualTo(source)
        }
    }

    @Test
    fun unknownTemperatureIsNull() {
        assertThat(available(BatteryMapping.from(50, 100, 2, 1, Int.MIN_VALUE)).tempC).isNull()
    }

    @Test
    fun levelIsScaledToPercent() {
        assertThat(available(BatteryMapping.from(50, 200, 3, 0, 250)).levelPct).isEqualTo(25)
    }
}
