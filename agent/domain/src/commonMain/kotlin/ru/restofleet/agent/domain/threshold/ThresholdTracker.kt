package ru.restofleet.agent.domain.threshold

import ru.restofleet.agent.domain.model.AgentEvent.ThresholdCrossed
import ru.restofleet.agent.domain.model.TelemetrySnapshot
import ru.restofleet.agent.domain.model.ThermalStatus
import ru.restofleet.agent.domain.model.ThresholdMetric
import ru.restofleet.agent.domain.model.Thresholds

/**
 * Пороги FR-AG-40 (реш. 202, 218): событие только при входе в зону и выходе из неё, не на каждом тике.
 * - `battery_low`: вход при заряде ≤ `batteryLowPct`, выход при ≥ `batteryLowPct` + 2;
 * - `temp_high`: вход при температуре батареи ≥ `tempHighC`, выход при ≤ `tempHighC` − 2;
 * - `thermal`: вход при статусе от `SEVERE` (код 3), выход ниже него.
 * Нет считывания — состояние метрики не меняется и события нет. Начальное состояние — «вне зоны»: после перезапуска
 * агента уже идущее пересечение сообщается заново (оговорка плана 3.3). Не потокобезопасен.
 */
class ThresholdTracker {
    private val inZone = mutableSetOf<ThresholdMetric>()

    fun evaluate(snapshot: TelemetrySnapshot, limits: Thresholds): List<ThresholdCrossed> {
        val battery = snapshot.battery
        val batteryLimit = limits.batteryLowPct.toDouble()
        val readings = listOfNotNull(
            battery?.let { Reading(ThresholdMetric.BATTERY_LOW, it.levelPct.toDouble(), batteryLimit) },
            battery?.tempC?.let { Reading(ThresholdMetric.TEMP_HIGH, it, limits.tempHighC) },
            snapshot.thermal?.status?.let { Reading(ThresholdMetric.THERMAL, it.ordinal.toDouble(), THERMAL_LIMIT) },
        )
        return readings.mapNotNull { r ->
            step(r)?.let { entered -> ThresholdCrossed(snapshot.ts, r.metric, entered, r.value, r.limit) }
        }
    }

    /** `true` — вход в зону, `false` — выход, `null` — состояние не изменилось. */
    private fun step(r: Reading): Boolean? {
        val inside = r.metric in inZone
        return when {
            !inside && r.enters() -> true.also { inZone += r.metric }
            inside && r.exits() -> false.also { inZone -= r.metric }
            else -> null
        }
    }

    private data class Reading(val metric: ThresholdMetric, val value: Double, val limit: Double) {
        fun enters(): Boolean = when (metric) {
            ThresholdMetric.BATTERY_LOW -> value <= limit
            ThresholdMetric.TEMP_HIGH, ThresholdMetric.THERMAL -> value >= limit
        }

        fun exits(): Boolean = when (metric) {
            ThresholdMetric.BATTERY_LOW -> value >= limit + BATTERY_HYSTERESIS_PCT
            ThresholdMetric.TEMP_HIGH -> value <= limit - TEMP_HYSTERESIS_C
            ThresholdMetric.THERMAL -> value < limit
        }
    }

    private companion object {
        const val BATTERY_HYSTERESIS_PCT = 2.0
        const val TEMP_HYSTERESIS_C = 2.0

        /** Порог теплового статуса — `SEVERE`: в тени его нет, константа агента (реш. 218). */
        val THERMAL_LIMIT = ThermalStatus.SEVERE.ordinal.toDouble()
    }
}
