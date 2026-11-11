package ru.restofleet.shared.probes

/**
 * Расписание плановых проверок одной цели (НФТ «нагрузка проверок»: не чаще 1 запроса к цели в 30 с с устройства;
 * реш. 228). Интервал из тени ниже [minIntervalSec] поднимается до него. [minIntervalSec] по умолчанию 30 с; для стенда
 * допускается 5 с ([forStand]) — ниже нельзя. Внеочередные проверки (`run_probe`, `collect_diagnostics`) расписанием
 * не ограничиваются: их запускает оператор.
 */
class ProbeSchedule(val minIntervalSec: Int = DEFAULT_MIN_INTERVAL_SEC) {
    init {
        require(minIntervalSec >= STAND_MIN_INTERVAL_SEC) { "minIntervalSec must be >= $STAND_MIN_INTERVAL_SEC" }
    }

    /** Фактический интервал для запрошенного тенью [requestedSec]. */
    fun intervalSec(requestedSec: Int): Int = maxOf(requestedSec, minIntervalSec)

    fun intervalMs(requestedSec: Int): Long = intervalSec(requestedSec) * MS_IN_SECOND

    /**
     * Момент следующего запуска (epoch, мс), не раньше [now]: первая проверка — сразу, дальше — через интервал после
     * [lastRunAt]. Если часы ушли назад ([lastRunAt] в будущем), отсчёт идёт от [now].
     */
    fun nextRunAt(lastRunAt: Long?, requestedSec: Int, now: Long): Long {
        if (lastRunAt == null) return now
        return maxOf(minOf(lastRunAt, now) + intervalMs(requestedSec), now)
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_SEC = 30
        const val STAND_MIN_INTERVAL_SEC = 5
        private const val MS_IN_SECOND = 1_000L

        /** Стенд и эксперимент: минимальный интервал 5 с. */
        fun forStand(): ProbeSchedule = ProbeSchedule(STAND_MIN_INTERVAL_SEC)
    }
}
