package ru.restofleet.console.feature.restaurants.component

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import ru.restofleet.console.core.decompose.ContentComponent
import ru.restofleet.console.core.decompose.retainedViewModel
import ru.restofleet.console.feature.restaurants.ui.RestaurantsContainer
import ru.restofleet.console.feature.restaurants.ui.api.IRestaurantsRouter
import ru.restofleet.console.feature.restaurants.ui.viewmodel.RestaurantsViewModel

sealed interface RestaurantsOutput {
    data class OpenRestaurantDevices(val restaurantId: String, val name: String) : RestaurantsOutput
}

class RestaurantsComponent(
    componentContext: ComponentContext,
    createViewModel: (IRestaurantsRouter) -> RestaurantsViewModel,
    private val output: (RestaurantsOutput) -> Unit,
) : ContentComponent, ComponentContext by componentContext {
    private val router = object : IRestaurantsRouter {
        override fun openRestaurantDevices(restaurantId: String, name: String) =
            output(RestaurantsOutput.OpenRestaurantDevices(restaurantId, name))
    }
    private val viewModel = retainedViewModel { createViewModel(router) }

    @Composable
    override fun Content() {
        RestaurantsContainer(viewModel)
    }
}

fun interface RestaurantsComponentFactory {
    fun create(componentContext: ComponentContext, output: (RestaurantsOutput) -> Unit): ContentComponent
}
