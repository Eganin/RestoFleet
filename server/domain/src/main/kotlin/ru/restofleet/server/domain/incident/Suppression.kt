package ru.restofleet.server.domain.incident

import ru.restofleet.server.domain.alert.Alert
import ru.restofleet.server.domain.alert.RuleId

/**
 * Подавление дочерних тревог (FR-BE-37, реш. 416) — чистая функция: какой открытый инцидент покрывает незакрытую
 * тревогу. R-08 и R-09 — R-05 по основной цели устройств затронутых ресторанов; R-07 — R-01 и R-05 (любая цель)
 * устройств ресторана. Одиночная R-01 вне инцидента не подавляется. Пересчёт целиком на каждом тике переносит тревоги
 * при смене «локально ↔ глобально» и снимает подавление, когда инцидент закрыт.
 */
object Suppression {
    /** Тревоги, у которых родитель должен смениться: `id тревоги → новый родитель` (`null` — снять подавление). */
    fun changes(
        open: List<Incident>,
        alerts: List<Alert>,
        params: ClassifierParams = ClassifierParams(),
    ): Map<String, String?> =
        alerts.filter { it.isActive }.mapNotNull { alert ->
            val parent = open.firstOrNull { covers(it, alert, params) }?.id
            if (parent == alert.parentIncidentId) null else alert.id to parent
        }.toMap()

    private fun covers(incident: Incident, alert: Alert, params: ClassifierParams): Boolean {
        if (!incident.isOpen || alert.restaurantId !in incident.restaurants) return false
        return when (incident.kind) {
            IncidentKind.RESTAURANT_NETWORK -> alert.rule == RuleId.R01 || alert.rule == RuleId.R05
            IncidentKind.BACKEND_GLOBAL, IncidentKind.BACKEND_LOCAL ->
                alert.rule == RuleId.R05 && alert.subject.target == params.primaryTarget
        }
    }
}
