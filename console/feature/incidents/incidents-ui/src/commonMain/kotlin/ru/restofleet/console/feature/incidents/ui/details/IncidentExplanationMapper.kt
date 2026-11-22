package ru.restofleet.console.feature.incidents.ui.details

import ru.restofleet.console.feature.incidents.domain.IncidentDetails
import ru.restofleet.console.feature.incidents.domain.IncidentKind
import ru.restofleet.console.feature.incidents.domain.RestaurantEvidence

/**
 * «Почему так решили» (FR-CN-10): текст по доказательствам инцидента, сервер его не присылает (реш. 435, 436).
 * Чистая функция; на каждый вид инцидента — своё объяснение, табличный тест проверяет все.
 */
class IncidentExplanationMapper {
    fun explain(d: IncidentDetails): List<String> {
        val i = d.incident
        val e = d.evidence
        return when (i.kind) {
            IncidentKind.RestaurantNetwork -> network(e.restaurants)
            IncidentKind.BackendGlobal -> global(i.target, d)
            IncidentKind.BackendLocal -> local(i.target, d)
            IncidentKind.Unknown -> listOf("Причина не классифицирована: тип «${i.ruleId}» этой версии консоли неизвестен.")
        }
    }

    /** Классы ошибок проверок: «timeout ×4, dns ×1» (по убыванию), пусто, если ошибок нет. */
    fun errors(d: IncidentDetails): String? {
        val total = d.evidence.restaurants.flatMap { it.errorClasses.entries }.groupBy({ it.key }, { it.value })
            .mapValues { it.value.sum() }
        return total.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ×${it.value}" }.ifEmpty { null }
    }

    private fun network(restaurants: List<RestaurantEvidence>): List<String> =
        restaurants.map { r ->
            "${r.name}: на связи ${r.devicesOnline} из ${r.devicesTotal} устройств; эталон (ref) недоступен " +
                "у ${r.refOnline - r.refUp} из ${r.refOnline}."
        } + "Пропала сеть ресторана: эталонный адрес не отвечает, значит бэкенд ни при чём."

    private fun global(target: String, d: IncidentDetails): List<String> {
        val t = d.evidence.thresholds
        return listOf(
            "Цель $target недоступна у ≥ ${t.p} % онлайн-устройств при живом эталоне в ${d.evidence.restaurants.size} " +
                "ресторанах (для общего отказа нужно не меньше ${t.m} из ${t.n}).",
            "Остальных ресторанов без проблем: ${d.evidence.otherRestaurantsOk}.",
        ) + d.evidence.restaurants.map(::counters) +
            "Одна причина вместо россыпи тревог: отказ на стороне бэкенда, скрыто тревог — ${d.incident.suppressedAlerts}."
    }

    private fun local(target: String, d: IncidentDetails): List<String> {
        val t = d.evidence.thresholds
        return listOf(
            "Цель $target недоступна при живом эталоне в ресторанах: ${d.evidence.restaurants.size}; " +
                "для общего отказа нужно не меньше ${t.m}, поэтому инцидент локальный.",
        ) + d.evidence.restaurants.map(::counters)
    }

    private fun counters(r: RestaurantEvidence): String {
        val errors = r.errorClasses.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ×${it.value}" }
        return "${r.name}: цель не отвечает на ${r.targetDown} из ${r.targetOnline} устройств" +
            if (errors.isEmpty()) "." else " ($errors)."
    }
}
