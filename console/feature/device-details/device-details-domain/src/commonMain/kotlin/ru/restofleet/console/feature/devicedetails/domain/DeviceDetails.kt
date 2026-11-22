package ru.restofleet.console.feature.devicedetails.domain

import kotlinx.coroutines.flow.Flow
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Outcome

data class DeviceAlert(val ruleId: String, val critical: Boolean, val target: String?, val details: Map<String, String>)

data class Telemetry(
    val ts: Long,
    val batteryLevel: Int?,
    val charging: Boolean?,
    val tempC: Double?,
    val thermal: String?,
    val rssi: Int?,
    val freeMb: Long?,
    val availMb: Long?,
    val foregroundApp: String?,
)

data class SessionInfo(val connectedAt: Long, val disconnectedAt: Long?, val reason: String?)

/** Карточка устройства (FR-CN-05). [loadedAt] — момент загрузки, от него считаются «N с назад». */
data class DeviceCard(
    val id: String,
    val name: String?,
    val role: String,
    val status: String,
    val restaurantName: String?,
    val model: String?,
    val osVersion: String?,
    val agentVersion: String?,
    val mode: String?,
    val lastSeenAt: Long?,
    val session: SessionInfo?,
    val telemetry: Telemetry?,
    val alerts: List<DeviceAlert>,
    val loadedAt: Long,
)

data class TempPoint(val ts: Long, val tempC: Double)

data class ProbeResult(
    val ts: Long,
    val target: String,
    val ok: Boolean,
    val status: Int?,
    val error: String?,
    val totalMs: Long,
    val dnsMs: Long?,
    val connectMs: Long?,
    val tlsMs: Long?,
    val ttfbMs: Long?,
)

data class DeviceEvent(val ts: Long, val type: String, val fields: Map<String, String>)

data class EventsPage(val items: List<DeviceEvent>, val offset: Int, val hasMore: Boolean)

/** Что пришло по realtime для этого устройства. */
sealed interface DeviceLiveEvent {
    data class TelemetryPushed(val telemetry: Telemetry) : DeviceLiveEvent

    data class StatusChanged(val status: String, val lastSeenAt: Long?) : DeviceLiveEvent

    data object ReloadNeeded : DeviceLiveEvent
}

interface DeviceDetailsRepository {
    suspend fun card(deviceId: String): Outcome<DeviceCard, ConsoleError>

    suspend fun temperature(deviceId: String, fromMs: Long, limit: Int): Outcome<List<TempPoint>, ConsoleError>

    suspend fun probes(deviceId: String, limit: Int): Outcome<List<ProbeResult>, ConsoleError>

    suspend fun events(deviceId: String, offset: Int, limit: Int): Outcome<EventsPage, ConsoleError>

    /** Подписка на телеметрию устройства живёт, пока собирается поток; при отмене сбора — отписка. */
    fun live(deviceId: String): Flow<DeviceLiveEvent>
}
