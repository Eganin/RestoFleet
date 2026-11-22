package ru.restofleet.console.feature.incidents.component

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import ru.restofleet.console.core.decompose.ContentComponent
import ru.restofleet.console.core.decompose.retainedViewModel
import ru.restofleet.console.feature.incidents.ui.IncidentsContainer
import ru.restofleet.console.feature.incidents.ui.api.IIncidentDetailsRouter
import ru.restofleet.console.feature.incidents.ui.api.IIncidentsRouter
import ru.restofleet.console.feature.incidents.ui.details.IncidentDetailsContainer
import ru.restofleet.console.feature.incidents.ui.details.IncidentDetailsViewModel
import ru.restofleet.console.feature.incidents.ui.viewmodel.IncidentsViewModel

sealed interface IncidentsOutput {
    data class OpenIncident(val incidentId: String) : IncidentsOutput
}

sealed interface IncidentDetailsOutput {
    data object Back : IncidentDetailsOutput
}

/** Список инцидентов. Переключатель «Тревоги | Инциденты» собирает родитель (`console:root`). */
class IncidentsComponent(
    componentContext: ComponentContext,
    createViewModel: (IIncidentsRouter) -> IncidentsViewModel,
    private val output: (IncidentsOutput) -> Unit,
) : ContentComponent, ComponentContext by componentContext {
    private val router = object : IIncidentsRouter {
        override fun openIncident(incidentId: String) = output(IncidentsOutput.OpenIncident(incidentId))
    }
    private val viewModel = retainedViewModel { createViewModel(router) }

    @Composable
    override fun Content() {
        IncidentsContainer(viewModel)
    }
}

class IncidentDetailsComponent(
    componentContext: ComponentContext,
    createViewModel: (IIncidentDetailsRouter) -> IncidentDetailsViewModel,
    private val output: (IncidentDetailsOutput) -> Unit,
) : ContentComponent, ComponentContext by componentContext {
    private val router = object : IIncidentDetailsRouter {
        override fun back() = output(IncidentDetailsOutput.Back)
    }
    private val viewModel = retainedViewModel { createViewModel(router) }

    @Composable
    override fun Content() {
        IncidentDetailsContainer(viewModel)
    }
}

fun interface IncidentsComponentFactory {
    fun create(componentContext: ComponentContext, output: (IncidentsOutput) -> Unit): ContentComponent
}

fun interface IncidentDetailsComponentFactory {
    fun create(
        componentContext: ComponentContext,
        incidentId: String,
        output: (IncidentDetailsOutput) -> Unit,
    ): ContentComponent
}
