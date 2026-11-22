package ru.restofleet.console.feature.provisioning.component

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import ru.restofleet.console.core.decompose.ContentComponent
import ru.restofleet.console.core.decompose.retainedViewModel
import ru.restofleet.console.feature.provisioning.ui.ProvisioningContainer
import ru.restofleet.console.feature.provisioning.ui.api.IProvisioningRouter
import ru.restofleet.console.feature.provisioning.ui.viewmodel.ProvisioningViewModel

sealed interface ProvisioningOutput {
    data object Back : ProvisioningOutput
}

class ProvisioningComponent(
    componentContext: ComponentContext,
    createViewModel: (IProvisioningRouter) -> ProvisioningViewModel,
    private val output: (ProvisioningOutput) -> Unit,
) : ContentComponent, ComponentContext by componentContext {
    private val router = object : IProvisioningRouter {
        override fun back() = output(ProvisioningOutput.Back)
    }
    private val viewModel = retainedViewModel { createViewModel(router) }

    @Composable
    override fun Content() {
        ProvisioningContainer(viewModel)
    }
}

fun interface ProvisioningComponentFactory {
    fun create(componentContext: ComponentContext, output: (ProvisioningOutput) -> Unit): ContentComponent
}
