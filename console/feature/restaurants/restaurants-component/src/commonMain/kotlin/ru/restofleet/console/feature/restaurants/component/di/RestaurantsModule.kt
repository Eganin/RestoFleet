package ru.restofleet.console.feature.restaurants.component.di

import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.restofleet.console.core.network.HttpQualifiers
import ru.restofleet.console.feature.restaurants.component.RestaurantsComponent
import ru.restofleet.console.feature.restaurants.component.RestaurantsComponentFactory
import ru.restofleet.console.feature.restaurants.data.RestaurantsApi
import ru.restofleet.console.feature.restaurants.data.RestaurantsRepositoryImpl
import ru.restofleet.console.feature.restaurants.domain.LoadRestaurants
import ru.restofleet.console.feature.restaurants.domain.RestaurantsRepository
import ru.restofleet.console.feature.restaurants.ui.viewmodel.RestaurantsViewModel

val restaurantsModule = module {
    single { RestaurantsApi(get(named(HttpQualifiers.AUTHORIZED))) }
    single<RestaurantsRepository> { RestaurantsRepositoryImpl(get()) }
    factory { LoadRestaurants(get()) }
    single<RestaurantsComponentFactory> {
        RestaurantsComponentFactory { context, output ->
            RestaurantsComponent(context, { router -> RestaurantsViewModel(get(), router) }, output)
        }
    }
}
