package ru.restofleet.shared.protocol.rest.console

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import ru.restofleet.shared.protocol.dto.DeviceInfoDto

/**
 * Устройство реестра; `status` — `provisioned | online | offline | decommissioned` (источник истины — БД, P4),
 * `role` — `kitchen | hall | assembly`, `transport` — `mqtt | poll`, `mode` — `normal | device_owner`.
 *
 * Поля консоли (П, необязательные — ответ К остаётся валидным подмножеством):
 * - [restaurantCode], [restaurantName] — для строки списка без второго запроса;
 * - [lastTelemetry] — последняя точка (только `GET /devices`; в карточке — `DeviceDetailsDto.lastTelemetry`);
 * - [lastSessionReason] — причина закрытия последней сессии (`lwt`, `stop`) у офлайн-устройства;
 * - [alerts] — открытые и подтверждённые неподавленные тревоги устройства, сначала критичные, затем новые.
 */
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
    val restaurantCode: String? = null,
    val restaurantName: String? = null,
    val lastTelemetry: TelemetryPointDto? = null,
    val lastSessionReason: String? = null,
    val alerts: List<AlertDto>? = null,
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

/** MQTT-сессия устройства; `reason` закрытия — `lwt`, `stop`. */
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

/** Результат проверки доступности с фазами (мс) для водопада DNS/TCP/TLS/TTFB. */
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

/** Коды полей устройства. */
object DeviceStatuses {
    const val PROVISIONED = "provisioned"
    const val ONLINE = "online"
    const val OFFLINE = "offline"
    const val DECOMMISSIONED = "decommissioned"
    val ALL: List<String> = listOf(PROVISIONED, ONLINE, OFFLINE, DECOMMISSIONED)
}

object DeviceRoles {
    const val KITCHEN = "kitchen"
    const val HALL = "hall"
    const val ASSEMBLY = "assembly"
    val ALL: List<String> = listOf(KITCHEN, HALL, ASSEMBLY)
}
