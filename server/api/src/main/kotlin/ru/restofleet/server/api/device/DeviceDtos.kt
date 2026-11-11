package ru.restofleet.server.api.device

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import ru.restofleet.server.domain.device.DecommissionResult
import ru.restofleet.server.domain.device.Device
import ru.restofleet.server.domain.device.DeviceDetails
import ru.restofleet.server.domain.ingest.DeviceEvent
import ru.restofleet.server.domain.ingest.ProbeRecord
import ru.restofleet.server.domain.ingest.TelemetrySnapshot
import ru.restofleet.server.domain.presence.DeviceSession
import ru.restofleet.shared.protocol.RfJson

@Serializable
data class DeviceInfoDto(val model: String, val manufacturer: String, val osVersion: String, val apiLevel: Int)

/** Устройство реестра; `status` — `provisioned | online | offline | decommissioned` (источник истины — БД, P4). */
@Serializable
data class DeviceDto(
    val id: String,
    val restaurantId: String,
    val role: String,
    val name: String? = null,
    val status: String,
    val transport: String? = null,
    val lastSeenAt: Long? = null,
    val decommissionedAt: Long? = null,
    val createdAt: Long,
    val agentVersion: String? = null,
    val mode: String? = null,
    val deviceInfo: DeviceInfoDto? = null,
)

/** Точка телеметрии; с `metric=` — только выбранные поля (плюс `ts`, `seq`). */
@Serializable
data class TelemetryPointDto(
    val ts: Long,
    val seq: Long,
    val batteryLevel: Int? = null,
    val charging: Boolean? = null,
    val tempC: Double? = null,
    val thermal: String? = null,
    val rssi: Int? = null,
    val freeMb: Long? = null,
    val availMb: Long? = null,
    val foregroundApp: String? = null,
)

@Serializable
data class SessionDto(
    val connId: String,
    val connectedAt: Long,
    val disconnectedAt: Long? = null,
    val reason: String? = null,
)

/** `GET /devices/{id}`: реестр, последняя телеметрия и последняя MQTT-сессия. */
@Serializable
data class DeviceDetailsDto(
    val device: DeviceDto,
    val lastTelemetry: TelemetryPointDto? = null,
    val lastSession: SessionDto? = null,
)

/** `DELETE /devices/{id}`: `retainedCleared = false` — повторный вызов дочистит retained у брокера. */
@Serializable
data class DecommissionDto(val device: DeviceDto, val retainedCleared: Boolean)

@Serializable
data class ProbeRecordDto(
    val ts: Long,
    val target: String,
    val ok: Boolean,
    val status: Int? = null,
    val error: String? = null,
    val totalMs: Long,
    val dnsMs: Long? = null,
    val connectMs: Long? = null,
    val tlsMs: Long? = null,
    val ttfbMs: Long? = null,
)

/** Событие устройства: `payload` — сообщение `events` как есть (ТЗ 7.2). */
@Serializable
data class EventRecordDto(val ts: Long, val type: String, val payload: JsonElement)

internal fun Device.toDto() = DeviceDto(
    id = id.value,
    restaurantId = restaurantId.value,
    role = role.code,
    name = name,
    status = status.code,
    transport = transport?.code,
    lastSeenAt = lastSeenAt,
    decommissionedAt = decommissionedAt,
    createdAt = createdAt,
    agentVersion = agentVersion,
    mode = mode,
    deviceInfo = info?.let { DeviceInfoDto(it.model, it.manufacturer, it.osVersion, it.apiLevel) },
)

internal fun DeviceDetails.toDto() =
    DeviceDetailsDto(device.toDto(), lastTelemetry?.toDto(TelemetryMetrics.ALL), lastSession?.toDto())

internal fun DecommissionResult.toDto() = DecommissionDto(device.toDto(), retainedCleared)

internal fun DeviceSession.toDto() = SessionDto(connId, connectedAt, disconnectedAt, reason)

internal fun ProbeRecord.toDto() = ProbeRecordDto(
    ts = ts,
    target = result.target,
    ok = result.ok,
    status = result.status,
    error = result.error,
    totalMs = result.totalMs,
    dnsMs = result.dnsMs,
    connectMs = result.connectMs,
    tlsMs = result.tlsMs,
    ttfbMs = result.ttfbMs,
)

internal fun DeviceEvent.toDto() = EventRecordDto(ts, type, parsePayload(payload))

/** `payload` в БД — валидный JSON (`jsonb`); если нет — строкой, чтобы не терять событие из выдачи. */
private fun parsePayload(payload: String): JsonElement = try {
    RfJson.parseToJsonElement(payload)
} catch (_: IllegalArgumentException) {
    JsonPrimitive(payload)
}

/** Поля телеметрии для `metric=` (через запятую): имена совпадают с полями [TelemetryPointDto]. */
internal object TelemetryMetrics {
    val ALL = setOf("batteryLevel", "charging", "tempC", "thermal", "rssi", "freeMb", "availMb", "foregroundApp")
}

internal fun TelemetrySnapshot.toDto(metrics: Set<String>) = TelemetryPointDto(
    ts = ts,
    seq = seq,
    batteryLevel = batteryLevel.takeIf { "batteryLevel" in metrics },
    charging = charging.takeIf { "charging" in metrics },
    tempC = tempC.takeIf { "tempC" in metrics },
    thermal = thermal.takeIf { "thermal" in metrics },
    rssi = rssi.takeIf { "rssi" in metrics },
    freeMb = freeMb.takeIf { "freeMb" in metrics },
    availMb = availMb.takeIf { "availMb" in metrics },
    foregroundApp = foregroundApp.takeIf { "foregroundApp" in metrics },
)
