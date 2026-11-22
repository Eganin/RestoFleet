package ru.restofleet.console.feature.devices.component

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import ru.restofleet.console.core.decompose.ContentComponent
import ru.restofleet.console.core.decompose.retainedViewModel
import ru.restofleet.console.feature.devices.domain.DeviceFilter
import ru.restofleet.console.feature.devices.ui.DevicesContainer
import ru.restofleet.console.feature.devices.ui.api.IDevicesRouter
import ru.restofleet.console.feature.devices.ui.viewmodel.DevicesViewModel

sealed interface DevicesOutput {
    data class OpenDevice(val deviceId: String) : DevicesOutput
    data object OpenProvisioning : DevicesOutput
}

class DevicesComponent(
    componentContext: ComponentContext,
    createViewModel: (IDevicesRouter) -> DevicesViewModel,
    private val output: (DevicesOutput) -> Unit,
) : ContentComponent, ComponentContext by componentContext {
    private val router = object : IDevicesRouter {
        override fun openDevice(deviceId: String) = output(DevicesOutput.OpenDevice(deviceId))

        override fun openProvisioning() = output(DevicesOutput.OpenProvisioning)
    }
    private val viewModel = retainedViewModel { createViewModel(router) }

    @Composable
    override fun Content() {
        DevicesContainer(viewModel)
    }
}

/** [title] задан, когда список открыт из карточки ресторана (в [initialFilter] — его id). */
fun interface DevicesComponentFactory {
    fun create(
        componentContext: ComponentContext,
        initialFilter: DeviceFilter,
        title: String?,
        output: (DevicesOutput) -> Unit,
    ): ContentComponent
}
