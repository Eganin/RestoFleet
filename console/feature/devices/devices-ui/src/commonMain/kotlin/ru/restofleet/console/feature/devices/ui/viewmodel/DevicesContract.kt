package ru.restofleet.console.feature.devices.ui.viewmodel

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.mvi.contract.MviAction
import ru.restofleet.console.core.mvi.contract.MviEffect
import ru.restofleet.console.core.mvi.contract.MviIntent
import ru.restofleet.console.feature.devices.domain.DeviceFilter
import ru.restofleet.console.feature.devices.domain.DevicesPage
import ru.restofleet.console.feature.devices.domain.RestaurantOption

sealed interface DevicesIntent : MviIntent {
    sealed interface User : DevicesIntent {
        data class SearchChanged(val query: String) : User
        data object ToggleProblems : User
        data class RestaurantSelected(val restaurantId: String?) : User
        data class RoleSelected(val role: String?) : User
        data class StatusSelected(val status: String?) : User
        data class VersionSelected(val version: String?) : User
        data object LoadMore : User
        data object Refresh : User
        data class OpenDevice(val deviceId: String) : User
        data object OpenProvisioning : User
    }
}

sealed interface DevicesAction : MviAction {
    /** Фильтр изменён: список сбрасывается, показывается загрузка. */
    data class FilterChanged(val filter: DeviceFilter) : DevicesAction
    data object ReloadStarted : DevicesAction
    data object MoreStarted : DevicesAction
    data class PageLoaded(val page: DevicesPage) : DevicesAction
    data class LoadFailed(val error: ConsoleError) : DevicesAction
    data class RestaurantsLoaded(val options: List<RestaurantOption>) : DevicesAction
    data class StatusChanged(val deviceId: String, val status: String, val lastSeenAt: Long?) : DevicesAction
}

sealed interface DevicesEffect : MviEffect {
    data class ShowMessage(val text: String) : DevicesEffect
}
