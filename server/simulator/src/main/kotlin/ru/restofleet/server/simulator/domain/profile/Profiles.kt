package ru.restofleet.server.simulator.domain.profile

import ru.restofleet.server.simulator.domain.model.DeviceRole
import java.time.Instant
import java.time.ZoneId
import kotlin.math.exp
import kotlin.random.Random

/**
 * Физическое состояние виртуального устройства, которое ведёт профиль (FR-SM-03). [heatBias] — индивидуальная
 * добавка к нагреву (например, планшет ближе к плите), задаётся при создании и не меняется.
 */
data class ProfileState(
    val batteryPct: Double,
    val charging: Boolean,
    val tempC: Double,
    val freeMb: Long,
    val availMb: Long,
    val rssi: Int,
    val heatBias: Double,
)

/**
 * Профиль устройства: чистый генератор, детерминированный от [Random] (seed сценария + номер устройства). [next] —
 * состояние через [dtSec] секунд, [hour] — местное время суток (0 ≤ hour < 24).
 */
interface DeviceProfile {
    val role: DeviceRole
    val foregroundApp: String

    /** Источник питания при зарядке (`ac` | `usb` | `dock`). */
    val plugged: String

    fun initial(random: Random): ProfileState

    fun next(state: ProfileState, hour: Double, dtSec: Double, random: Random): ProfileState
}

/** Кухня: всегда на зарядке; в часы пик (12–15, 18–21) нагрев у плиты до 40 °C + индивидуальная добавка 0…5 °C. */
object KitchenProfile : DeviceProfile {
    override val role = DeviceRole.KITCHEN
    override val foregroundApp = "ru.restofleet.dummy.kitchen"
    override val plugged = "ac"

    private const val CHARGE_PER_HOUR = 5.0
    private const val BASE_TEMP = 31.0
    private const val PEAK_TEMP = 40.0
    private const val MAX_BIAS = 5.0
    private const val TEMP_TAU_SEC = 600.0
    private val PEAKS = listOf(12.0..15.0, 18.0..21.0)

    fun isPeak(hour: Double): Boolean = PEAKS.any { hour >= it.start && hour < it.endInclusive }

    override fun initial(random: Random) = Physics.initial(random, battery = 80.0..100.0, charging = true)
        .copy(heatBias = random.nextDouble(0.0, MAX_BIAS))

    override fun next(state: ProfileState, hour: Double, dtSec: Double, random: Random): ProfileState {
        val target = if (isPeak(hour)) PEAK_TEMP + state.heatBias else BASE_TEMP
        return Physics.step(state, dtSec, random, charging = true, ratePerHour = CHARGE_PER_HOUR)
            .copy(tempC = Physics.relax(state.tempC, target, dtSec, TEMP_TAU_SEC, random))
    }
}

/** Зал (официант): в смену 10–23 разряжается ≈ 6 %/ч, после смены — на зарядке (+25 %/ч). */
object HallProfile : DeviceProfile {
    override val role = DeviceRole.HALL
    override val foregroundApp = "ru.restofleet.dummy.waiter"
    override val plugged = "usb"

    private const val SHIFT_START = 10.0
    private const val SHIFT_END = 23.0
    private const val DRAIN_PER_HOUR = -6.0
    private const val CHARGE_PER_HOUR = 25.0
    private const val BASE_TEMP = 30.0
    private const val CHARGING_HEAT = 2.0
    private const val TEMP_TAU_SEC = 900.0

    fun inShift(hour: Double): Boolean = hour >= SHIFT_START && hour < SHIFT_END

    override fun initial(random: Random) = Physics.initial(random, battery = 55.0..100.0, charging = false)

    override fun next(state: ProfileState, hour: Double, dtSec: Double, random: Random): ProfileState {
        val charging = !inShift(hour)
        val rate = if (charging) CHARGE_PER_HOUR else DRAIN_PER_HOUR
        val target = BASE_TEMP + if (charging) CHARGING_HEAT else 0.0
        return Physics.step(state, dtSec, random, charging, rate)
            .copy(tempC = Physics.relax(state.tempC, target, dtSec, TEMP_TAU_SEC, random))
    }
}

/** Сборка: 15 минут из каждых 45 — в док-станции (+30 %/ч), остальное время разряжается ≈ 9 %/ч. */
object AssemblyProfile : DeviceProfile {
    override val role = DeviceRole.ASSEMBLY
    override val foregroundApp = "ru.restofleet.dummy.assembly"
    override val plugged = "dock"

    private const val CYCLE_MIN = 45.0
    private const val DOCKED_MIN = 15.0
    private const val MINUTES_IN_HOUR = 60.0
    private const val DRAIN_PER_HOUR = -9.0
    private const val CHARGE_PER_HOUR = 30.0
    private const val BASE_TEMP = 32.0
    private const val CHARGING_HEAT = 1.5
    private const val TEMP_TAU_SEC = 600.0

    fun docked(hour: Double): Boolean = (hour * MINUTES_IN_HOUR) % CYCLE_MIN < DOCKED_MIN

