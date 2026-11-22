package ru.restofleet.console.feature.restaurants.ui.viewmodel

import androidx.compose.runtime.Immutable
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.mvi.contract.MviAction
import ru.restofleet.console.core.mvi.contract.MviEffect
import ru.restofleet.console.core.mvi.contract.MviIntent
import ru.restofleet.console.core.mvi.contract.MviState
import ru.restofleet.console.feature.restaurants.domain.Restaurant

data class RestaurantsSession(
    val all: List<Restaurant> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val error: ConsoleError? = null,
) : MviState

sealed interface RestaurantsIntent : MviIntent {
    sealed interface User : RestaurantsIntent {
        data class SearchChanged(val query: String) : User
        data object Refresh : User
        data class Open(val restaurantId: String) : User
    }
}

sealed interface RestaurantsAction : MviAction {
    data object LoadStarted : RestaurantsAction
    data class Loaded(val restaurants: List<Restaurant>) : RestaurantsAction
    data class LoadFailed(val error: ConsoleError) : RestaurantsAction
    data class QueryChanged(val query: String) : RestaurantsAction
}

sealed interface RestaurantsEffect : MviEffect {
    data class ShowMessage(val text: String) : RestaurantsEffect
}

@Immutable
data class RestaurantRowUi(val id: String, val title: String, val subtitle: String)

@Immutable
data class RestaurantsUiState(
    val query: String,
    val rows: List<RestaurantRowUi>,
    val loading: Boolean,
    val errorText: String?,
    val emptyText: String?,
)
