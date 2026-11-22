package ru.restofleet.server.domain.alert.usecase

import ru.restofleet.server.domain.alert.AlertFilter
import ru.restofleet.server.domain.alert.AlertRepository
import ru.restofleet.server.domain.alert.Summary
import ru.restofleet.server.domain.alert.SummaryBuilder
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.device.FleetQueries
import ru.restofleet.server.domain.incident.IncidentRepository
import ru.restofleet.server.domain.restaurant.RestaurantRepository
import ru.restofleet.shared.kernel.Clock

/**
 * `GET /summary` и WS `summary_changed` (FR-CN-02): счётчики устройств и тревог — точные; рестораны с проблемами —
 * по первым [MAX_ALERTS] незакрытым тревогам (защита от «шторма»: ответ остаётся быстрым).
 */
class GetSummary(
    private val fleet: FleetQueries,
    private val alerts: AlertRepository,
    private val restaurants: RestaurantRepository,
    private val incidents: IncidentRepository,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Summary = tx.inTransaction {
        val deviceCounts = fleet.counts(offlineSince = clock.nowMillis() - HOUR_MS)
        val active = alerts.list(AlertFilter(activeOnly = true), PageRequest.of(limit = MAX_ALERTS))
        SummaryBuilder.build(deviceCounts, active, restaurants.list(), incidents.open(), alerts.activeCounts())
    }

    private companion object {
        const val HOUR_MS = 60L * 60 * 1000
        const val MAX_ALERTS = PageRequest.MAX_LIMIT
    }
}
