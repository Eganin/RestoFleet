package ru.restofleet.server.api.device

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import ru.restofleet.server.api.alert.toDto
import ru.restofleet.server.domain.alert.Alert
import ru.restofleet.server.domain.device.DecommissionResult
import ru.restofleet.server.domain.device.Device
import ru.restofleet.server.domain.device.DeviceDetails
import ru.restofleet.server.domain.device.DeviceOverview
import ru.restofleet.server.domain.ingest.DeviceEvent
import ru.restofleet.server.domain.ingest.ProbeRecord
import ru.restofleet.server.domain.ingest.TelemetrySnapshot
import ru.restofleet.server.domain.presence.DeviceSession
import ru.restofleet.server.domain.restaurant.Restaurant
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.dto.DeviceInfoDto
import ru.restofleet.shared.protocol.rest.console.DecommissionDto
import ru.restofleet.shared.protocol.rest.console.DeviceDetailsDto
import ru.restofleet.shared.protocol.rest.console.DeviceDto
import ru.restofleet.shared.protocol.rest.console.EventRecordDto
import ru.restofleet.shared.protocol.rest.console.ProbeRecordDto
import ru.restofleet.shared.protocol.rest.console.SessionDto
import ru.restofleet.shared.protocol.rest.console.TelemetryPointDto

// Маппинг домена в REST-DTO консоли: сами DTO — в shared:protocol `rest.console` (реш. 408)
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

/** Карточка: в `device` — ресторан и тревоги (поля консоли, реш. 437); телеметрия и сессия — рядом. */
internal fun DeviceDetails.toDto() = DeviceDetailsDto(
    device = device.toDto().withConsole(restaurant, alerts),
    lastTelemetry = lastTelemetry?.toDto(TelemetryMetrics.ALL),
    lastSession = lastSession?.toDto(),
)

/** Строка `GET /devices` с полями консоли (реш. 437). */
internal fun DeviceOverview.toDto() = device.toDto().withConsole(restaurant, alerts).copy(
    lastTelemetry = lastTelemetry?.toDto(TelemetryMetrics.ALL),
    lastSessionReason = lastSessionReason,
)

private fun DeviceDto.withConsole(restaurant: Restaurant?, alerts: List<Alert>) = copy(
    restaurantCode = restaurant?.code,
    restaurantName = restaurant?.name,
    alerts = alerts.map { it.toDto(name, restaurant?.code.orEmpty(), restaurant?.name.orEmpty()) },
)

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
