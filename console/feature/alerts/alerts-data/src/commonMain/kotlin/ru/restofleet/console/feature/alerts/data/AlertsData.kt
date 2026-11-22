package ru.restofleet.console.feature.alerts.data

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
import ru.restofleet.console.feature.alerts.domain.AlertItem
import ru.restofleet.console.feature.alerts.domain.AlertLists
import ru.restofleet.console.feature.alerts.domain.AlertState
import ru.restofleet.console.feature.alerts.domain.AlertsEvent
import ru.restofleet.console.feature.alerts.domain.AlertsRepository
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.kernel.map
import ru.restofleet.shared.protocol.rest.console.AlertActionDto
import ru.restofleet.shared.protocol.rest.console.AlertActions
import ru.restofleet.shared.protocol.rest.console.AlertDto
import ru.restofleet.shared.protocol.rest.console.ConsolePaths
import ru.restofleet.shared.protocol.rest.console.ItemsDto
import ru.restofleet.shared.protocol.rest.console.Severities
import ru.restofleet.shared.protocol.rest.console.ws.WsServerMessage

class AlertsApi(private val client: HttpClient) {
    suspend fun list(state: String, limit: Int): Outcome<ItemsDto<AlertDto>, ConsoleError> = apiCall {
        client.get(ConsolePaths.ALERTS) {
            parameter("state", state)
            parameter("limit", limit)
        }
    }

    suspend fun act(id: String, action: AlertActionDto): Outcome<AlertDto, ConsoleError> =
        apiCall { client.patch(ConsolePaths.alert(id)) { setBody(action) } }
}

class AlertsRepositoryImpl(
    private val api: AlertsApi,
    private val realtime: RealtimeClient,
    private val clock: Clock,
) : AlertsRepository {
    override suspend fun lists(limit: Int): Outcome<AlertLists, ConsoleError> = coroutineScope {
        val open = async { api.list(AlertState.Open.wire, limit) }
        val acked = async { api.list(AlertState.Acknowledged.wire, limit) }
        val resolved = async { api.list(AlertState.Resolved.wire, limit) }
        val results = listOf(open.await(), acked.await(), resolved.await())
        val failure = results.firstOrNull { it is Outcome.Failure } as? Outcome.Failure
        if (failure != null) {
            failure
        } else {
            val (o, a, r) = results.map { (it as Outcome.Success).value.items.map(AlertDto::toDomain) }
            Outcome.Success(AlertLists(o, a, r, clock.nowMillis()))
        }
    }

    override suspend fun acknowledge(id: String): Outcome<AlertItem, ConsoleError> =
        api.act(id, AlertActionDto(AlertActions.ACKNOWLEDGE)).map { it.toDomain() }

    override suspend fun resolve(id: String, comment: String): Outcome<AlertItem, ConsoleError> =
        api.act(id, AlertActionDto(AlertActions.RESOLVE, comment)).map { it.toDomain() }

    override fun events(): Flow<AlertsEvent> = realtime.events.mapNotNull { event ->
        when (event) {
            RealtimeEvent.Connected -> AlertsEvent.ReloadNeeded
            RealtimeEvent.SessionLost -> null
            is RealtimeEvent.Message -> when (val m = event.message) {
                is WsServerMessage.Alert -> AlertsEvent.Pushed(m.data.toDomain())
                is WsServerMessage.Resync -> AlertsEvent.ReloadNeeded
                else -> null
            }
        }
    }
}

internal fun AlertDto.toDomain() = AlertItem(
    id = id,
    ruleId = ruleId,
    critical = severity == Severities.CRITICAL,
    state = AlertState.fromWire(state),
    deviceId = deviceId,
    deviceName = deviceName,
    restaurantName = restaurantName,
    target = target,
    openedAt = openedAt,
    ackBy = ackBy,
    resolvedAt = resolvedAt,
    comment = comment,
    auto = auto,
    details = details,
)
