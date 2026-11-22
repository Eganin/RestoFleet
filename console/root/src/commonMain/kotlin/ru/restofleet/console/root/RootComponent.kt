package ru.restofleet.console.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.value.Value
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.restofleet.console.core.decompose.ContentComponent
import ru.restofleet.console.core.decompose.componentScope
import ru.restofleet.console.feature.auth.component.LoginComponentFactory
import ru.restofleet.console.feature.auth.domain.Logout
import ru.restofleet.console.feature.auth.domain.ObserveSession
import ru.restofleet.console.feature.auth.domain.RestoreSession
import kotlin.coroutines.CoroutineContext

/**
 * Корень: «Вход | Главная». Выбор экрана определяет только сессия: после входа или выхода стек заменяется целиком,
 * поэтому «назад» с главного экрана не возвращает на вход, а отказ refresh (сессия кончилась) сам ведёт на вход.
 */
class RootComponent(
    componentContext: ComponentContext,
    private val loginFactory: LoginComponentFactory,
    private val mainFactory: (ComponentContext, onLogout: () -> Unit) -> MainComponent,
    restoreSession: RestoreSession,
    observeSession: ObserveSession,
    private val logout: Logout,
    uiContext: CoroutineContext,
) : ComponentContext by componentContext {
    private sealed interface Config {
        data object Loading : Config

        data object Login : Config

        data object Main : Config
    }

    sealed interface Child {
        data object Loading : Child

        class Login(val component: ContentComponent) : Child

        class Main(val component: MainComponent) : Child
    }

    private val scope = lifecycle.componentScope(uiContext)
    private val navigation = StackNavigation<Config>()

    val stack: Value<ChildStack<*, Child>> = childStack(
        source = navigation,
        serializer = null,
        initialConfiguration = Config.Loading,
        key = "root",
        handleBackButton = false,
    ) { config, context ->
        when (config) {
            Config.Loading -> Child.Loading
            Config.Login -> Child.Login(loginFactory.create(context) { /* дальше ведёт сессия */ })
            Config.Main -> Child.Main(mainFactory(context, ::onLogoutRequested))
        }
    }

    init {
        scope.launch {
            restoreSession()
            observeSession().map { it != null }.distinctUntilChanged().collect { loggedIn ->
                navigation.replaceAll(if (loggedIn) Config.Main else Config.Login)
            }
        }
    }

    private fun onLogoutRequested() {
        scope.launch { logout() }
    }
}
