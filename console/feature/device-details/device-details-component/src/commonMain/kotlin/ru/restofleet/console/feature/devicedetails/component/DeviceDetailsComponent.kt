package ru.restofleet.console.feature.devicedetails.component

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import ru.restofleet.console.core.decompose.ContentComponent
import ru.restofleet.console.core.decompose.retainedViewModel
import ru.restofleet.console.feature.devicedetails.ui.DeviceDetailsContainer
import ru.restofleet.console.feature.devicedetails.ui.api.IDeviceDetailsRouter
import ru.restofleet.console.feature.devicedetails.ui.viewmodel.DetailsViewModel

sealed interface DeviceDetailsOutput {
    data object Back : DeviceDetailsOutput
}

/**
 * Хост вкладок. «Тень» и «Команда» — компоненты других фич, их поставляет родитель; здесь они только
 * рисуются в своих слотах (фича от `shadow` и `commands` не зависит).
 */
class DeviceDetailsComponent(
    componentContext: ComponentContext,
    createViewModel: (IDeviceDetailsRouter) -> DetailsViewModel,
    private val shadow: ContentComponent,
    private val commands: ContentComponent,
    private val output: (DeviceDetailsOutput) -> Unit,
) : ContentComponent, ComponentContext by componentContext {
    private val router = object : IDeviceDetailsRouter {
        override fun back() = output(DeviceDetailsOutput.Back)
    }
    private val viewModel = retainedViewModel { createViewModel(router) }

    @Composable
    override fun Content() {
        DeviceDetailsContainer(viewModel, shadowContent = { shadow.Content() }, commandsContent = { commands.Content() })
    }
}

/** [shadow] и [commands] — фабрики дочерних экранов, привязанные к тому же устройству. */
fun interface DeviceDetailsComponentFactory {
    fun create(
        componentContext: ComponentContext,
        deviceId: String,
        shadow: ContentComponent,
        commands: ContentComponent,
        output: (DeviceDetailsOutput) -> Unit,
    ): ContentComponent
}
