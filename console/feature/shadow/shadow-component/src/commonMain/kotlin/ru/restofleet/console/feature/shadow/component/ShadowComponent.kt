package ru.restofleet.console.feature.shadow.component

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import ru.restofleet.console.core.decompose.ContentComponent
import ru.restofleet.console.core.decompose.retainedViewModel
import ru.restofleet.console.feature.shadow.ui.ShadowContainer
import ru.restofleet.console.feature.shadow.ui.viewmodel.ShadowViewModel

class ShadowComponent(
    componentContext: ComponentContext,
    createViewModel: () -> ShadowViewModel,
) : ContentComponent, ComponentContext by componentContext {
    private val viewModel = retainedViewModel { createViewModel() }

    @Composable
    override fun Content() {
        ShadowContainer(viewModel)
    }
}

/** Фабрика для карточки устройства: вкладка «Тень» подключается без зависимости фич друг от друга. */
fun interface ShadowComponentFactory {
    fun create(componentContext: ComponentContext, deviceId: String): ContentComponent
}
