package ru.restofleet.console.root.di

import com.arkivanov.decompose.childContext
import org.koin.core.scope.Scope
import ru.restofleet.console.core.decompose.ContentComponent
import ru.restofleet.console.feature.commands.component.CommandsComponentFactory
import ru.restofleet.console.feature.dashboard.component.DashboardComponentFactory
import ru.restofleet.console.feature.dashboard.component.DashboardOutput
import ru.restofleet.console.feature.devicedetails.component.DeviceDetailsComponentFactory
import ru.restofleet.console.feature.devicedetails.component.DeviceDetailsOutput
import ru.restofleet.console.feature.devices.component.DevicesComponentFactory
import ru.restofleet.console.feature.devices.component.DevicesOutput
import ru.restofleet.console.feature.devices.domain.DeviceFilter
import ru.restofleet.console.feature.restaurants.component.RestaurantsComponentFactory
import ru.restofleet.console.feature.restaurants.component.RestaurantsOutput
import ru.restofleet.console.feature.shadow.component.ShadowComponentFactory
import ru.restofleet.console.root.MainNavigator
import ru.restofleet.console.root.PlaceholderComponent
import ru.restofleet.console.root.Screen
import ru.restofleet.console.root.ScreenFactory
import ru.restofleet.console.root.Tab

/**
 * Связывает `Screen` с фабриками фич и переводит их `Output` в навигацию. Единственное место, где фичи
 * «знакомы» друг с другом; здесь допустим `get()` (composition root).
 */
internal fun Scope.screenFactory(): ScreenFactory {
    val dashboard = get<DashboardComponentFactory>()
    val devices = get<DevicesComponentFactory>()
    val restaurants = get<RestaurantsComponentFactory>()
    val details = get<DeviceDetailsComponentFactory>()
    val shadow = get<ShadowComponentFactory>()
    val commands = get<CommandsComponentFactory>()
    return ScreenFactory { context, screen, nav ->
        when (screen) {
            Screen.Dashboard -> dashboard.create(context) { dashboardOutput(it, nav) }
            is Screen.Devices -> devices.create(
                context,
                DeviceFilter(restaurantId = screen.restaurantId, problemsOnly = screen.problemsOnly),
                screen.restaurantName,
            ) { devicesOutput(it, nav) }
            is Screen.DeviceDetails -> details.create(
                context,
                screen.deviceId,
                shadow.create(context.childContext("shadow"), screen.deviceId),
                commands.create(context.childContext("commands"), screen.deviceId),
            ) { if (it == DeviceDetailsOutput.Back) nav.back() }
            Screen.Restaurants -> restaurants.create(context) { restaurantsOutput(it, nav) }
            Screen.Alerts -> placeholder("Тревоги")
            is Screen.IncidentDetails -> placeholder("Инцидент")
            Screen.Provisioning -> placeholder("Новое устройство")
        }
    }
}

private fun placeholder(title: String): ContentComponent = PlaceholderComponent(title)

private fun dashboardOutput(output: DashboardOutput, nav: MainNavigator) = when (output) {
    is DashboardOutput.OpenDevice -> nav.open(Screen.DeviceDetails(output.deviceId))
    is DashboardOutput.OpenRestaurant ->
        nav.open(Screen.Devices(output.restaurantId, output.name, problemsOnly = false))
    DashboardOutput.OpenAlerts -> nav.switchTab(Tab.Alerts)
    DashboardOutput.Logout -> nav.logout()
}

private fun devicesOutput(output: DevicesOutput, nav: MainNavigator) = when (output) {
    is DevicesOutput.OpenDevice -> nav.open(Screen.DeviceDetails(output.deviceId))
    DevicesOutput.OpenProvisioning -> nav.open(Screen.Provisioning)
}

private fun restaurantsOutput(output: RestaurantsOutput, nav: MainNavigator) = when (output) {
    is RestaurantsOutput.OpenRestaurantDevices ->
        nav.open(Screen.Devices(output.restaurantId, output.name, problemsOnly = false))
}
