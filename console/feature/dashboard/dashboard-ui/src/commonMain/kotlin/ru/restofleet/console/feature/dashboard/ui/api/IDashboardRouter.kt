package ru.restofleet.console.feature.dashboard.ui.api

/** Переходы с обзора; куда они ведут, решает родитель. */
interface IDashboardRouter {
    fun openDevice(deviceId: String)

    fun openRestaurant(restaurantId: String, name: String)

    fun openAlerts()

    fun logout()
}
