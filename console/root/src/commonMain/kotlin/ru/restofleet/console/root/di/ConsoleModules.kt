package ru.restofleet.console.root.di

import com.arkivanov.decompose.ComponentContext
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.restofleet.console.core.common.AppDispatchers
import ru.restofleet.console.core.network.HttpClientFactory
import ru.restofleet.console.core.network.HttpQualifiers
import ru.restofleet.console.core.network.KtorRefreshApi
import ru.restofleet.console.core.network.ServerEndpoint
import ru.restofleet.console.core.network.SessionManager
import ru.restofleet.console.core.network.realtime.KtorWsConnector
import ru.restofleet.console.core.network.realtime.RealtimeClient
import ru.restofleet.console.core.storage.TokenStore
import ru.restofleet.console.feature.auth.component.LoginComponentFactory
import ru.restofleet.console.feature.auth.component.di.authModule
import ru.restofleet.console.feature.auth.domain.Logout
import ru.restofleet.console.feature.alerts.component.di.alertsModule
import ru.restofleet.console.feature.commands.component.di.commandsModule
import ru.restofleet.console.feature.dashboard.component.di.dashboardModule
import ru.restofleet.console.feature.devicedetails.component.di.deviceDetailsModule
import ru.restofleet.console.feature.devices.component.di.devicesModule
import ru.restofleet.console.feature.incidents.component.di.incidentsModule
import ru.restofleet.console.feature.provisioning.component.di.provisioningModule
import ru.restofleet.console.feature.restaurants.component.di.restaurantsModule
import ru.restofleet.console.feature.shadow.component.di.shadowModule
import ru.restofleet.console.feature.auth.domain.ObserveSession
import ru.restofleet.console.feature.auth.domain.RestoreSession
import ru.restofleet.console.root.MainComponent
import ru.restofleet.console.root.RootComponent
import ru.restofleet.console.root.ScreenFactory
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.SystemClock
import kotlin.coroutines.CoroutineContext

/** Ядро: адрес сервера, клиенты HTTP, сессия, realtime. */
fun coreModule(platform: PlatformDependencies): Module = module {
    single { platform.dispatchers }
    single<Clock> { SystemClock }
    single { ServerEndpoint() }
    single(named(HttpQualifiers.BARE)) {
        HttpClientFactory.bare(platform.engineFactory(), get(), platform.debugLogger)
    }
    single { SessionManager(platform.tokenStore, KtorRefreshApi(get(named(HttpQualifiers.BARE))), get()) }
    single(named(HttpQualifiers.AUTHORIZED)) {
        HttpClientFactory.authorized(platform.engineFactory(), get(), get<SessionManager>(), platform.debugLogger)
    }
    single {
        val endpoint = get<ServerEndpoint>()
        RealtimeClient(
            connector = KtorWsConnector(get(named(HttpQualifiers.AUTHORIZED))),
            tokens = get<SessionManager>(),
            url = { endpoint.wsUrl() },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
}

/** Экраны вкладок. Пока заглушки; фичи подключаются в `ScreenFactories`. */
val screensModule: Module = module {
    single<ScreenFactory> { screenFactory() }
}

/** Все модули консоли в порядке подключения. */
fun consoleModules(platform: PlatformDependencies): List<Module> = listOf(
    coreModule(platform),
    authModule,
    dashboardModule,
    devicesModule,
    restaurantsModule,
    deviceDetailsModule,
    shadowModule,
    commandsModule,
    alertsModule,
    incidentsModule,
    provisioningModule,
    screensModule,
)

/** Собирает корневой компонент из графа Koin. Единственное место, где компоненты получают зависимости из DI. */
fun Koin.createRootComponent(context: ComponentContext, uiContext: CoroutineContext): RootComponent {
    val realtime = get<RealtimeClient>()
    val screens = get<ScreenFactory>()
    return RootComponent(
        componentContext = context,
        loginFactory = get<LoginComponentFactory>(),
        mainFactory = { ctx, onLogout -> MainComponent(ctx, screens, onLogout, realtime::start, realtime::stop) },
        restoreSession = get<RestoreSession>(),
        observeSession = get<ObserveSession>(),
        logout = get<Logout>(),
        uiContext = uiContext,
    )
}
