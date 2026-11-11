package ru.restofleet.agent

import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.agent.domain.port.CredentialStore
import ru.restofleet.agent.domain.usecase.GetRegistration
import ru.restofleet.agent.presentation.common.SettingsTarget
import ru.restofleet.agent.presentation.root.viewmodel.RootViewModel
import ru.restofleet.agent.presentation.root.viewmodel.model.AgentScreen

/** Роутер мастера и статуса: переходы — через навигацию, настройки — новой задачей от контекста приложения. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AgentRouterTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun TestScope.root(): RootViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = object : CredentialStore {
            override suspend fun save(s: BrokerSettings) = Unit

            override suspend fun load(): BrokerSettings? = null
        }
        return RootViewModel(GetRegistration(store), true, dispatcher, CoroutineScope(SupervisorJob() + dispatcher))
    }

    @Test
    fun openStatusAndOpenLabDriveTheRootNavigation() = runTest {
        val root = root()
        runCurrent()
        val router = AgentRouter(root, app, SettingsIntents(app.packageName))

        router.openLab()
        assertThat(root.uiState.value.screen).isEqualTo(AgentScreen.LAB)
        root.onIntent(ru.restofleet.agent.presentation.root.viewmodel.RootIntent.User.Back)
        router.openStatus()
        assertThat(root.uiState.value.screen).isEqualTo(AgentScreen.STATUS)
    }

    @Test
    fun openSettingsStartsTheSystemScreenFromTheApplicationContext() = runTest {
        val router = AgentRouter(root(), app, SettingsIntents(app.packageName))

        router.openSettings(SettingsTarget.USAGE_ACCESS)

        assertThat(shadowOf(app).nextStartedActivity.action).isEqualTo(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    }
}
