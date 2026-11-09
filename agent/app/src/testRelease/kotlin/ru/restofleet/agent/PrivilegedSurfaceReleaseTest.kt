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

/**
 * Release-сборка (решение 140): привилегированной поверхности нет ни на экране, ни в графе. Исполнитель команд
 * `ExecuteCommand` остаётся: им пользуется приём команд по MQTT (M3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = RestoFleetApp::class)
class PrivilegedSurfaceReleaseTest {
    private val koinOverride = object : ExternalResource() {
        override fun before() = loadKoinModules(module { single<ServiceStarter> { ServiceStarter { } } })

        override fun after() = stopKoin()
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(WorkManagerIsolationRule()).around(koinOverride).around(compose)

    @Test
    fun deviceOwnerTabAndTabRowAreAbsentInTheReleaseBuild() {
        compose.onNodeWithText("Device Owner").assertDoesNotExist()
        compose.onNodeWithText("Lab").assertDoesNotExist()
        compose.onNodeWithText("Capability Lab", substring = true).assertExists()
    }

    @Test
    fun graphHasNoDeviceOwnerScreenAndNoManualCommandUseCases() {
        val koin = GlobalContext.get()

        assertThat(koin.getOrNull<DeviceOwnerTab>()).isNull()
        assertThat(koin.getOrNull<DeviceOwnerViewModel>()).isNull()
        assertThat(koin.getOrNull<RunCommandCheck>()).isNull()
        assertThat(koin.getOrNull<GrantSelfPermissions>()).isNull()
        assertThat(koin.getOrNull<FetchApkChecksum>()).isNull()
        assertThat(koin.getOrNull<ExecuteCommand>()).isNotNull()
    }

    @Test
    fun labCommandsSectionIsAbsent() {
        compose.onNodeWithText("Команды").assertDoesNotExist()
        assertThat(GlobalContext.get().get<LabViewModel>().uiState.value.commands).isNull()
    }

    @Test
    fun debugToolsM2AndM7AreAbsent() {
        val koin = GlobalContext.get()

        assertThat(koin.getOrNull<ForeignTopicPublisher>()).isNull()
        assertThat(koin.getOrNull<GenerateDebugEvents>()).isNull()
        compose.onNodeWithText("Публикация в чужой топик").assertDoesNotExist()
        compose.onNodeWithText("Сгенерировать N событий").assertDoesNotExist()
        assertThat(koin.get<LabViewModel>().uiState.value.debugTools).isFalse()
    }
}
