package ru.restofleet.server.simulator.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.server.simulator.domain.device.ThresholdTracker
import ru.restofleet.server.simulator.domain.model.SimEvent
import ru.restofleet.server.simulator.domain.model.TelemetrySample
import ru.restofleet.server.simulator.domain.model.Thresholds

/** Реш. 202, 264: события только на входе и выходе, гистерезис 2 % / 2 °C, `thermal` — от SEVERE. */
class ThresholdTrackerTest {
    private val limits = Thresholds(batteryLowPct = 20, tempHighC = 45.0)

    private fun sample(level: Int = 80, temp: Double = 30.0, thermal: String = "NONE", ts: Long = 1) = TelemetrySample(
        ts = ts, seq = ts, batteryLevel = level, charging = false, plugged = "none", batteryTempC = temp,
        thermalStatus = thermal, rssi = -60, freeMb = 1_000, availMb = 800, uptimeSec = 0, foregroundApp = null,
    )

    private fun ThresholdTracker.states(vararg samples: TelemetrySample) =
        samples.flatMap { evaluate(it, limits) }
            .map { (it as SimEvent.Threshold).let { e -> "${e.metric}:${e.state}" } }

    @Test
    fun batteryLowEntersAtLimitAndExitsWithHysteresis() {
        val tracker = ThresholdTracker()
        assertThat(tracker.states(sample(21), sample(20), sample(19), sample(21), sample(22), sample(20)))
            .containsExactly("battery_low:enter", "battery_low:exit", "battery_low:enter").inOrder()
    }

    @Test
    fun temperatureEntersAtLimitAndExitsTwoDegreesBelow() {
        val tracker = ThresholdTracker()
        val events = listOf(44.9, 45.0, 44.0, 43.1, 43.0).flatMap { tracker.evaluate(sample(temp = it), limits) }
        assertThat(events.map { (it as SimEvent.Threshold).state }).containsExactly("enter", "exit").inOrder()
        val enter = events.first() as SimEvent.Threshold
        assertThat(enter.value).isEqualTo(45.0)
        assertThat(enter.limit).isEqualTo(45.0)
        assertThat(enter.metric).isEqualTo(ThresholdTracker.TEMP_HIGH)
    }

    @Test
    fun thermalEntersFromSevereAndIgnoresUnknownStatus() {
        val tracker = ThresholdTracker()
        assertThat(
            tracker.states(
                sample(thermal = "MODERATE"), sample(thermal = "SEVERE"), sample(thermal = "CRITICAL"),
                sample(thermal = "WEIRD"), sample(thermal = "LIGHT"),
            ),
        ).containsExactly("thermal:enter", "thermal:exit").inOrder()
    }
}
