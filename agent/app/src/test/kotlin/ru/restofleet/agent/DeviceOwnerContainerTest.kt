package ru.restofleet.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.Capabilities
import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.agent.domain.command.Command
import ru.restofleet.agent.domain.port.DeviceAdmin
import ru.restofleet.agent.domain.port.CheckResultJournal
import ru.restofleet.agent.domain.usecase.ExecuteCommand
import ru.restofleet.agent.domain.usecase.GetDeviceInfo
import ru.restofleet.agent.domain.usecase.GrantSelfPermissions
import ru.restofleet.agent.domain.usecase.RunCapabilityChecks
import ru.restofleet.agent.domain.usecase.RunCommandCheck
import ru.restofleet.agent.domain.usecase.FetchApkChecksum
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.presentation.deviceowner.DeviceOwnerContainer
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerIntent.User
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerViewModel
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/** Диалог перезагрузки: эффект открывает его, ответы и закрытие экрана доходят до VM; без «да» перезагрузки нет. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DeviceOwnerContainerTest {
    @get:Rule val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val clock = Clock { 1L }
    private val capabilities = Capabilities(AgentMode.DEVICE_OWNER, 36, emptySet())
    private val admin = object : DeviceAdmin {
        override suspend fun isDeviceOwner() = true
        override suspend fun reboot(): Outcome<Unit, DomainError> {
            calls += "reboot"
            return Outcome.Success(Unit)
        }
        override suspend fun installApk(url: String, sha256: String): Outcome<Unit, DomainError> = Outcome.Success(Unit)
        override suspend fun startLockTask(pkg: String, url: String?): Outcome<Unit, DomainError> =
            Outcome.Success(Unit)
        override suspend fun grantSelfPermissions(): Map<String, Boolean> = emptyMap()
    }
    private val journal = object : CheckResultJournal {
        override fun record(result: CheckResult) = Unit
        override fun recorded(): Map<CheckId, CheckResult> = emptyMap()
    }
    private val vm = DeviceOwnerViewModel(
        runChecks = RunCapabilityChecks(emptyList(), clock),
        runCommand = RunCommandCheck(
            ExecuteCommand(
                { capabilities },
                admin,
                { _, _ -> Outcome.Success(emptyMap()) },
                { _, _, _ -> Outcome.Success(emptyMap()) },
                { Outcome.Success(emptyMap()) },
                clock,
            ),
            journal,
            clock,
        ),
        grantPermissions = GrantSelfPermissions({ capabilities }, admin, journal, clock),
        fetchChecksum = FetchApkChecksum { Outcome.Success("a".repeat(64)) },
        getDeviceInfo = GetDeviceInfo { DeviceInfo(36, "Pixel", true, AgentMode.DEVICE_OWNER, "0.1") },
        clock = clock,
        io = Dispatchers.Unconfined,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )
    private var shown by mutableStateOf(true)

    private fun startRunAll() {
        rule.setContent { if (shown) DeviceOwnerContainer(vm) }
        rule.runOnIdle { vm.onIntent(User.RunAll) }
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodesWithTextCount("Перезагрузить устройство?") > 0 }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

    @Test
    fun effectOpensTheDialogAndRebootHappensOnlyAfterYes() {
        startRunAll()
        assertThat(calls).isEmpty()

        rule.onNodeWithText("Перезагрузить").performClick()
        rule.waitUntil(TIMEOUT_MS) { calls.isNotEmpty() }

        assertThat(calls).containsExactly("reboot")
        rule.onNodeWithText("Перезагрузить устройство?").assertDoesNotExist()
    }

    @Test
    fun cancelAnswersNoAndNeverReboots() {
        startRunAll()

        rule.onNodeWithText("Отмена").performClick()
        rule.waitUntil(TIMEOUT_MS) { !rule.runOnIdle { vm.uiState.value.isRunningAll } }

        assertThat(calls).isEmpty()
        assertThat(vm.uiState.value.rows.first { it.id == "D2" }.summary).isEqualTo("не запускалась")
    }

    @Test
    fun leavingTheCompositionWithAnOpenDialogAnswersNo() {
        startRunAll()

        rule.runOnIdle { shown = false }
        rule.waitUntil(TIMEOUT_MS) { !rule.runOnIdle { vm.uiState.value.isRunningAll } }

        assertThat(calls).isEmpty()
        assertThat(vm.uiState.value.rows.first { it.id == "D2" }.summary).isEqualTo("не запускалась")
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
