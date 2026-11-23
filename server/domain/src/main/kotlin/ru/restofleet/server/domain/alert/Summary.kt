package ru.restofleet.server.domain.alert

import ru.restofleet.server.domain.device.DeviceCounts
import ru.restofleet.server.domain.incident.Incident
import ru.restofleet.server.domain.restaurant.Restaurant
import ru.restofleet.shared.kernel.DeviceId

/** Сводка экрана «Обзор» (FR-CN-02, реш. 436). */
data class Summary(
    val devicesOnline: Int,
    val devicesTotal: Int,
    val devicesOffline: Int,
    val offlineLastHour: Int,
    val alertsOpen: Int,
    val alertsCritical: Int,
    val incidentsOpen: Int,
    val restaurantsWithProblems: Int,
    val restaurantsTotal: Int,
    val problems: List<ProblemRestaurant>,
)

/** Главная проблема ресторана: правило, сколько устройств из скольких, с какого момента (текст строит консоль). */
data class ProblemRestaurant(
    val restaurant: Restaurant,
    val rule: RuleId,
    val count: Int,
    val devicesTotal: Int,
    val target: String?,
    val deviceId: DeviceId?,
    val deviceName: String?,
    val since: Long,
    val details: Map<String, String>,
)

/**
 * Сборка сводки — чистая функция над счётчиками устройств, незакрытыми неподавленными тревогами, открытыми
 * инцидентами и ресторанами. Рестораны инцидентов идут первыми и не дублируются тревогами;
 * [counts] — точные счётчики тревог, если [alerts] — лишь первая страница.
 * Главное правило ресторана: сначала критичное, затем по числу затронутых устройств, затем самое давнее. Рестораны —
 * сначала с критичной проблемой, затем по числу устройств и давности; не больше [MAX_PROBLEMS].
 */
object SummaryBuilder {
    const val MAX_PROBLEMS = 10

    fun build(
        devices: DeviceCounts,
        alerts: List<AlertView>,
        restaurants: List<Restaurant>,
        incidents: List<Incident> = emptyList(),
        counts: AlertCounts? = null,
    ): Summary {
        val active = alerts.filter { it.alert.isActive && it.alert.parentIncidentId == null }
        val byId = restaurants.associateBy { it.id }
        val open = incidents.filter { it.isOpen }
        // Затронутые сейчас — по доказательствам последнего тика; `restaurants` — охват за жизнь инцидента (V-6)
        val fromIncidents = open.flatMap { incident ->
            incident.evidence.restaurants.map { it.restaurantId }.mapNotNull { id ->
                byId[id]?.let { problemOf(it, incident, devices.byRestaurant[id] ?: 0) }
            }
        }.distinctBy { it.restaurant.id }
        val covered = fromIncidents.map { it.restaurant.id }.toSet()
        val fromAlerts = active.groupBy { it.alert.restaurantId }.filterKeys { it !in covered }
            .mapNotNull { (restaurantId, views) ->
                byId[restaurantId]?.let { problemOf(it, views, devices.byRestaurant[restaurantId] ?: 0) }
            }
        val problems = fromIncidents + fromAlerts
        return Summary(
            devicesOnline = devices.online,
            devicesTotal = devices.total,
            devicesOffline = devices.offline,
            offlineLastHour = devices.offlineRecently,
            alertsOpen = counts?.active ?: active.size,
            alertsCritical = counts?.critical ?: active.count { it.alert.severity == Severity.CRITICAL },
            incidentsOpen = open.size,
            restaurantsWithProblems = problems.size,
            restaurantsTotal = restaurants.size,
            problems = problems.sortedWith(
                compareBy<ProblemRestaurant> { it.rule.severity != Severity.CRITICAL }
                    .thenByDescending { it.count }.thenBy { it.since }.thenBy { it.restaurant.code },
            ).take(MAX_PROBLEMS),
        )
    }

    /** Ресторан в инциденте: правило инцидента, затронуто — недоступна цель или офлайн (что больше). */
    private fun problemOf(restaurant: Restaurant, incident: Incident, devicesTotal: Int): ProblemRestaurant {
        val evidence = incident.evidence.restaurants.firstOrNull { it.restaurantId == restaurant.id }
        val affected = evidence?.let { maxOf(it.targetDown, it.devicesTotal - it.devicesOnline) } ?: 0
        return ProblemRestaurant(
            restaurant = restaurant, rule = incident.kind.rule, count = affected,
            devicesTotal = evidence?.devicesTotal ?: devicesTotal, target = incident.target, deviceId = null,
            deviceName = null, since = incident.startedAt, details = emptyMap(),
        )
    }

    private fun problemOf(restaurant: Restaurant, views: List<AlertView>, devicesTotal: Int): ProblemRestaurant {
        val byRule = views.groupBy { it.alert.rule }
        val (rule, ruleViews) = byRule.entries.minWith(
            compareBy<Map.Entry<RuleId, List<AlertView>>> { it.key.severity != Severity.CRITICAL }
                .thenByDescending { e -> e.value.map { it.alert.deviceId }.distinct().size }
                .thenBy { e -> e.value.minOf { it.alert.openedAt } }
                .thenBy { it.key },
        ).let { it.key to it.value }
        val first = ruleViews.minWith(compareBy<AlertView> { it.alert.openedAt }.thenBy { it.alert.id })
        val devicesAffected = ruleViews.map { it.alert.deviceId }.distinct()
        val single = devicesAffected.size == 1
        return ProblemRestaurant(
            restaurant = restaurant,
            rule = rule,
            count = devicesAffected.size,
            devicesTotal = devicesTotal,
            target = ruleViews.map { it.alert.subject.target }.distinct().singleOrNull(),
            deviceId = if (single) first.alert.deviceId else null,
            deviceName = if (single) first.deviceName else null,
            since = first.alert.openedAt,
            details = first.alert.details,
        )
    }
}
