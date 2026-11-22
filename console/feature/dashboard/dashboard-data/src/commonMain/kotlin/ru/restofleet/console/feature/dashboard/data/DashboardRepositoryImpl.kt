package ru.restofleet.console.feature.dashboard.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.network.realtime.RealtimeClient
import ru.restofleet.console.core.network.realtime.RealtimeEvent
import ru.restofleet.console.feature.dashboard.domain.Dashboard
import ru.restofleet.console.feature.dashboard.domain.DashboardEvent
import ru.restofleet.console.feature.dashboard.domain.DashboardRepository
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.rest.console.ws.WsServerMessage

class DashboardRepositoryImpl(
    private val api: DashboardApi,
    private val realtime: RealtimeClient,
    private val clock: Clock,
) : DashboardRepository {
    override suspend fun load(): Outcome<Dashboard, ConsoleError> = when (val summary = api.summary()) {
        is Outcome.Failure -> summary
        is Outcome.Success -> {
            // Блок «Последние события» вторичен: его сбой не прячет сводку
            val recent = (api.recentAlerts(RECENT_LIMIT) as? Outcome.Success)?.value?.items.orEmpty()
            Outcome.Success(Dashboard(summary.value.toDomain(), recent.map { it.toRecent() }, clock.nowMillis()))
        }
    }

    override fun events(): Flow<DashboardEvent> = realtime.events.mapNotNull { event ->
        when (event) {
            RealtimeEvent.Connected -> DashboardEvent.ReloadNeeded
            RealtimeEvent.SessionLost -> null
            is RealtimeEvent.Message -> when (val m = event.message) {
                is WsServerMessage.SummaryChanged -> DashboardEvent.SummaryPushed(m.data.toDomain())
                is WsServerMessage.DeviceStatus, is WsServerMessage.Alert, is WsServerMessage.Resync ->
                    DashboardEvent.ReloadNeeded
                else -> null
            }
        }
    }

    private companion object {
        const val RECENT_LIMIT = 5
    }
}
