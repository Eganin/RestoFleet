package ru.restofleet.agent

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import ru.restofleet.agent.data.provisioning.ProvisioningTrust
import ru.restofleet.agent.domain.usecase.ExecuteCommand
import ru.restofleet.agent.domain.usecase.FetchApkChecksum
import ru.restofleet.agent.domain.port.ForeignTopicPublisher
import ru.restofleet.agent.domain.usecase.GenerateDebugEvents
import ru.restofleet.agent.domain.usecase.GrantSelfPermissions
import ru.restofleet.agent.domain.usecase.RunCommandCheck
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerViewModel
import ru.restofleet.agent.presentation.lab.viewmodel.LabViewModel
import ru.restofleet.shared.probes.ProbeSchedule

/**
 * Release-сборка (решение 140): привилегированной поверхности нет ни на экране, ни в графе; с КМ3 туда входит и
 * Capability Lab (реш. 275) — release показывает только мастер и статус. Исполнитель команд `ExecuteCommand`
 * остаётся: им пользуется приём команд по MQTT (M3).
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

    private fun awaitWizard() {
        // Регистрация читается на IO: дождаться выбора экрана (учётки нет — мастер)
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText("Настройка агента").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun wizardIsShownWithoutLabAndDeviceOwner() {
        // Реш. 140, 275: Capability Lab и вкладка Device Owner — только debug; в release нет и кнопки Lab
        awaitWizard()

        compose.onNodeWithText("Capability Lab", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Device Owner").assertDoesNotExist()
        compose.onNodeWithText("Lab").assertDoesNotExist()
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
    fun labScreenAndItsViewModelAreAbsent() {
        val koin = GlobalContext.get()

        assertThat(koin.getOrNull<LabScreen>()).isNull()
        assertThat(koin.getOrNull<LabViewModel>()).isNull()
        compose.onNodeWithText("Команды").assertDoesNotExist()
    }

    @Test
    fun debugToolsM2AndM7AreAbsent() {
        val koin = GlobalContext.get()

        assertThat(koin.getOrNull<ForeignTopicPublisher>()).isNull()
        assertThat(koin.getOrNull<GenerateDebugEvents>()).isNull()
        compose.onNodeWithText("Публикация в чужой топик").assertDoesNotExist()
        compose.onNodeWithText("Сгенерировать N событий").assertDoesNotExist()
    }

    @Test
    fun provisioningTrustsTheSystemStoreAndProbesKeepTheProductionMinimum() {
        // План 3.5: в release CA стенда нет — системное доверие; НФТ «нагрузка проверок» — не чаще 30 с
        val koin = GlobalContext.get()

        assertThat(koin.get<ProvisioningTrust>()).isEqualTo(ProvisioningTrust.System)
        assertThat(koin.get<ProbeSchedule>().minIntervalSec).isEqualTo(ProbeSchedule.DEFAULT_MIN_INTERVAL_SEC)
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
