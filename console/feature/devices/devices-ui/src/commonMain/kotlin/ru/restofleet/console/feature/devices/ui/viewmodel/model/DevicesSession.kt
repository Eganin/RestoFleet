package ru.restofleet.console.feature.devices.ui.viewmodel.model

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.mvi.contract.MviState
import ru.restofleet.console.feature.devices.domain.DeviceFilter
import ru.restofleet.console.feature.devices.domain.DeviceItem
import ru.restofleet.console.feature.devices.domain.RestaurantOption

data class DevicesSession(
    val title: String? = null,
    val filter: DeviceFilter = DeviceFilter(),
    val items: List<DeviceItem> = emptyList(),
    val hasMore: Boolean = false,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: ConsoleError? = null,
    val loadedAt: Long = 0L,
    val restaurants: List<RestaurantOption> = emptyList(),
) : MviState
