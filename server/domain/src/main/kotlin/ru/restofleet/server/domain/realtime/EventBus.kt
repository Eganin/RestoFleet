package ru.restofleet.server.domain.realtime

import kotlinx.coroutines.flow.Flow
import ru.restofleet.server.domain.alert.AlertView
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.ingest.TelemetrySnapshot
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.kernel.DeviceId

/** Событие реального времени для консоли (FR-BE-33, 51; реш. 419). Публикуется только после commit. */
sealed interface RealtimeEvent {
    data class DeviceStatusChanged(
        val deviceId: DeviceId,
        val restaurantId: RestaurantId,
        val status: DeviceStatus,
        val lastSeenAt: Long?,
        val reason: String?,
    ) : RealtimeEvent

    data class TelemetryReceived(val snapshot: TelemetrySnapshot) : RealtimeEvent

    data class AlertChanged(val view: AlertView) : RealtimeEvent
}

/**
 * Порт шины событий (реш. 419): in-process, бэкенд один. [publish] не блокирует и не бросает: при переполнении
 * реализация отбрасывает старейшее и считает потери (подписчик узнаёт о них и перечитывает состояние через REST).
 */
interface EventBus {
    fun publish(event: RealtimeEvent)

    fun events(): Flow<RealtimeEvent>
}
