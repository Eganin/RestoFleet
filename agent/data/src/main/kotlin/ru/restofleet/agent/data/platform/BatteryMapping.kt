package ru.restofleet.agent.data.platform

import ru.restofleet.agent.domain.model.BatteryState
import ru.restofleet.agent.domain.model.PowerSource
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason

/** Сырые значения ACTION_BATTERY_CHANGED -> [BatteryState]. */
object BatteryMapping {
    private const val PERCENT = 100

    /** BatteryManager.BATTERY_STATUS_CHARGING */
    private const val STATUS_CHARGING = 2

    /** BatteryManager.BATTERY_STATUS_FULL */
    private const val STATUS_FULL = 5

    /** BatteryManager.BATTERY_PLUGGED_AC */
    private const val PLUGGED_AC = 1

    /** BatteryManager.BATTERY_PLUGGED_USB */
    private const val PLUGGED_USB = 2

    /** BatteryManager.BATTERY_PLUGGED_WIRELESS */
    private const val PLUGGED_WIRELESS = 4

    /** BatteryManager.BATTERY_PLUGGED_DOCK */
    private const val PLUGGED_DOCK = 8

    private const val TENTHS = 10.0

    /** Температура в EXTRA_TEMPERATURE отсутствует (getIntExtra с Int.MIN_VALUE по умолчанию). */
    private const val TEMP_ABSENT = Int.MIN_VALUE

    fun from(level: Int, scale: Int, status: Int, plugged: Int, tempTenths: Int): Reading<BatteryState> {
        if (scale <= 0 || level < 0 || level > scale) {
            return Reading.Unavailable(UnavailableReason.Failed("battery level=$level scale=$scale"))
        }
        val tempC = if (tempTenths == TEMP_ABSENT) null else tempTenths / TENTHS
        return Reading.Available(
            BatteryState(
                levelPct = level * PERCENT / scale,
                charging = status == STATUS_CHARGING || status == STATUS_FULL,
                source = source(plugged),
                tempC = tempC,
            ),
        )
    }

    // Map вместо when: when по плотным константам компилируется в tableswitch с «дырами», которые Kover считает ветвями
    private val SOURCES = mapOf(
        PLUGGED_AC to PowerSource.AC,
        PLUGGED_USB to PowerSource.USB,
        PLUGGED_WIRELESS to PowerSource.WIRELESS,
        PLUGGED_DOCK to PowerSource.DOCK,
    )

    private fun source(plugged: Int): PowerSource = SOURCES[plugged] ?: PowerSource.NONE
}
