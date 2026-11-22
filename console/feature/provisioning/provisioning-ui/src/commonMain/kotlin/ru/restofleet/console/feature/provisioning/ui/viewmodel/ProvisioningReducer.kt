package ru.restofleet.console.feature.provisioning.ui.viewmodel

import ru.restofleet.console.core.mvi.Reducer

class ProvisioningReducer : Reducer<ProvisioningAction, ProvisioningSession> {
    override fun reduce(action: ProvisioningAction, state: ProvisioningSession): ProvisioningSession = when (action) {
        is ProvisioningAction.RestaurantsLoaded -> state.copy(
            restaurants = action.restaurants,
            restaurantId = state.restaurantId ?: action.restaurants.singleOrNull()?.id,
        )
        is ProvisioningAction.RestaurantSelected -> state.copy(restaurantId = action.id, error = null)
        is ProvisioningAction.RoleSelected -> state.copy(role = action.role, error = null)
        is ProvisioningAction.NameChanged -> state.copy(name = action.name, error = null)
        ProvisioningAction.IssueStarted -> state.copy(issuing = true, error = null)
        is ProvisioningAction.Issued -> state.copy(
            issuing = false, issued = action.code, remainingMs = action.remainingMs, expired = false, error = null,
        )
        is ProvisioningAction.IssueFailed -> state.copy(issuing = false, error = action.error)
        is ProvisioningAction.Tick -> if (action.remainingMs > 0) {
            state.copy(remainingMs = action.remainingMs)
        } else {
            state.copy(issued = null, remainingMs = 0, expired = true)
        }
        ProvisioningAction.Reset -> state.copy(issued = null, remainingMs = 0, expired = false, name = "", error = null)
    }
}
