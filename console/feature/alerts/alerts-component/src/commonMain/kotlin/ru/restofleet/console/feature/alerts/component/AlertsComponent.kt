package ru.restofleet.console.feature.alerts.component

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import ru.restofleet.console.core.decompose.ContentComponent
import ru.restofleet.console.core.decompose.retainedViewModel
import ru.restofleet.console.feature.alerts.ui.AlertsContainer
import ru.restofleet.console.feature.alerts.ui.api.IAlertsRouter
import ru.restofleet.console.feature.alerts.ui.viewmodel.AlertsViewModel

sealed interface AlertsOutput {
    data class OpenDevice(val deviceId: String) : AlertsOutput
}

class AlertsComponent(
    componentContext: ComponentContext,
    createViewModel: (IAlertsRouter) -> AlertsViewModel,
    private val output: (AlertsOutput) -> Unit,
) : ContentComponent, ComponentContext by componentContext {
    private val router = object : IAlertsRouter {
        override fun openDevice(deviceId: String) = output(AlertsOutput.OpenDevice(deviceId))
    }
    private val viewModel = retainedViewModel { createViewModel(router) }

    @Composable
    override fun Content() {
        AlertsContainer(viewModel)
    }
}

fun interface AlertsComponentFactory {
    fun create(componentContext: ComponentContext, output: (AlertsOutput) -> Unit): ContentComponent
}
