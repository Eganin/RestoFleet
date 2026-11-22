package ru.restofleet.agent.domain.threshold

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import ru.restofleet.agent.domain.model.AgentEvent.ThresholdCrossed
import ru.restofleet.agent.domain.model.BatteryState
import ru.restofleet.agent.domain.model.PowerSource
import ru.restofleet.agent.domain.model.TelemetrySnapshot
import ru.restofleet.agent.domain.model.ThermalState
import ru.restofleet.agent.domain.model.ThermalStatus
import ru.restofleet.agent.domain.model.ThresholdMetric
import ru.restofleet.agent.domain.model.Thresholds

/** FR-AG-40, реш. 202, 218: событие только на входе в зону и выходе из неё с гистерезисом 2 % / 2 °C. */
class ThresholdTrackerTest {
    private val limits = Thresholds(batteryLowPct = 20, tempHighC = 45.0, fgAbsentMin = 10)

    private fun snapshot(
        level: Int? = 80,
        tempC: Double? = 30.0,
        thermal: ThermalStatus? = ThermalStatus.NONE,
        ts: Long = 1,
    ) = TelemetrySnapshot(
            ts = ts,
            seq = 1,
            battery = level?.let { BatteryState(it, false, PowerSource.NONE, tempC) },
            thermal = ThermalState(thermal, null),
            network = null,
            resources = null,
            foregroundApp = null,
            apps = emptyList(),
            unavailable = emptyMap(),
        )

    /** Прогоняет серию снимков и возвращает признаки событий метрики [metric] по шагам («enter»/«exit»/«-»). */
    private fun trace(metric: ThresholdMetric, snapshots: List<TelemetrySnapshot>): List<String> {
        val tracker = ThresholdTracker()
        return snapshots.map { s ->
            val events = tracker.evaluate(s, limits).filter { it.metric == metric }
            when {
                events.isEmpty() -> "-"
                else -> events.single().let { if (it.entered) "enter" else "exit" }
            }
        }
    }

    @Test
    fun batteryLowHasHysteresisOfTwoPercent() {
        val levels = listOf(25, 21, 20, 19, 20, 21, 22, 21, 20)
        val expected = listOf("-", "-", "enter", "-", "-", "-", "exit", "-", "enter")

        assertThat(trace(ThresholdMetric.BATTERY_LOW, levels.map { snapshot(level = it) })).isEqualTo(expected)
    }

    @Test
    fun tempHighHasHysteresisOfTwoDegrees() {
        val temps = listOf(40.0, 44.9, 45.0, 46.0, 43.1, 43.0, 44.0, 45.5)
        val expected = listOf("-", "-", "enter", "-", "-", "exit", "-", "enter")

        assertThat(trace(ThresholdMetric.TEMP_HIGH, temps.map { snapshot(tempC = it) })).isEqualTo(expected)
    }

    @Test
    fun thermalEntersFromSevereAndExitsBelowIt() {
        val statuses = listOf(
            ThermalStatus.LIGHT, ThermalStatus.MODERATE, ThermalStatus.SEVERE, ThermalStatus.CRITICAL,
            ThermalStatus.SEVERE, ThermalStatus.MODERATE, ThermalStatus.EMERGENCY,
        )
        val expected = listOf("-", "-", "enter", "-", "-", "exit", "enter")

        assertThat(trace(ThresholdMetric.THERMAL, statuses.map { snapshot(thermal = it) })).isEqualTo(expected)
    }

    @Test
    fun eventCarriesValueLimitAndSnapshotTime() {
        val tracker = ThresholdTracker()

        val hot = snapshot(level = 19, tempC = 47.5, thermal = ThermalStatus.SEVERE, ts = 77)

        val events = tracker.evaluate(hot, limits)

        assertThat(events).containsExactly(
            ThresholdCrossed(77, ThresholdMetric.BATTERY_LOW, entered = true, value = 19.0, limit = 20.0),
            ThresholdCrossed(77, ThresholdMetric.TEMP_HIGH, entered = true, value = 47.5, limit = 45.0),
            ThresholdCrossed(77, ThresholdMetric.THERMAL, entered = true, value = 3.0, limit = 3.0),
        ).inOrder()
    }

    @Test
    fun missingReadingsKeepStateAndSendNothing() {
        val tracker = ThresholdTracker()
        tracker.evaluate(snapshot(level = 10, tempC = 50.0, thermal = ThermalStatus.SEVERE), limits)

        val gap = tracker.evaluate(snapshot(level = null, thermal = null), limits)
        val noTemp = tracker.evaluate(snapshot(level = 10, tempC = null, thermal = ThermalStatus.SEVERE), limits)
        val back = tracker.evaluate(snapshot(level = 10, tempC = 50.0, thermal = ThermalStatus.SEVERE), limits)

        assertWithMessage("gap").that(gap).isEmpty()
        assertWithMessage("no temperature").that(noTemp).isEmpty()
        assertWithMessage("back in the same zone").that(back).isEmpty()
    }

    @Test
    fun loweredLimitIsAppliedOnTheNextTick() {
        val tracker = ThresholdTracker()
        tracker.evaluate(snapshot(level = 18), limits)

        val events = tracker.evaluate(snapshot(level = 18), limits.copy(batteryLowPct = 15))

        assertThat(events.single().entered).isFalse()
        assertThat(events.single().limit).isEqualTo(15.0)
    }
}
