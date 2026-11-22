package ru.restofleet.console.feature.devices.domain

import kotlinx.coroutines.flow.Flow
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Outcome

/** Открытая тревога устройства — для бейджа правила в строке списка. */
data class DeviceAlert(val ruleId: String, val critical: Boolean, val target: String?, val details: Map<String, String>)

/** Строка списка устройств (FR-CN-04). */
data class DeviceItem(
    val id: String,
    val name: String?,
    val role: String,
    val status: String,
    val restaurantId: String,
    val restaurantName: String?,
    val agentVersion: String?,
    val lastSeenAt: Long?,
    val lastSessionReason: String?,
    val batteryLevel: Int?,
    val charging: Boolean?,
    val tempC: Double?,
    val thermal: String?,
    val alerts: List<DeviceAlert>,
)

/** Фильтры списка; по умолчанию включён «Проблемы» (реш. макета FR-CN-04). */
data class DeviceFilter(
    val query: String = "",
    val restaurantId: String? = null,
    val role: String? = null,
    val status: String? = null,
    val version: String? = null,
    val problemsOnly: Boolean = true,
)

data class RestaurantOption(val id: String, val label: String)

data class DevicesPage(val items: List<DeviceItem>, val offset: Int, val hasMore: Boolean, val loadedAt: Long)

/** Что пришло по realtime: смена статуса устройства или сигнал перечитать список. */
sealed interface DevicesEvent {
    data class StatusChanged(val deviceId: String, val status: String, val lastSeenAt: Long?) : DevicesEvent

    data object ReloadNeeded : DevicesEvent
}

interface DevicesRepository {
    suspend fun page(filter: DeviceFilter, offset: Int, limit: Int): Outcome<DevicesPage, ConsoleError>

    suspend fun restaurants(): Outcome<List<RestaurantOption>, ConsoleError>

    fun events(): Flow<DevicesEvent>
}

class LoadDevicesPage(private val repository: DevicesRepository) {
    suspend operator fun invoke(filter: DeviceFilter, offset: Int, limit: Int = PAGE_SIZE) =
        repository.page(filter, offset, limit)

    companion object {
        const val PAGE_SIZE = 30
    }
}

class LoadRestaurantOptions(private val repository: DevicesRepository) {
    suspend operator fun invoke() = repository.restaurants()
}

class ObserveDevicesEvents(private val repository: DevicesRepository) {
    operator fun invoke(): Flow<DevicesEvent> = repository.events()
}
