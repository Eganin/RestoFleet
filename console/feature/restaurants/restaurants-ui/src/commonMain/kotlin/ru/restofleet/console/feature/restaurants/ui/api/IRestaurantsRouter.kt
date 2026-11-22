package ru.restofleet.console.feature.restaurants.ui.api

interface IRestaurantsRouter {
    /** Открыть устройства ресторана. */
    fun openRestaurantDevices(restaurantId: String, name: String)
}
