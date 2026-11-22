package ru.restofleet.console.feature.devices.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.network.realtime.RealtimeClient
import ru.restofleet.console.core.network.realtime.RealtimeEvent
import ru.restofleet.console.feature.devices.domain.DeviceAlert
import ru.restofleet.console.feature.devices.domain.DeviceFilter
import ru.restofleet.console.feature.devices.domain.DeviceItem
import ru.restofleet.console.feature.devices.domain.DevicesEvent
import ru.restofleet.console.feature.devices.domain.DevicesPage
import ru.restofleet.console.feature.devices.domain.DevicesRepository
import ru.restofleet.console.feature.devices.domain.RestaurantOption
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.kernel.map
import ru.restofleet.shared.protocol.rest.console.AlertDto
import ru.restofleet.shared.protocol.rest.console.DeviceDto
import ru.restofleet.shared.protocol.rest.console.Severities
import ru.restofleet.shared.protocol.rest.console.ws.WsServerMessage

class DevicesRepositoryImpl(
    private val api: DevicesApi,
    private val realtime: RealtimeClient,
    private val clock: Clock,
) : DevicesRepository {
    override suspend fun page(filter: DeviceFilter, offset: Int, limit: Int): Outcome<DevicesPage, ConsoleError> =
        api.devices(filter, offset, limit).map { dto ->
            DevicesPage(dto.items.map { it.toDomain() }, offset, hasMore = dto.items.size >= limit, loadedAt = clock.nowMillis())
        }

    override suspend fun restaurants(): Outcome<List<RestaurantOption>, ConsoleError> =
        api.restaurants().map { dto -> dto.items.map { RestaurantOption(it.id, "${it.name} · ${it.code}") } }

    override fun events(): Flow<DevicesEvent> = realtime.events.mapNotNull { event ->
        when (event) {
            RealtimeEvent.Connected -> DevicesEvent.ReloadNeeded
            RealtimeEvent.SessionLost -> null
            is RealtimeEvent.Message -> when (val m = event.message) {
                is WsServerMessage.DeviceStatus ->
                    DevicesEvent.StatusChanged(m.data.deviceId, m.data.status, m.data.lastSeenAt)
                is WsServerMessage.Alert, is WsServerMessage.Resync -> DevicesEvent.ReloadNeeded
                else -> null
            }
        }
    }
}

internal fun DeviceDto.toDomain() = DeviceItem(
    id = id,
    name = name,
    role = role,
    status = status,
    restaurantId = restaurantId,
    restaurantName = restaurantName,
    agentVersion = agentVersion,
    lastSeenAt = lastSeenAt,
    lastSessionReason = lastSessionReason,
    batteryLevel = lastTelemetry?.batteryLevel,
    charging = lastTelemetry?.charging,
    tempC = lastTelemetry?.tempC,
    thermal = lastTelemetry?.thermal,
    alerts = alerts.orEmpty().map { it.toDomain() },
)

internal fun AlertDto.toDomain() = DeviceAlert(ruleId, severity == Severities.CRITICAL, target, details)
