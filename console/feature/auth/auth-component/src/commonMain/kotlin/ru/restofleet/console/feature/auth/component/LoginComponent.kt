package ru.restofleet.console.feature.auth.component

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import ru.restofleet.console.core.decompose.ContentComponent
import ru.restofleet.console.core.decompose.retainedViewModel
import ru.restofleet.console.feature.auth.ui.LoginContainer
import ru.restofleet.console.feature.auth.ui.api.IAuthRouter
import ru.restofleet.console.feature.auth.ui.viewmodel.LoginViewModel

/** Экран входа как компонент Decompose. ViewModel создаётся фабрикой DI и живёт в `InstanceKeeper`. */
class LoginComponent(
    componentContext: ComponentContext,
    createViewModel: (IAuthRouter) -> LoginViewModel,
    private val onLoggedIn: () -> Unit,
) : ContentComponent, ComponentContext by componentContext {
    private val router = object : IAuthRouter {
        override fun onLoggedIn() = this@LoginComponent.onLoggedIn()
    }
    private val viewModel = retainedViewModel { createViewModel(router) }

    @Composable
    override fun Content() {
        LoginContainer(viewModel)
    }
}

/** Фабрика для родителя: он не знает ни ViewModel, ни use cases. */
fun interface LoginComponentFactory {
    fun create(componentContext: ComponentContext, onLoggedIn: () -> Unit): ContentComponent
}
