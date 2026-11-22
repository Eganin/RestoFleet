package ru.restofleet.server.domain.incident.usecase

import ru.restofleet.server.domain.alert.usecase.ALERT_COMMENT_MAX
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.incident.IncidentRepository
import ru.restofleet.server.domain.incident.IncidentView
import ru.restofleet.server.domain.incident.views
import ru.restofleet.server.domain.realtime.EventBus
import ru.restofleet.server.domain.realtime.RealtimeEvent
import ru.restofleet.server.domain.restaurant.RestaurantRepository
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome

/**
 * `PATCH /incidents/{id}` `acknowledge` (реш. 412): только открытый и ещё не подтверждённый; комментарий необязателен
 * (≤ 500). Закрывается инцидент только автоматически.
 */
class AcknowledgeIncident(
    private val incidents: IncidentRepository,
    private val restaurants: RestaurantRepository,
    private val bus: EventBus,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: String, by: String, comment: String?): Outcome<IncidentView, ServerError> {
        val text = comment?.trim()?.takeIf { it.isNotEmpty() }
        if (text != null && text.length > ALERT_COMMENT_MAX) {
            return Outcome.Failure(ServerError.Validation("comment", "too_long"))
        }
        val result = tx.transactional<IncidentView, ServerError> {
            val incident = incidents.findForUpdate(id)
            when {
                incident == null -> Outcome.Failure(ServerError.NotFound("incident", id))
                !incident.isOpen -> Outcome.Failure(ServerError.Conflict("already_resolved"))
                incident.ackBy != null -> Outcome.Failure(ServerError.Conflict("already_acknowledged"))
                else -> {
                    val acked = incident.copy(ackBy = by, ackAt = clock.nowMillis(), comment = text)
                    incidents.update(acked)
                    val counts = incidents.suppressedCounts(listOf(id))
                    Outcome.Success(views(listOf(acked), restaurants.list(), counts).single())
                }
            }
        }
        if (result is Outcome.Success) bus.publish(RealtimeEvent.IncidentChanged(result.value))
        return result
    }
}
