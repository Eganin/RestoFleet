package ru.restofleet.console.root

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.dsl.koinApplication
import ru.restofleet.console.core.network.HttpQualifiers
import ru.restofleet.console.core.network.ServerEndpoint
import ru.restofleet.console.core.network.realtime.RealtimeClient
import ru.restofleet.console.core.storage.InMemoryTokenStore
import ru.restofleet.console.core.testing.DecomposeTestSupport
import ru.restofleet.console.core.testing.TestDispatchers
import ru.restofleet.console.root.di.PlatformDependencies
import ru.restofleet.console.root.di.consoleModules
import ru.restofleet.console.root.di.createRootComponent
import org.koin.core.qualifier.named

class ConsoleModulesTest {
    init {
        DecomposeTestSupport.disableMainThreadCheck()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setMain() = Dispatchers.setMain(Dispatchers.Unconfined)

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun platform() = PlatformDependencies(
        engineFactory = { MockEngine { respond("") } },
        tokenStore = InMemoryTokenStore(),
        dispatchers = TestDispatchers.of(),
    )

    @Test
    fun `module graph resolves the core and builds the root component`() {
        val koin = koinApplication { modules(consoleModules(platform())) }.koin

        assertThat(koin.get<ServerEndpoint>()).isNotNull()
        assertThat(koin.get<RealtimeClient>()).isNotNull()
        assertThat(koin.get<io.ktor.client.HttpClient>(named(HttpQualifiers.AUTHORIZED))).isNotNull()
        assertThat(koin.get<io.ktor.client.HttpClient>(named(HttpQualifiers.BARE))).isNotNull()

        val ctx = DefaultComponentContext(LifecycleRegistry().apply { resume() })
        val root = koin.createRootComponent(ctx, Dispatchers.Unconfined)

        // сессии нет → вход
        assertThat(root.stack.value.active.instance).isInstanceOf(RootComponent.Child.Login::class.java)
    }

    private class NullNavigator : MainNavigator {
        override fun open(screen: Screen) = Unit

        override fun switchTab(tab: Tab) = Unit

        override fun back() = Unit

        override fun logout() = Unit
    }

    @Test
    fun `screen factory builds every screen from the module graph`() {
        val koin = koinApplication { modules(consoleModules(platform())) }.koin
        val factory = koin.get<ScreenFactory>()
        val screens = listOf(
            Screen.Dashboard, Screen.Devices(), Screen.Devices("r1", "Сокол", false), Screen.DeviceDetails("d1"),
            Screen.Alerts, Screen.IncidentDetails("i1"), Screen.Restaurants, Screen.Provisioning,
        )

        screens.forEach { screen ->
            val ctx = DefaultComponentContext(LifecycleRegistry().apply { resume() })
            assertThat(factory.create(ctx, screen, NullNavigator())).isNotNull()
        }
    }
}
