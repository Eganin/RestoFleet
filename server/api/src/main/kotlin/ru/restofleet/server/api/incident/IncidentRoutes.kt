package ru.restofleet.server.api.incident

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import ru.restofleet.server.api.IncidentUseCases
import ru.restofleet.server.api.alert.toRef
import ru.restofleet.server.api.alert.userLogin
import ru.restofleet.server.api.error.ApiException
import ru.restofleet.server.api.error.orThrow
import ru.restofleet.server.api.http.page
import ru.restofleet.server.api.http.pathParam
import ru.restofleet.server.api.http.queryParam
import ru.restofleet.server.api.http.timeRange
import ru.restofleet.server.domain.incident.IncidentFilter
import ru.restofleet.server.domain.incident.IncidentView
import ru.restofleet.shared.protocol.rest.console.IncidentActionDto
import ru.restofleet.shared.protocol.rest.console.IncidentDetailsDto
import ru.restofleet.shared.protocol.rest.console.IncidentDto
import ru.restofleet.shared.protocol.rest.console.IncidentEvidenceDto
import ru.restofleet.shared.protocol.rest.console.IncidentKinds
import ru.restofleet.shared.protocol.rest.console.IncidentThresholdsDto
import ru.restofleet.shared.protocol.rest.console.ItemsDto
import ru.restofleet.shared.protocol.rest.console.RestaurantEvidenceDto

/** Сводные инциденты (FR-CN-10; реш. 412, 441): список, карточка с доказательствами, подтверждение. */
fun Route.incidentRoutes(useCases: IncidentUseCases) {
    route("/incidents") {
        get {
            call.respond(ItemsDto(useCases.list(call.incidentFilter(), call.page()).map { it.toDto() }))
        }
        get("/{id}") {
            call.respond(useCases.get(call.pathParam("id")).orThrow().toDetailsDto())
        }
        patch("/{id}") {
            val action = call.receive<IncidentActionDto>()
            if (action.action != ACKNOWLEDGE) throw ApiException.validation("action", "unknown")
            call.respond(useCases.acknowledge(call.pathParam("id"), call.userLogin(), action.comment).orThrow().toDto())
        }
    }
}

private const val ACKNOWLEDGE = "acknowledge"

/** `?state=open|resolved&from=&to=`: неизвестное состояние — 400. */
private fun ApplicationCall.incidentFilter() = IncidentFilter(
    open = when (queryParam("state")) {
        null -> null
        IncidentKinds.STATE_OPEN -> true
        IncidentKinds.STATE_RESOLVED -> false
        else -> throw ApiException.validation("state", "unknown")
    },
    range = timeRange(),
)

internal fun IncidentView.toDto() = IncidentDto(
    id = incident.id,
    kind = incident.kind.code,
    ruleId = incident.kind.rule.code,
    state = if (incident.isOpen) IncidentKinds.STATE_OPEN else IncidentKinds.STATE_RESOLVED,
    target = incident.target,
    restaurants = restaurants.map { it.toRef() },
    startedAt = incident.startedAt,
    resolvedAt = incident.resolvedAt,
    resolveReason = incident.resolveReason?.code,
    suppressedAlerts = suppressedAlerts,
    ackBy = incident.ackBy,
    ackAt = incident.ackAt,
    comment = incident.comment,
)

/** Доказательства с кодом и названием ресторанов; рестораны, которых больше нет, пропускаются. */
internal fun IncidentView.toDetailsDto(): IncidentDetailsDto {
    val byId = restaurants.associateBy { it.id }
    val evidence = incident.evidence
    return IncidentDetailsDto(
        incident = toDto(),
        evidence = IncidentEvidenceDto(
            evaluatedAt = evidence.evaluatedAt,
            restaurants = evidence.restaurants.mapNotNull { e ->
                byId[e.restaurantId]?.let { r ->
                    RestaurantEvidenceDto(
                        id = r.id.value, code = r.code, name = r.name, devicesTotal = e.devicesTotal,
                        devicesOnline = e.devicesOnline, targetOnline = e.targetOnline, targetDown = e.targetDown,
                        refOnline = e.refOnline, refUp = e.refUp, errorClasses = e.errorClasses,
                    )
                }
            },
            otherRestaurantsOk = evidence.otherRestaurantsOk,
            thresholds = IncidentThresholdsDto(evidence.thresholds.p, evidence.thresholds.m, evidence.thresholds.n),
        ),
    )
}
