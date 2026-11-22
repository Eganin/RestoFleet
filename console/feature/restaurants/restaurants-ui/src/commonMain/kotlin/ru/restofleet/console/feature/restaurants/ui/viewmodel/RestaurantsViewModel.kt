package ru.restofleet.console.feature.restaurants.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.restofleet.console.core.mvi.BaseMviViewModel
import ru.restofleet.console.core.mvi.MviStore
import ru.restofleet.console.core.mvi.defaultViewModelScope
import ru.restofleet.console.feature.restaurants.domain.LoadRestaurants
import ru.restofleet.console.feature.restaurants.ui.api.IRestaurantsRouter
import ru.restofleet.console.feature.restaurants.ui.viewmodel.mapper.RestaurantsUiStateMapper
import ru.restofleet.shared.kernel.Outcome

class RestaurantsViewModel(
    private val loadRestaurants: LoadRestaurants,
    private val router: IRestaurantsRouter,
    scope: CoroutineScope = defaultViewModelScope(),
) : BaseMviViewModel<RestaurantsSession, RestaurantsUiState, RestaurantsIntent, RestaurantsAction, RestaurantsEffect>(
    store = MviStore(RestaurantsSession(), RestaurantsReducer(), RestaurantsUiStateMapper()::map),
    scope = scope,
) {
    init {
        load()
    }

    override fun onIntent(intent: RestaurantsIntent) {
        when (intent) {
            is RestaurantsIntent.User.SearchChanged -> reduce(RestaurantsAction.QueryChanged(intent.query))
            RestaurantsIntent.User.Refresh -> load()
            is RestaurantsIntent.User.Open ->
                state.all.firstOrNull { it.id == intent.restaurantId }?.let { router.openRestaurantDevices(it.id, it.name) }
        }
    }

    private fun load() {
        reduce(RestaurantsAction.LoadStarted)
        scope.launch {
            when (val result = loadRestaurants()) {
                is Outcome.Success -> reduce(RestaurantsAction.Loaded(result.value))
                is Outcome.Failure -> reduce(RestaurantsAction.LoadFailed(result.error))
            }
        }
    }
}
