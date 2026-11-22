package ru.restofleet.console.feature.devicedetails.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.restofleet.console.feature.devicedetails.domain.DeviceAlert
import ru.restofleet.console.feature.devicedetails.domain.DeviceCard
import ru.restofleet.console.feature.devicedetails.domain.DeviceEvent
import ru.restofleet.console.feature.devicedetails.domain.ProbeResult
import ru.restofleet.console.feature.devicedetails.domain.SessionInfo
import ru.restofleet.console.feature.devicedetails.domain.Telemetry
import ru.restofleet.shared.protocol.rest.console.AlertDto
import ru.restofleet.shared.protocol.rest.console.DeviceDetailsDto
import ru.restofleet.shared.protocol.rest.console.EventRecordDto
import ru.restofleet.shared.protocol.rest.console.ProbeRecordDto
import ru.restofleet.shared.protocol.rest.console.Severities
import ru.restofleet.shared.protocol.rest.console.TelemetryPointDto

internal fun TelemetryPointDto.toDomain() =
    Telemetry(ts, batteryLevel, charging, tempC, thermal, rssi, freeMb, availMb, foregroundApp)

internal fun AlertDto.toDomain() = DeviceAlert(ruleId, severity == Severities.CRITICAL, target, details)

internal fun DeviceDetailsDto.toDomain(loadedAt: Long): DeviceCard {
    val d = device
    return DeviceCard(
        id = d.id,
        name = d.name,
        role = d.role,
        status = d.status,
        restaurantName = d.restaurantName,
        model = d.deviceInfo?.model,
        osVersion = d.deviceInfo?.osVersion,
        agentVersion = d.agentVersion,
        mode = d.mode,
        lastSeenAt = d.lastSeenAt,
        session = lastSession?.let { SessionInfo(it.connectedAt, it.disconnectedAt, it.reason) },
        telemetry = (lastTelemetry ?: d.lastTelemetry)?.toDomain(),
        alerts = d.alerts.orEmpty().map { it.toDomain() },
        loadedAt = loadedAt,
    )
}

internal fun ProbeRecordDto.toDomain() =
    ProbeResult(ts, target, ok, status, error, totalMs, dnsMs, connectMs, tlsMs, ttfbMs)

/** Полезная нагрузка события — плоская карта строк: тексты строит UI, структура домену не нужна. */
internal fun EventRecordDto.toDomain() = DeviceEvent(ts, type, flatten(payload))

internal fun flatten(element: JsonElement): Map<String, String> =
    (element as? JsonObject)?.entries.orEmpty()
        .filter { it.key != "type" && it.key != "ts" && it.value !is JsonNull }
        .associate { (k, v) -> k to if (v is JsonPrimitive) v.content else v.toString() }