    override fun initial(random: Random) = Physics.initial(random, battery = 40.0..95.0, charging = false)

    override fun next(state: ProfileState, hour: Double, dtSec: Double, random: Random): ProfileState {
        val charging = docked(hour)
        val rate = if (charging) CHARGE_PER_HOUR else DRAIN_PER_HOUR
        val target = BASE_TEMP + if (charging) CHARGING_HEAT else 0.0
        return Physics.step(state, dtSec, random, charging, rate)
            .copy(tempC = Physics.relax(state.tempC, target, dtSec, TEMP_TAU_SEC, random))
    }
}

/** Профиль по роли. */
fun profileOf(role: DeviceRole): DeviceProfile = when (role) {
    DeviceRole.KITCHEN -> KitchenProfile
    DeviceRole.HALL -> HallProfile
    DeviceRole.ASSEMBLY -> AssemblyProfile
}

/**
 * Общая «физика» профилей: заряд с ограничением 1…100 %, медленное убывание свободной памяти, случайное блуждание ОЗУ
 * и RSSI в пределах, релаксация температуры к цели с шумом.
 */
internal object Physics {
    private const val SEC_IN_HOUR = 3_600.0
    private const val MIN_BATTERY = 1.0
    private const val MAX_BATTERY = 100.0
    private const val FREE_MB_PER_HOUR = 4.0
    private const val MIN_FREE_MB = 500L
    private const val AVAIL_STEP = 40L
    private val AVAIL_RANGE = 300L..2_000L
    private const val RSSI_STEP = 2
    private val RSSI_RANGE = -85..-40
    private const val TEMP_NOISE = 0.2
    private val INITIAL_TEMP = 29.0..32.0
    private val INITIAL_FREE = 2_000L..12_000L
    private val INITIAL_AVAIL = 600L..1_600L
    private val INITIAL_RSSI = -75..-45

    fun initial(random: Random, battery: ClosedFloatingPointRange<Double>, charging: Boolean) = ProfileState(
        batteryPct = random.nextDouble(battery.start, battery.endInclusive),
        charging = charging,
        tempC = random.nextDouble(INITIAL_TEMP.start, INITIAL_TEMP.endInclusive),
        freeMb = random.nextLong(INITIAL_FREE.first, INITIAL_FREE.last),
        availMb = random.nextLong(INITIAL_AVAIL.first, INITIAL_AVAIL.last),
        rssi = random.nextInt(INITIAL_RSSI.first, INITIAL_RSSI.last),
        heatBias = 0.0,
    )

    fun step(state: ProfileState, dtSec: Double, random: Random, charging: Boolean, ratePerHour: Double) =
        state.copy(
            batteryPct = (state.batteryPct + ratePerHour * dtSec / SEC_IN_HOUR).coerceIn(MIN_BATTERY, MAX_BATTERY),
            charging = charging,
            freeMb = (state.freeMb - (FREE_MB_PER_HOUR * dtSec / SEC_IN_HOUR).toLong()).coerceAtLeast(MIN_FREE_MB),
            availMb = (state.availMb + random.nextLong(-AVAIL_STEP, AVAIL_STEP + 1))
                .coerceIn(AVAIL_RANGE.first, AVAIL_RANGE.last),
            rssi = (state.rssi + random.nextInt(-RSSI_STEP, RSSI_STEP + 1)).coerceIn(RSSI_RANGE.first, RSSI_RANGE.last),
        )

    fun relax(current: Double, target: Double, dtSec: Double, tauSec: Double, random: Random): Double =
        target + (current - target) * exp(-dtSec / tauSec) + random.nextDouble(-TEMP_NOISE, TEMP_NOISE)
}

/** Код `THERMAL_STATUS_*` по температуре корпуса: < 37 °C — 0 (NONE) … ≥ 46 °C — 4 (CRITICAL). */
object Thermal {
    val NAMES = listOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")
    private val BOUNDS = listOf(37.0, 40.0, 43.0, 46.0)

    /** Порог `SEVERE` (реш. 264). */
    const val SEVERE = 3

    fun code(tempC: Double): Int = BOUNDS.count { tempC >= it }

    fun name(code: Int): String = NAMES[code.coerceIn(0, NAMES.lastIndex)]
}

/**
 * Местное время суток устройства. [timeScale] ускоряет сутки относительно [startMillis] (1 — реальное время, 60 —
 * минута за час): так короткий прогон показывает суточную динамику профилей.
 */
class DayClock(private val zone: ZoneId, private val timeScale: Double, private val startMillis: Long) {
    fun simulatedMillis(nowMillis: Long): Long = startMillis + ((nowMillis - startMillis) * timeScale).toLong()

    fun hour(nowMillis: Long): Double {
        val local = Instant.ofEpochMilli(simulatedMillis(nowMillis)).atZone(zone)
        return local.hour + local.minute / MINUTES + local.second / SECONDS
    }

    private companion object {
        const val MINUTES = 60.0
        const val SECONDS = 3_600.0
    }
}
