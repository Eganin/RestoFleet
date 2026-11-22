package ru.restofleet.server.domain.incident

/** Итог сверки: новые, изменённые (в том числе закрытые) инциденты. */
data class Reconciliation(val opened: List<Incident>, val changed: List<Incident>) {
    val all: List<Incident> get() = opened + changed
}

/**
 * Сверка открытых инцидентов с классификацией тика (реш. 412) — чистая функция:
 * - совпавший (тот же тип и рестораны; у глобального — любой состав) обновляет рестораны и доказательства, счётчик
 *   промахов обнуляется;
 * - новый — открывается;
 * - открытый без условия закрывается `cleared` на [resolveAfterTicks]-м тике подряд;
 * - «локально ↔ глобально»: прежние закрываются `superseded` сразу, тем же тиком открывается новый (дочерние тревоги
 *   переносит пересчёт подавления).
 */
object IncidentReconciler {
    @Suppress("LongParameterList")
    fun reconcile(
        open: List<Incident>,
        classification: Classification,
        stats: List<RestaurantStats>,
        now: Long,
        newId: () -> String,
        params: ClassifierParams = ClassifierParams(),
        resolveAfterTicks: Int = DEFAULT_RESOLVE_AFTER_TICKS,
    ): Reconciliation {
        val opened = mutableListOf<Incident>()
        val changed = mutableListOf<Incident>()
        val matched = mutableSetOf<String>()
        val wantGlobal = classification.incidents.any { it.kind == IncidentKind.BACKEND_GLOBAL }
        val wantLocal = classification.incidents.any { it.kind == IncidentKind.BACKEND_LOCAL }
        classification.incidents.forEach { wanted ->
            val evidence = evidence(wanted, stats, classification.thresholds, now, params)
            val existing = open.firstOrNull { it.id !in matched && matches(it, wanted) }
            if (existing != null) {
                matched += existing.id
                changed += existing.copy(restaurants = wanted.restaurants, evidence = evidence, missedTicks = 0)
            } else {
                opened += Incident(newId(), wanted.kind, wanted.target, wanted.restaurants, now, evidence)
            }
        }
        open.filter { it.id !in matched }.forEach { stale ->
            val superseded = (stale.kind == IncidentKind.BACKEND_GLOBAL && wantLocal) ||
                (stale.kind == IncidentKind.BACKEND_LOCAL && wantGlobal)
            val missed = stale.missedTicks + 1
            changed += when {
                superseded ->
                    stale.copy(resolvedAt = now, resolveReason = ResolveReason.SUPERSEDED, missedTicks = missed)
                missed >= resolveAfterTicks ->
                    stale.copy(resolvedAt = now, resolveReason = ResolveReason.CLEARED, missedTicks = missed)
                else -> stale.copy(missedTicks = missed)
            }
        }
        return Reconciliation(opened, changed)
    }

    private fun matches(incident: Incident, wanted: Classified): Boolean = incident.kind == wanted.kind &&
        (wanted.kind == IncidentKind.BACKEND_GLOBAL || incident.restaurants.toSet() == wanted.restaurants.toSet())

    /** Доказательства по цели инцидента: затронутые рестораны и сколько остальных с данными в норме. */
    fun evidence(
        wanted: Classified,
        stats: List<RestaurantStats>,
        thresholds: IncidentThresholds,
        now: Long,
        params: ClassifierParams,
    ): IncidentEvidence {
        val affected = wanted.restaurants.toSet()
        val restaurants = stats.filter { it.restaurantId in affected }.map { it.toEvidence(wanted.target, params) }
        val othersOk = stats.count { s ->
            val t = s.target(wanted.target)
            s.restaurantId !in affected && t.online > 0 && t.down * PERCENT < params.backendDownPct * t.online
        }
        return IncidentEvidence(now, restaurants, othersOk, thresholds)
    }

    private fun RestaurantStats.toEvidence(target: String, params: ClassifierParams): RestaurantEvidence {
        val t = target(target)
        val ref = target(params.refTarget)
        return RestaurantEvidence(
            restaurantId, devicesTotal, devicesOnline, t.online, t.down, ref.online, ref.up, t.errorClasses,
        )
    }

    /** Закрытие после двух тиков подряд без условия (реш. 412). */
    const val DEFAULT_RESOLVE_AFTER_TICKS = 2
}
