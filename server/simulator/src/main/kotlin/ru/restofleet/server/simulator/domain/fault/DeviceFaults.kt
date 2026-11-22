package ru.restofleet.server.simulator.domain.fault

import ru.restofleet.server.simulator.domain.model.SimEvent
import ru.restofleet.server.simulator.domain.model.TelemetrySample

/** Отказы устройств (FR-SM-04): что меняется в телеметрии и событиях на время отказа. */
enum class DeviceFaultType(val code: String) {
    /** Нагрев до 47,5 °C и `SEVERE`: пороги `temp_high` и `thermal` → R-03. */
    OVERHEAT("overheat"),

    /** На экране не отслеживаемое приложение: `fg_changed`, через `fgAbsentMin` — `fg_absent` → R-04. */
    APP_CLOSED("app_closed"),

    /** Заряд падает до 12 % без зарядки: `battery_low` → R-02. */
    DISCHARGE("discharge"),

    /** Свободно 400 МБ → R-06 (порог бэкенда). */
    LOW_STORAGE("low_storage"),

    /** Соединение рвётся и восстанавливается в конце отказа (массовое переподключение). */
    MASS_RECONNECT("mass_reconnect"),
    ;

    companion object {
        fun fromCode(code: String): DeviceFaultType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Отказ из `faults:` сценария: начиная с [atSec] от старта прогона, [durationSec] секунд, у [devices] устройств прогона
 * подряд с номера [offset] (детерминированно: прогон на N устройств всегда берёт одни и те же).
 */
data class DeviceFault(
    val type: DeviceFaultType,
    val atSec: Int,
    val durationSec: Int,
    val devices: Int = 1,
    val offset: Int = 0,
) {
    init {
        require(atSec >= 0 && durationSec >= 1) { "fault atSec must be >= 0, durationSec >= 1" }
        require(devices >= 1 && offset >= 0) { "fault devices must be >= 1, offset >= 0" }
    }

    fun covers(deviceIndex: Int): Boolean = deviceIndex in offset until offset + devices

    fun activeAt(elapsedMs: Long): Boolean = elapsedMs >= atSec * MS && elapsedMs < (atSec + durationSec) * MS

    private companion object {
        const val MS = 1_000L
    }
}

/** Отказы одного устройства прогона: какие действуют в момент `now` (мс от старта прогона [startMillis]). */
class FaultPlan(private val faults: List<DeviceFault>, private val deviceIndex: Int, private val startMillis: Long) {
    fun activeAt(now: Long): Set<DeviceFaultType> =
        faults.filter { it.covers(deviceIndex) && it.activeAt(now - startMillis) }.map { it.type }.toSet()

    companion object {
        val NONE = FaultPlan(emptyList(), 0, 0)
    }
}

/**
 * Применение отказов к снимку (чистые функции): значения подменяются до проверки порогов, так что пороговые события
 * порождает тот же `ThresholdTracker`, что и у здорового устройства.
 */
object FaultEffects {
    const val OVERHEAT_TEMP_C = 47.5
    const val DISCHARGED_PCT = 12
    const val LOW_FREE_MB = 400L
    const val OTHER_APP = "com.android.settings"
    private const val SEVERE = "SEVERE"
    private const val NOT_PLUGGED = "none"

    fun apply(sample: TelemetrySample, active: Set<DeviceFaultType>): TelemetrySample {
        var s = sample
        if (DeviceFaultType.OVERHEAT in active) {
            s = s.copy(batteryTempC = maxOf(s.batteryTempC, OVERHEAT_TEMP_C), thermalStatus = SEVERE)
        }
        if (DeviceFaultType.DISCHARGE in active) {
            s = s.copy(batteryLevel = minOf(s.batteryLevel, DISCHARGED_PCT), charging = false, plugged = NOT_PLUGGED)
        }
        if (DeviceFaultType.LOW_STORAGE in active) s = s.copy(freeMb = minOf(s.freeMb, LOW_FREE_MB))
        if (DeviceFaultType.APP_CLOSED in active) s = s.copy(foregroundApp = OTHER_APP)
        return s
    }
}

/**
 * Отслеживание приложения на экране, как у агента (FR-AG-24): смена — `fg_changed`; отслеживаемое приложение не на
 * экране дольше `limitMin` — один `fg_absent` до возврата. Начальное состояние — отслеживаемое на экране.
 */
class ForegroundTracker(private val watched: String?) {
    private var current: String? = watched
    private var absentSince: Long? = null
    private var reported = false

    fun evaluate(foreground: String?, now: Long, limitMin: Int): List<SimEvent> = buildList {
        if (watched == null) return@buildList
        if (foreground != current) {
            add(SimEvent.FgChanged(now, current, foreground))
            current = foreground
            absentSince = if (foreground == watched) null else now
            reported = false
        }
        val since = absentSince
        if (since != null && !reported && now - since >= limitMin * MS_IN_MIN) {
            add(SimEvent.FgAbsent(now, watched, since, limitMin, foreground))
            reported = true
        }
    }

    private companion object {
        const val MS_IN_MIN = 60_000L
    }
}
