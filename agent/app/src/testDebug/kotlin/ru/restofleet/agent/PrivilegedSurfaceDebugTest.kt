package ru.restofleet.agent

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.restofleet.agent.data.lifecycle.ServiceStarter
import ru.restofleet.agent.domain.usecase.ExecuteCommand
import ru.restofleet.agent.domain.usecase.FetchApkChecksum
import ru.restofleet.agent.domain.port.ForeignTopicPublisher
import ru.restofleet.agent.domain.usecase.GenerateDebugEvents
import ru.restofleet.agent.domain.usecase.GrantSelfPermissions
import ru.restofleet.agent.domain.usecase.RunCommandCheck
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerViewModel
import ru.restofleet.agent.presentation.lab.viewmodel.LabViewModel

/** Debug-сборка (решение 140): вкладка Device Owner и команды Lab подключены. */
@RunWith(RobolectricTestRunner::class)
@Config(application = RestoFleetApp::class)
class PrivilegedSurfaceDebugTest {
    private val koinOverride = object : ExternalResource() {
        override fun before() = loadKoinModules(module { single<ServiceStarter> { ServiceStarter { } } })

        override fun after() = stopKoin()
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(WorkManagerIsolationRule()).around(koinOverride).around(compose)

    @Test
    fun deviceOwnerTabIsShownInTheDebugBuild() {
        compose.onNodeWithText("Device Owner").assertExists()
        compose.onNodeWithText("Lab").assertExists()
    }

    @Test
    fun graphHasTheDeviceOwnerScreenAndTheCommandUseCases() {
        val koin = GlobalContext.get()

        assertThat(koin.getOrNull<DeviceOwnerTab>()).isNotNull()
        assertThat(koin.getOrNull<DeviceOwnerViewModel>()).isNotNull()
        assertThat(koin.getOrNull<RunCommandCheck>()).isNotNull()
        assertThat(koin.getOrNull<GrantSelfPermissions>()).isNotNull()
        assertThat(koin.getOrNull<FetchApkChecksum>()).isNotNull()
        assertThat(koin.getOrNull<ExecuteCommand>()).isNotNull()
    }

    @Test
    fun labCommandsSectionIsOnTheScreen() {
        compose.onNodeWithText("Команды").assertExists()
        assertThat(GlobalContext.get().get<LabViewModel>().uiState.value.commands).isNotNull()
    }

    @Test
    fun debugToolsM2AndM7AreInTheGraphAndOnTheScreen() {
        val koin = GlobalContext.get()

        assertThat(koin.getOrNull<ForeignTopicPublisher>()).isNotNull()
        assertThat(koin.getOrNull<GenerateDebugEvents>()).isNotNull()
        compose.onNodeWithText("Публикация в чужой топик").assertExists()
        compose.onNodeWithText("Сгенерировать N событий").assertExists()
        assertThat(koin.get<LabViewModel>().uiState.value.debugTools).isTrue()
    }
}
