package ru.restofleet.server.domain.incident

import ru.restofleet.server.domain.restaurant.Restaurant

/** Настройки тика инцидентов (реш. 409–412): конфиг `incidents.*`. */
data class IncidentSettings(
    val classifier: ClassifierParams = ClassifierParams(),
    /** Окно свежести результатов проб (спецификация 4.1: 2 мин). */
    val windowMillis: Long = DEFAULT_WINDOW_MS,
    /** Устройства, молчащие дольше, не учитываются (реш. 410). */
    val silentAfterMillis: Long = DEFAULT_SILENT_AFTER_MS,
    val resolveAfterTicks: Int = IncidentReconciler.DEFAULT_RESOLVE_AFTER_TICKS,
    /** Период тика (спецификация 4.1: 30 с); задача `serve` берёт его отсюда. */
    val tickMillis: Long = DEFAULT_TICK_MS,
) {
    companion object {
        const val DEFAULT_WINDOW_MS = 120_000L
        const val DEFAULT_SILENT_AFTER_MS = 86_400_000L
        const val DEFAULT_TICK_MS = 30_000L
    }
}

/** Вид для выдачи: рестораны по порядку инцидента и число подавленных тревог. */
internal fun views(
    incidents: List<Incident>,
    restaurants: List<Restaurant>,
    suppressed: Map<String, Int>,
): List<IncidentView> {
    val byId = restaurants.associateBy { it.id }
    return incidents.map { incident ->
        IncidentView(incident, incident.restaurants.mapNotNull { byId[it] }, suppressed[incident.id] ?: 0)
    }
}
