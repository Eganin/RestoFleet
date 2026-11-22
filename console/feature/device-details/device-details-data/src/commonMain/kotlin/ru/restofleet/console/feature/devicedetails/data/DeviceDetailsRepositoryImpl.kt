package ru.restofleet.console.feature.devicedetails.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.network.realtime.RealtimeClient
import ru.restofleet.console.core.network.realtime.RealtimeEvent
import ru.restofleet.console.feature.devicedetails.domain.DeviceCard
import ru.restofleet.console.feature.devicedetails.domain.DeviceDetailsRepository
import ru.restofleet.console.feature.devicedetails.domain.DeviceLiveEvent
import ru.restofleet.console.feature.devicedetails.domain.EventsPage
import ru.restofleet.console.feature.devicedetails.domain.ProbeResult
import ru.restofleet.console.feature.devicedetails.domain.TempPoint
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.kernel.map
import ru.restofleet.shared.protocol.rest.console.ws.WsServerMessage

class DeviceDetailsRepositoryImpl(
    private val api: DeviceDetailsApi,
    private val realtime: RealtimeClient,
    private val clock: Clock,
) : DeviceDetailsRepository {
    override suspend fun card(deviceId: String): Outcome<DeviceCard, ConsoleError> =
        api.details(deviceId).map { it.toDomain(clock.nowMillis()) }

    override suspend fun temperature(deviceId: String, fromMs: Long, limit: Int): Outcome<List<TempPoint>, ConsoleError> =
        api.telemetry(deviceId, fromMs, limit).map { dto ->
            dto.items.mapNotNull { p -> p.tempC?.let { TempPoint(p.ts, it) } }.sortedBy { it.ts }
        }

    override suspend fun probes(deviceId: String, limit: Int): Outcome<List<ProbeResult>, ConsoleError> =
        api.probes(deviceId, limit).map { dto -> dto.items.map { it.toDomain() } }

    override suspend fun events(deviceId: String, offset: Int, limit: Int): Outcome<EventsPage, ConsoleError> =
        api.events(deviceId, offset, limit).map { dto ->
            EventsPage(dto.items.map { it.toDomain() }, offset, hasMore = dto.items.size >= limit)
        }

    override fun live(deviceId: String): Flow<DeviceLiveEvent> = realtime.events
        .mapNotNull { event ->
            when (event) {
                RealtimeEvent.Connected -> DeviceLiveEvent.ReloadNeeded
                RealtimeEvent.SessionLost -> null
                is RealtimeEvent.Message -> when (val m = event.message) {
                    is WsServerMessage.Telemetry ->
                        m.data.takeIf { it.deviceId == deviceId }?.let { DeviceLiveEvent.TelemetryPushed(it.point.toDomain()) }
                    is WsServerMessage.DeviceStatus ->
                        m.data.takeIf { it.deviceId == deviceId }
                            ?.let { DeviceLiveEvent.StatusChanged(it.status, it.lastSeenAt) }
                    is WsServerMessage.Alert -> DeviceLiveEvent.ReloadNeeded.takeIf { m.data.deviceId == deviceId }
                    is WsServerMessage.Resync -> DeviceLiveEvent.ReloadNeeded
                    else -> null
                }
            }
        }
        .onStart { realtime.subscribeDevice(deviceId) }
        .onCompletion { realtime.unsubscribeDevice(deviceId) }
}
