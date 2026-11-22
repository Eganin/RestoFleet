package ru.restofleet.console.feature.commands.component

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import ru.restofleet.console.core.decompose.ContentComponent
import ru.restofleet.console.core.decompose.retainedViewModel
import ru.restofleet.console.feature.commands.ui.CommandsContainer
import ru.restofleet.console.feature.commands.ui.viewmodel.CommandsViewModel

class CommandsComponent(
    componentContext: ComponentContext,
    createViewModel: () -> CommandsViewModel,
) : ContentComponent, ComponentContext by componentContext {
    private val viewModel = retainedViewModel { createViewModel() }

    @Composable
    override fun Content() {
        CommandsContainer(viewModel)
    }
}

/** Фабрика для карточки устройства: она получает готовый [ContentComponent] и не знает о команде ничего больше. */
fun interface CommandsComponentFactory {
    fun create(componentContext: ComponentContext, deviceId: String): ContentComponent
}
