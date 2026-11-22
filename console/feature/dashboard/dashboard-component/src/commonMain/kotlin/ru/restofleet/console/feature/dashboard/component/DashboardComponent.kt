package ru.restofleet.console.feature.dashboard.component

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import ru.restofleet.console.core.decompose.ContentComponent
import ru.restofleet.console.core.decompose.retainedViewModel
import ru.restofleet.console.feature.dashboard.ui.DashboardContainer
import ru.restofleet.console.feature.dashboard.ui.api.IDashboardRouter
import ru.restofleet.console.feature.dashboard.ui.viewmodel.DashboardViewModel

/** Куда обзор просит перейти. Родитель переводит это в навигацию. */
sealed interface DashboardOutput {
    data class OpenDevice(val deviceId: String) : DashboardOutput
    data class OpenRestaurant(val restaurantId: String, val name: String) : DashboardOutput
    data object OpenAlerts : DashboardOutput
    data object Logout : DashboardOutput
}

class DashboardComponent(
    componentContext: ComponentContext,
    createViewModel: (IDashboardRouter) -> DashboardViewModel,
    private val output: (DashboardOutput) -> Unit,
) : ContentComponent, ComponentContext by componentContext {
    private val router = object : IDashboardRouter {
        override fun openDevice(deviceId: String) = output(DashboardOutput.OpenDevice(deviceId))

        override fun openRestaurant(restaurantId: String, name: String) =
            output(DashboardOutput.OpenRestaurant(restaurantId, name))

        override fun openAlerts() = output(DashboardOutput.OpenAlerts)

        override fun logout() = output(DashboardOutput.Logout)
    }
    private val viewModel = retainedViewModel { createViewModel(router) }

    @Composable
    override fun Content() {
        DashboardContainer(viewModel)
    }
}

fun interface DashboardComponentFactory {
    fun create(componentContext: ComponentContext, output: (DashboardOutput) -> Unit): ContentComponent
}
