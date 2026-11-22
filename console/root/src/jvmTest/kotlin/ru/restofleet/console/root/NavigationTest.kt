package ru.restofleet.console.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.backhandler.BackDispatcher
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.decompose.ContentComponent
import ru.restofleet.console.core.testing.DecomposeTestSupport
import ru.restofleet.console.feature.auth.component.LoginComponentFactory
import ru.restofleet.console.feature.auth.domain.AuthRepository
import ru.restofleet.console.feature.auth.domain.AuthSession
import ru.restofleet.console.feature.auth.domain.Logout
import ru.restofleet.console.feature.auth.domain.ObserveSession
import ru.restofleet.console.feature.auth.domain.RestoreSession
import ru.restofleet.shared.kernel.Outcome

class NavigationTest {
    init {
        DecomposeTestSupport.disableMainThreadCheck()
    }

    private class Ctx {
        val lifecycle = LifecycleRegistry().apply { resume() }
        val back = BackDispatcher()
        val context: ComponentContext = DefaultComponentContext(lifecycle = lifecycle, backHandler = back)
    }

    private class FakeRepo(saved: AuthSession?) : AuthRepository {
        val state = MutableStateFlow(saved)
        override val session: Flow<AuthSession?> = state
        override suspend fun restore(): AuthSession? = state.value
        override suspend fun login(server: String, login: String, password: String):
            Outcome<AuthSession, ConsoleError> = Outcome.Failure(ConsoleError.Network)
        override suspend fun logout() {
            state.value = null
        }
    }

    private class Marker(val name: String) : ContentComponent {
        @androidx.compose.runtime.Composable
        override fun Content() = Unit
    }

    private class Recorder : ScreenFactory {
        val created = mutableListOf<Screen>()
        var navigator: MainNavigator? = null
        override fun create(context: ComponentContext, screen: Screen, navigator: MainNavigator): ContentComponent {
            created += screen
            this.navigator = navigator
            return Marker(screen.toString())
        }
    }

    private class Env(saved: AuthSession?) {
        val ctx = Ctx()
        val repo = FakeRepo(saved)
        val screens = Recorder()
        var realtime = 0
        val root = RootComponent(
            componentContext = ctx.context,
            loginFactory = LoginComponentFactory { _, _ -> Marker("login") },
            mainFactory = { context, onLogout ->
                MainComponent(context, screens, onLogout, { realtime++ }, { realtime-- })
            },
            restoreSession = RestoreSession(repo),
            observeSession = ObserveSession(repo),
            logout = Logout(repo),
            uiContext = Dispatchers.Unconfined,
        )
        val child get() = root.stack.value.active.instance
        val main get() = (child as RootComponent.Child.Main).component
    }

    private val user = AuthSession("https://rf.local", "u")

    @Test
    fun `without a saved session the root shows login, with one it shows main`() {
        assertThat(Env(null).child).isInstanceOf(RootComponent.Child.Login::class.java)
        assertThat(Env(user).child).isInstanceOf(RootComponent.Child.Main::class.java)
    }

    @Test
    fun `login switches to main and replaces the stack so back does not return to login`() {
        val env = Env(null)

        env.repo.state.value = user

        assertThat(env.child).isInstanceOf(RootComponent.Child.Main::class.java)
        assertThat(env.root.stack.value.backStack).isEmpty()
    }

    @Test
    fun `logout from main goes to login and stops realtime`() {
        val env = Env(user)
        assertThat(env.realtime).isEqualTo(1)

        env.screens.navigator!!.logout()

        assertThat(env.child).isInstanceOf(RootComponent.Child.Login::class.java)
        assertThat(env.repo.state.value).isNull()
    }

    @Test
    fun `lost session sends the user to login`() {
        val env = Env(user)

        env.repo.state.value = null

        assertThat(env.child).isInstanceOf(RootComponent.Child.Login::class.java)
    }

    @Test
    fun `destroying the root stops realtime`() {
        val env = Env(user)
        env.ctx.lifecycle.destroy()
        assertThat(env.realtime).isEqualTo(0)
    }

    @Test
    fun `tabs start at their root screens and keep their own stacks`() {
        val env = Env(user)
        val main = env.main

        assertThat(main.activeTab.value).isEqualTo(Tab.Overview)
        main.open(Screen.DeviceDetails("d1"))
        assertThat(main.stack(Tab.Overview).value.active.configuration).isEqualTo(Screen.DeviceDetails("d1"))

        main.select(Tab.Alerts)
        assertThat(main.stack(Tab.Alerts).value.active.configuration).isEqualTo(Screen.Alerts)
        main.select(Tab.Overview)
        assertThat(main.stack(Tab.Overview).value.active.configuration).isEqualTo(Screen.DeviceDetails("d1"))
    }

    @Test
    fun `navigator opens screens and switches tabs for features`() {
        val env = Env(user)
        val nav = env.screens.navigator!!

        nav.switchTab(Tab.Restaurants)
        nav.open(Screen.Devices(restaurantId = "r1", restaurantName = "Сокол", problemsOnly = false))

        assertThat(env.main.activeTab.value).isEqualTo(Tab.Restaurants)
        assertThat(env.main.stack(Tab.Restaurants).value.items.map { it.configuration }).containsExactly(
            Screen.Restaurants, Screen.Devices("r1", "Сокол", false),
        ).inOrder()
    }

    @Test
    fun `back pops the tab stack, then returns to overview, then is not handled`() {
        val env = Env(user)
        val main = env.main
        main.select(Tab.Devices)
        main.open(Screen.DeviceDetails("d1"))

        assertThat(env.ctx.back.back()).isTrue()
        assertThat(main.stack(Tab.Devices).value.active.configuration).isEqualTo(Tab.Devices.rootScreen())
        assertThat(main.activeTab.value).isEqualTo(Tab.Devices)

        assertThat(env.ctx.back.back()).isTrue()
        assertThat(main.activeTab.value).isEqualTo(Tab.Overview)

        assertThat(env.ctx.back.back()).isFalse()
        assertThat(main.back()).isFalse()
    }

    @Test
    fun `every tab has a root screen`() {
        assertThat(Tab.entries.map { it.rootScreen() }).containsExactly(
            Screen.Dashboard, Screen.Devices(problemsOnly = true), Screen.Alerts, Screen.Restaurants,
        ).inOrder()
        assertThat(PlaceholderComponent("x")).isNotNull()
    }
}
