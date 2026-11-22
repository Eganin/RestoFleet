package ru.restofleet.shared.shadow

/** Допустимые значения `desired` (ТЗ 7.2, FR-AG-53). */
object DesiredLimits {
    val TELEMETRY_INTERVAL_SEC: IntRange = 10..3_600

    /** Интервал, если неверное значение пришло, а прежней применённой тени нет (реш. MVP 177). */
    const val DEFAULT_TELEMETRY_INTERVAL_SEC = 30
}

/** Коды `reported.errors` (ТЗ 7.2; реш. MVP 134, 161, 177). */
object DesiredErrors {
    const val INTERVAL_OUT_OF_RANGE = "telemetryIntervalSec:out_of_range"
    const val PROBE_EMPTY_ID = "probes:empty_id"
    const val WATCHED_APP_EMPTY = "watchedApps:empty"

    fun probeEmptyUrl(id: String): String = "probes:empty_url:$id"

    fun probeDuplicateId(id: String): String = "probes:duplicate_id:$id"

    fun watchedAppInvalidName(name: String): String = "watchedApps:invalid_name:$name"
}

/**
 * Итог проверки `desired`: что можно применить и почему остальное отброшено. [telemetryIntervalSec] = `null` —
 * значение вне диапазона, применять прежний интервал (без прежней тени —
 * [DesiredLimits.DEFAULT_TELEMETRY_INTERVAL_SEC]).
 */
data class DesiredValidation<P>(
    val telemetryIntervalSec: Int?,
    val watchedApps: List<String>,
    val probes: List<P>,
    val errors: List<String>,
) {
    val isValid: Boolean get() = errors.isEmpty()
}

/**
 * Валидация параметров `desired` (общая для агента и бэкенда, реш. 225). Цели проверок [probes] — любого типа: поля
 * берутся через [probeId] и [probeUrl]. Пакеты: пустые и с неверным именем отбрасываются, повторы схлопываются. Цели:
 * пустой `id`, пустой `url`, повторный `id` отбрасываются (остаётся первая). Порядок ошибок: интервал, пакеты, цели.
 */
fun <P> validateDesired(
    telemetryIntervalSec: Int,
    watchedApps: List<String>,
    probes: List<P>,
    probeId: (P) -> String,
    probeUrl: (P) -> String,
): DesiredValidation<P> {
    val errors = mutableListOf<String>()
    val interval = telemetryIntervalSec.takeIf { it in DesiredLimits.TELEMETRY_INTERVAL_SEC }
    if (interval == null) errors += DesiredErrors.INTERVAL_OUT_OF_RANGE
    val apps = watchedApps.filter { name ->
        when {
            name.isBlank() -> false.also { errors += DesiredErrors.WATCHED_APP_EMPTY }
            !isValidPackageName(name) -> false.also { errors += DesiredErrors.watchedAppInvalidName(name) }
            else -> true
        }
    }.distinct()
    val seen = mutableSetOf<String>()
    val targets = probes.filter { probe ->
        val id = probeId(probe)
        when {
            id.isBlank() -> false.also { errors += DesiredErrors.PROBE_EMPTY_ID }
            probeUrl(probe).isBlank() -> false.also { errors += DesiredErrors.probeEmptyUrl(id) }
            !seen.add(id) -> false.also { errors += DesiredErrors.probeDuplicateId(id) }
            else -> true
        }
    }
    return DesiredValidation(interval, apps, targets, errors)
}
