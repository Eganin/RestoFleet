package ru.restofleet.console.root.di

import com.arkivanov.decompose.childContext
import org.koin.core.scope.Scope
import ru.restofleet.console.feature.alerts.component.AlertsComponentFactory
import ru.restofleet.console.feature.alerts.component.AlertsOutput
import ru.restofleet.console.feature.commands.component.CommandsComponentFactory
import ru.restofleet.console.feature.dashboard.component.DashboardComponentFactory
import ru.restofleet.console.feature.dashboard.component.DashboardOutput
import ru.restofleet.console.feature.devicedetails.component.DeviceDetailsComponentFactory
import ru.restofleet.console.feature.devicedetails.component.DeviceDetailsOutput
import ru.restofleet.console.feature.devices.component.DevicesComponentFactory
import ru.restofleet.console.feature.devices.component.DevicesOutput
import ru.restofleet.console.feature.devices.domain.DeviceFilter
import ru.restofleet.console.feature.incidents.component.IncidentDetailsComponentFactory
import ru.restofleet.console.feature.incidents.component.IncidentDetailsOutput
import ru.restofleet.console.feature.incidents.component.IncidentsComponentFactory
import ru.restofleet.console.feature.incidents.component.IncidentsOutput
import ru.restofleet.console.feature.provisioning.component.ProvisioningComponentFactory
import ru.restofleet.console.feature.provisioning.component.ProvisioningOutput
import ru.restofleet.console.feature.restaurants.component.RestaurantsComponentFactory
import ru.restofleet.console.feature.restaurants.component.RestaurantsOutput
import ru.restofleet.console.feature.shadow.component.ShadowComponentFactory
import ru.restofleet.console.root.AlertsHostComponent
import ru.restofleet.console.root.MainNavigator
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
    val alerts = get<AlertsComponentFactory>()
    val incidents = get<IncidentsComponentFactory>()
    val incidentDetails = get<IncidentDetailsComponentFactory>()
    val provisioning = get<ProvisioningComponentFactory>()
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
            Screen.Alerts -> AlertsHostComponent(
                alerts = alerts.create(context.childContext("alerts")) { alertsOutput(it, nav) },
                incidents = incidents.create(context.childContext("incidents")) { incidentsOutput(it, nav) },
            )
            is Screen.IncidentDetails -> incidentDetails.create(context, screen.incidentId) {
                if (it == IncidentDetailsOutput.Back) nav.back()
            }
            Screen.Provisioning -> provisioning.create(context) { if (it == ProvisioningOutput.Back) nav.back() }
        }
    }
}

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

private fun alertsOutput(output: AlertsOutput, nav: MainNavigator) = when (output) {
    is AlertsOutput.OpenDevice -> nav.open(Screen.DeviceDetails(output.deviceId))
}

private fun incidentsOutput(output: IncidentsOutput, nav: MainNavigator) = when (output) {
    is IncidentsOutput.OpenIncident -> nav.open(Screen.IncidentDetails(output.incidentId))
}

private fun restaurantsOutput(output: RestaurantsOutput, nav: MainNavigator) = when (output) {
    is RestaurantsOutput.OpenRestaurantDevices ->
        nav.open(Screen.Devices(output.restaurantId, output.name, problemsOnly = false))
}
