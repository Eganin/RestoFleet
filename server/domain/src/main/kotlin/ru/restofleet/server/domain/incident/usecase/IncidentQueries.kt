package ru.restofleet.server.domain.incident.usecase

import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.incident.IncidentFilter
import ru.restofleet.server.domain.incident.IncidentRepository
import ru.restofleet.server.domain.incident.IncidentView
import ru.restofleet.server.domain.incident.views
import ru.restofleet.server.domain.restaurant.RestaurantRepository
import ru.restofleet.shared.kernel.Outcome

/** `GET /incidents` (FR-CN-10): новые сначала; «закрытые сегодня» — `open = false` и интервал за сутки. */
class ListIncidents(
    private val incidents: IncidentRepository,
    private val restaurants: RestaurantRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(filter: IncidentFilter, page: PageRequest = PageRequest.of()): List<IncidentView> =
        tx.inTransaction {
            val found = incidents.list(filter, page)
            views(found, restaurants.list(), incidents.suppressedCounts(found.map { it.id }))
        }
}

/** `GET /incidents/{id}`: инцидент с доказательствами (текст объяснения строит консоль). */
class GetIncident(
    private val incidents: IncidentRepository,
    private val restaurants: RestaurantRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(id: String): Outcome<IncidentView, ServerError> = tx.inTransaction {
        incidents.find(id)?.let {
            views(listOf(it), restaurants.list(), incidents.suppressedCounts(listOf(id))).single()
        }
    }?.let { Outcome.Success(it) } ?: Outcome.Failure(ServerError.NotFound("incident", id))
}
