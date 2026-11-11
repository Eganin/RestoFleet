package ru.restofleet.server.domain.device

import ru.restofleet.server.domain.ingest.TelemetrySnapshot
import ru.restofleet.server.domain.presence.DeviceSession
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.kernel.DeviceId

/** Роль устройства в ресторане (реш. 210: задаётся при выпуске кода). [code] — значение на проводе и в БД. */
enum class DeviceRole(val code: String) {
    KITCHEN("kitchen"),
    HALL("hall"),
    ASSEMBLY("assembly"),
    ;

    companion object {
        fun fromCode(code: String?): DeviceRole? = entries.firstOrNull { it.code == code }
    }
}

/** Жизненный цикл устройства; источник истины статуса — БД (P4). */
enum class DeviceStatus(val code: String) {
    PROVISIONED("provisioned"),
    ONLINE("online"),
    OFFLINE("offline"),
    DECOMMISSIONED("decommissioned"),
    ;

    companion object {
        fun fromCode(code: String?): DeviceStatus? = entries.firstOrNull { it.code == code }
    }
}

/** Как устройство связывается с бэкендом: по MQTT или REST-опросом (эксперимент, реш. 214). */
enum class DeviceTransport(val code: String) {
    MQTT("mqtt"),
    POLL("poll"),
    ;

    companion object {
        fun fromCode(code: String?): DeviceTransport? = entries.firstOrNull { it.code == code }
    }
}

/** Модель и ОС (из `/provision` и `status`). */
data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val osVersion: String,
    val apiLevel: Int,
)

/** Устройство реестра (FR-BE-01). */
data class Device(
    val id: DeviceId,
    val restaurantId: RestaurantId,
    val role: DeviceRole,
    val name: String?,
    val status: DeviceStatus,
    val transport: DeviceTransport?,
    val lastSeenAt: Long?,
    val decommissionedAt: Long?,
    val createdAt: Long,
    val info: DeviceInfo?,
    val agentVersion: String?,
    /** `normal` | `device_owner` — из `status` агента. */
    val mode: String?,
) {
    val isDecommissioned: Boolean get() = status == DeviceStatus.DECOMMISSIONED
}

/** Фильтр списка (`GET /devices?restaurant=&role=&status=`); `null` — без условия. */
data class DeviceFilter(
    val restaurantId: RestaurantId? = null,
    val role: DeviceRole? = null,
    val status: DeviceStatus? = null,
)

/** Что сообщило устройство о себе при подключении (`status: online`). */
data class DeviceAnnouncement(
    val agentVersion: String? = null,
    val mode: String? = null,
    val info: DeviceInfo? = null,
)

/** Итог вывода из эксплуатации; [retainedCleared] = `false` — retained остались, повторный вызов дочистит. */
data class DecommissionResult(val device: Device, val retainedCleared: Boolean)

/** Карточка устройства: реестр, последняя телеметрия и последняя MQTT-сессия. */
data class DeviceDetails(
    val device: Device,
    val lastTelemetry: TelemetrySnapshot?,
    val lastSession: DeviceSession?,
)
