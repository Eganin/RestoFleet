package ru.restofleet.console.feature.alerts.domain

import kotlinx.coroutines.flow.Flow
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Outcome

enum class AlertState(val wire: String) {
    Open("open"),
    Acknowledged("acknowledged"),
    Resolved("resolved"),
    ;

    companion object {
        fun fromWire(wire: String): AlertState = entries.firstOrNull { it.wire == wire } ?: Open
    }
}

/** Тревога (FR-CN-08). [auto] — закрыта автоматически, когда условие исчезло. */
data class AlertItem(
    val id: String,
    val ruleId: String,
    val critical: Boolean,
    val state: AlertState,
    val deviceId: String,
    val deviceName: String?,
    val restaurantName: String,
    val target: String?,
    val openedAt: Long,
    val ackBy: String?,
    val resolvedAt: Long?,
    val comment: String?,
    val auto: Boolean,
    val details: Map<String, String>,
)

/** Списки по вкладкам; [loadedAt] — момент загрузки для «N мин назад». */
data class AlertLists(
    val open: List<AlertItem>,
    val acknowledged: List<AlertItem>,
    val resolved: List<AlertItem>,
    val loadedAt: Long,
)

sealed interface AlertsEvent {
    data class Pushed(val alert: AlertItem) : AlertsEvent

    data object ReloadNeeded : AlertsEvent
}

interface AlertsRepository {
    suspend fun lists(limit: Int): Outcome<AlertLists, ConsoleError>

    suspend fun acknowledge(id: String): Outcome<AlertItem, ConsoleError>

    suspend fun resolve(id: String, comment: String): Outcome<AlertItem, ConsoleError>

    fun events(): Flow<AlertsEvent>
}
