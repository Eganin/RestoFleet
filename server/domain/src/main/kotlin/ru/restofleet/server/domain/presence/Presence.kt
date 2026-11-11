package ru.restofleet.server.domain.presence

import ru.restofleet.server.domain.device.DeviceAnnouncement
import ru.restofleet.shared.kernel.DeviceId

/** MQTT-сессия устройства (`device_sessions`): одна на `connId` (P3), история подключений (FR-BE-12). */
data class DeviceSession(
    val deviceId: DeviceId,
    val connId: String,
    val connectedAt: Long,
    val disconnectedAt: Long?,
    /** `lwt` | `stop` | [SessionCloseReason] */
    val reason: String?,
) {
    val isOpen: Boolean get() = disconnectedAt == null
}

/** Состояние из сообщения `status`. */
enum class PresenceState { ONLINE, OFFLINE }

/** Сообщение `status` устройства (в том числе LWT). Время сессий — время бэкенда, [ts] устройства не используется. */
data class StatusReport(
    val deviceId: DeviceId,
    val state: PresenceState,
    val ts: Long,
    val connId: String,
    /** причина `offline`: `lwt` | `stop` */
    val reason: String? = null,
    val announcement: DeviceAnnouncement = DeviceAnnouncement(),
)

/** Порт истории сессий. Только в транзакции. */
interface SessionRepository {
    /** Идемпотентно: повторное `online` того же `connId` строку не дублирует. */
    suspend fun open(session: DeviceSession)

    /** Последняя по времени подключения сессия устройства. */
    suspend fun latest(deviceId: DeviceId): DeviceSession?

    /** Закрывает сессию [connId], если она открыта. */
    suspend fun close(deviceId: DeviceId, connId: String, at: Long, reason: String)

    /** Закрывает все открытые сессии устройства, кроме [exceptConnId]. */
    suspend fun closeOpen(deviceId: DeviceId, at: Long, reason: String, exceptConnId: String? = null)
}
