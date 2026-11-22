package ru.restofleet.server.simulator.domain.device

import ru.restofleet.server.simulator.domain.model.SimEvent
import ru.restofleet.server.simulator.domain.model.TelemetrySample
import ru.restofleet.server.simulator.domain.model.Thresholds
import ru.restofleet.server.simulator.domain.profile.Thermal

/**
 * Пересечения порогов по тем же правилам, что у агента (реш. 202, 264): событие только на входе в зону и выходе из
 * неё. `battery_low`: вход при заряде ≤ `batteryLowPct`, выход при ≥ порог + 2; `temp_high`: вход при ≥ `tempHighC`,
 * выход при ≤ порог − 2; `thermal`: вход от `SEVERE` (3), выход ниже. Начальное состояние — «вне зоны». Зарядка не
 * отменяет `battery_low` (как у агента: смотрится только уровень).
 */
class ThresholdTracker {
    private var batteryLow = false
    private var tempHigh = false
    private var thermal = false

    fun evaluate(sample: TelemetrySample, limits: Thresholds): List<SimEvent> = buildList {
        val level = sample.batteryLevel.toDouble()
        val batteryLimit = limits.batteryLowPct.toDouble()
        crossing(batteryLow, enter = level <= batteryLimit, exit = level >= batteryLimit + HYSTERESIS)?.let {
            batteryLow = it
            add(event(sample.ts, BATTERY_LOW, it, level, batteryLimit))
        }
        val temp = sample.batteryTempC
        crossing(tempHigh, enter = temp >= limits.tempHighC, exit = temp <= limits.tempHighC - HYSTERESIS)?.let {
            tempHigh = it
            add(event(sample.ts, TEMP_HIGH, it, temp, limits.tempHighC))
        }
        val code = Thermal.NAMES.indexOf(sample.thermalStatus)
        if (code >= 0) {
            crossing(thermal, enter = code >= Thermal.SEVERE, exit = code < Thermal.SEVERE)?.let {
                thermal = it
                add(event(sample.ts, THERMAL, it, code.toDouble(), Thermal.SEVERE.toDouble()))
            }
        }
    }

    /** Новое состояние «в зоне», если оно сменилось; иначе `null`. */
    private fun crossing(inZone: Boolean, enter: Boolean, exit: Boolean): Boolean? = when {
        !inZone && enter -> true
        inZone && exit -> false
        else -> null
    }

    private fun event(ts: Long, metric: String, entered: Boolean, value: Double, limit: Double) =
        SimEvent.Threshold(ts, metric, if (entered) ENTER else EXIT, value, limit)

    companion object {
        const val BATTERY_LOW = "battery_low"
        const val TEMP_HIGH = "temp_high"
        const val THERMAL = "thermal"
        const val ENTER = "enter"
        const val EXIT = "exit"
        private const val HYSTERESIS = 2.0
    }
}
