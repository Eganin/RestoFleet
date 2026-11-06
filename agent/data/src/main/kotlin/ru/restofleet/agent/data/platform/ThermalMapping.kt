package ru.restofleet.agent.data.platform

import ru.restofleet.agent.domain.model.ThermalStatus

/** PowerManager.THERMAL_STATUS_* (0..6) и getThermalHeadroom -> домен. */
object ThermalMapping {
    fun status(raw: Int): ThermalStatus? = ThermalStatus.entries.getOrNull(raw)

    /** Float.NaN — платформа не может оценить запас. */
    fun headroom(raw: Float): Double? = if (raw.isNaN()) null else raw.toDouble()
}
