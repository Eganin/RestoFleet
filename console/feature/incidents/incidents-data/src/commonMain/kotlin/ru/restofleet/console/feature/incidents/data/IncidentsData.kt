package ru.restofleet.console.feature.incidents.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.network.apiCall
import ru.restofleet.console.core.network.realtime.RealtimeClient
import ru.restofleet.console.core.network.realtime.RealtimeEvent
import ru.restofleet.console.feature.incidents.domain.Incident
import ru.restofleet.console.feature.incidents.domain.IncidentDetails
import ru.restofleet.console.feature.incidents.domain.IncidentEvidence
import ru.restofleet.console.feature.incidents.domain.IncidentKind
import ru.restofleet.console.feature.incidents.domain.IncidentLists
import ru.restofleet.console.feature.incidents.domain.IncidentsEvent
import ru.restofleet.console.feature.incidents.domain.IncidentsRepository
import ru.restofleet.console.feature.incidents.domain.RestaurantEvidence
import ru.restofleet.console.feature.incidents.domain.RestaurantRef
import ru.restofleet.console.feature.incidents.domain.Thresholds
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.kernel.map
import ru.restofleet.shared.protocol.rest.console.ConsolePaths
import ru.restofleet.shared.protocol.rest.console.IncidentActionDto
import ru.restofleet.shared.protocol.rest.console.IncidentDetailsDto
import ru.restofleet.shared.protocol.rest.console.IncidentDto
import ru.restofleet.shared.protocol.rest.console.IncidentKinds
import ru.restofleet.shared.protocol.rest.console.ItemsDto
import ru.restofleet.shared.protocol.rest.console.ws.WsServerMessage

class IncidentsApi(private val client: HttpClient) {
    suspend fun list(state: String, from: Long?): Outcome<ItemsDto<IncidentDto>, ConsoleError> = apiCall {
        client.get(ConsolePaths.INCIDENTS) {
            parameter("state", state)
            from?.let { parameter("from", it) }
        }
    }

    suspend fun details(id: String): Outcome<IncidentDetailsDto, ConsoleError> =
        apiCall { client.get(ConsolePaths.incident(id)) }

    suspend fun acknowledge(id: String, comment: String?): Outcome<IncidentDto, ConsoleError> =
        apiCall { client.patch(ConsolePaths.incident(id)) { setBody(IncidentActionDto("acknowledge", comment)) } }
}

class IncidentsRepositoryImpl(
    private val api: IncidentsApi,
    private val realtime: RealtimeClient,
    private val clock: Clock,
) : IncidentsRepository {
    override suspend fun lists(): Outcome<IncidentLists, ConsoleError> = coroutineScope {
        val now = clock.nowMillis()
        val open = async { api.list(IncidentKinds.STATE_OPEN, null) }
        val resolved = async { api.list(IncidentKinds.STATE_RESOLVED, now - DAY_MS) }
        val o = open.await()
        val r = resolved.await()
        when {
            o is Outcome.Failure -> o
            r is Outcome.Failure -> r
            else -> Outcome.Success(
                IncidentLists(
                    (o as Outcome.Success).value.items.map { it.toDomain() },
                    (r as Outcome.Success).value.items.map { it.toDomain() },
                    now,
                ),
            )
        }
    }

    override suspend fun details(id: String): Outcome<IncidentDetails, ConsoleError> = api.details(id).map { it.toDomain() }

    override suspend fun acknowledge(id: String, comment: String?): Outcome<Incident, ConsoleError> =
        api.acknowledge(id, comment).map { it.toDomain() }

    override fun events(): Flow<IncidentsEvent> = realtime.events.mapNotNull { event ->
        when (event) {
            RealtimeEvent.Connected -> IncidentsEvent.ReloadNeeded
            RealtimeEvent.SessionLost -> null
            is RealtimeEvent.Message -> when (val m = event.message) {
                is WsServerMessage.Incident -> IncidentsEvent.Pushed(m.data.toDomain())
                is WsServerMessage.Resync -> IncidentsEvent.ReloadNeeded
                else -> null
            }
        }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}

internal fun IncidentDto.toDomain() = Incident(
    id = id,
    kind = IncidentKind.fromWire(kind),
    ruleId = ruleId,
    resolved = state == IncidentKinds.STATE_RESOLVED,
    target = target,
    restaurants = restaurants.map { RestaurantRef(it.id, it.code, it.name) },
    startedAt = startedAt,
    resolvedAt = resolvedAt,
    resolveReason = resolveReason,
    suppressedAlerts = suppressedAlerts,
    ackBy = ackBy,
    comment = comment,
)

internal fun IncidentDetailsDto.toDomain() = IncidentDetails(
    incident = incident.toDomain(),
    evidence = IncidentEvidence(
        evaluatedAt = evidence.evaluatedAt,
        restaurants = evidence.restaurants.map {
            RestaurantEvidence(
                it.name, it.devicesTotal, it.devicesOnline, it.targetOnline, it.targetDown, it.refOnline, it.refUp,
                it.errorClasses,
            )
        },
        otherRestaurantsOk = evidence.otherRestaurantsOk,
        thresholds = Thresholds(evidence.thresholds.p, evidence.thresholds.m, evidence.thresholds.n),
    ),
)
