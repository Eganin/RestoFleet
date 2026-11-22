package ru.restofleet.console.feature.devices.ui.viewmodel

import ru.restofleet.console.core.mvi.Reducer
import ru.restofleet.console.feature.devices.ui.viewmodel.model.DevicesSession

class DevicesReducer : Reducer<DevicesAction, DevicesSession> {
    override fun reduce(action: DevicesAction, state: DevicesSession): DevicesSession = when (action) {
        is DevicesAction.FilterChanged -> state.copy(
            filter = action.filter, items = emptyList(), hasMore = false, loading = true, error = null,
        )
        DevicesAction.ReloadStarted -> state.copy(loading = state.items.isEmpty(), error = null)
        DevicesAction.MoreStarted -> state.copy(loadingMore = true)
        is DevicesAction.PageLoaded -> state.copy(
            items = if (action.page.offset == 0) action.page.items else state.items + action.page.items,
            hasMore = action.page.hasMore,
            loading = false,
            loadingMore = false,
            error = null,
            loadedAt = action.page.loadedAt,
        )
        is DevicesAction.LoadFailed -> state.copy(loading = false, loadingMore = false, error = action.error)
        is DevicesAction.RestaurantsLoaded -> state.copy(restaurants = action.options)
        is DevicesAction.StatusChanged -> state.copy(
            items = state.items.map {
                if (it.id == action.deviceId) it.copy(status = action.status, lastSeenAt = action.lastSeenAt ?: it.lastSeenAt) else it
            },
        )
    }
}
