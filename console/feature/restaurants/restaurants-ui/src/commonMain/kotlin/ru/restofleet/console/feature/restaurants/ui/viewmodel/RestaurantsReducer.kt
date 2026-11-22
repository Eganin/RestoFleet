package ru.restofleet.console.feature.restaurants.ui.viewmodel

import ru.restofleet.console.core.mvi.Reducer

class RestaurantsReducer : Reducer<RestaurantsAction, RestaurantsSession> {
    override fun reduce(action: RestaurantsAction, state: RestaurantsSession): RestaurantsSession = when (action) {
        RestaurantsAction.LoadStarted -> state.copy(loading = state.all.isEmpty(), error = null)
        is RestaurantsAction.Loaded -> state.copy(all = action.restaurants, loading = false, error = null)
        is RestaurantsAction.LoadFailed -> state.copy(loading = false, error = action.error)
        is RestaurantsAction.QueryChanged -> state.copy(query = action.query)
    }
}
