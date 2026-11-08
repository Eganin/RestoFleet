package ru.restofleet.agent.presentation.lab.viewmodel

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.agent.domain.usecase.ExportCapabilityReport
import ru.restofleet.agent.domain.usecase.GetDeviceInfo
import ru.restofleet.agent.domain.usecase.PublishCheckResult
import ru.restofleet.agent.domain.usecase.RunCapabilityChecks
import ru.restofleet.agent.domain.usecase.RunCommandCheck
import ru.restofleet.agent.domain.error.MqttNotConfigured
import ru.restofleet.agent.presentation.common.Badge
import ru.restofleet.agent.presentation.lab.viewmodel.LabIntent.User
import ru.restofleet.agent.presentation.lab.viewmodel.model.LabCommand
import ru.restofleet.agent.presentation.support.CommandPorts
import ru.restofleet.agent.presentation.support.MemoryJournal
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome

/** Секция «Команды» Lab: C1–C4 исполняются как команды (а не как проверки) и попадают в строки C1–C4. */
class LabCommandsViewModelTest {
    private val clock = Clock { 1L }

    private inner class Fixture(scope: TestScope) {
        val ports = CommandPorts()
        val journal = MemoryJournal()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val checkRuns = mutableListOf<CheckId>()
        private val run = RunCapabilityChecks(
            listOf(CheckId.R1, CheckId.C1, CheckId.C4).map { id ->
                object : CapabilityCheck {
                    override val id = id
                    override suspend fun run(): CheckResult {
                        checkRuns += id
                        return CheckResult(id, CheckStatus.OK, mapOf("k" to "v"), null, 1L)
                    }
                }
            },
            clock,
        )
        val vm = LabViewModel(
            runChecks = run,
            exportReport = ExportCapabilityReport(
                run,
                { DeviceInfo(36, "Pixel", true, AgentMode.NORMAL, "0.1") },
                { Outcome.Success("/r.json") },
                clock,
                journal,
            ),
            publishResult = PublishCheckResult { Outcome.Failure(MqttNotConfigured) },
            getDeviceInfo = GetDeviceInfo { DeviceInfo(36, "Pixel", true, AgentMode.NORMAL, "0.1") },
            runCommand = RunCommandCheck(ports.executeCommand(clock), journal, clock),
            io = dispatcher,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )

        fun row(id: String) = vm.uiState.value.rows.first { it.id == id }
    }

    @Test
    fun c1ShowsTheFixedWarningMessage() = runTest {
        val f = Fixture(this)

        f.vm.onIntent(User.RunCommand(LabCommand.SHOW_MESSAGE))
        advanceUntilIdle()

        assertThat(f.ports.calls).containsExactly("message:Проверка RestoFleet:WARNING")
        assertThat(f.row("C1").badge).isEqualTo(Badge.OK)
    }

    @Test
    fun c2LaunchesThePackageFromTheFieldsWithTheStrategyFromCapabilities() = runTest {
        val f = Fixture(this)
        f.vm.onIntent(User.EditCommandPackage("com.android.chrome"))
        f.vm.onIntent(User.EditCommandUrl("https://example.com/x"))

        f.vm.onIntent(User.RunCommand(LabCommand.LAUNCH_APP))
        advanceUntilIdle()

        assertThat(f.ports.calls)
            .containsExactly("launch:com.android.chrome:https://example.com/x:DIRECT_OVERLAY")
        assertThat(f.row("C2").summary).contains("strategy DIRECT_OVERLAY")
    }

    @Test
    fun c2WithEmptyUrlLaunchesThePackageWithoutAnAddress() = runTest {
        val f = Fixture(this)

        f.vm.onIntent(User.RunCommand(LabCommand.LAUNCH_APP))
        advanceUntilIdle()

        assertThat(f.ports.calls).containsExactly("launch:ru.restofleet.dummy.waiter:null:DIRECT_OVERLAY")
    }

    @Test
    fun c3WaitsTenSecondsSoTheTesterCanPressHome() = runTest {
        val f = Fixture(this)

        f.vm.onIntent(User.RunCommand(LabCommand.LAUNCH_APP_DELAYED))
        runCurrent()
        assertThat(f.row("C3").isRunning).isTrue()
        advanceTimeBy(9_999)
        assertThat(f.ports.calls).isEmpty()

        advanceTimeBy(1)
        runCurrent()

        assertThat(f.ports.calls).containsExactly("launch:ru.restofleet.dummy.waiter:null:DIRECT_OVERLAY")
        assertThat(f.row("C3").isRunning).isFalse()
        assertThat(f.row("C3").badge).isEqualTo(Badge.OK)
    }

    @Test
    fun c4BeepsThreeSecondsAndShowsRingerMode() = runTest {
        val f = Fixture(this)

        f.vm.onIntent(User.RunCommand(LabCommand.BEEP))
        advanceUntilIdle()

        assertThat(f.ports.calls).containsExactly("beep:3000")
        assertThat(f.row("C4").summary).contains("ringerMode silent")
    }

    @Test
    fun clickingTheCommandRowRunsTheCommandNotTheEmptyCheck() = runTest {
        val f = Fixture(this)

        f.vm.onIntent(User.RunOne(CheckId.C4))
        advanceUntilIdle()

        assertThat(f.ports.calls).containsExactly("beep:3000")
        assertThat(f.checkRuns).isEmpty()
    }

    @Test
    fun portFailureBecomesAFailedRowAndNeverStaysRunning() = runTest {
        val f = Fixture(this)
        f.ports.failWith = IllegalStateException("boom")

        f.vm.onIntent(User.RunCommand(LabCommand.BEEP))
        advanceUntilIdle()

        assertThat(f.row("C4").badge).isEqualTo(Badge.FAIL)
        assertThat(f.row("C4").summary).isEqualTo("exception:IllegalStateException")
        assertThat(f.row("C4").isRunning).isFalse()
    }

    @Test
    fun repeatWhileRunningIsIgnored() = runTest {
        val f = Fixture(this)

        f.vm.onIntent(User.RunCommand(LabCommand.LAUNCH_APP_DELAYED))
        f.vm.onIntent(User.RunCommand(LabCommand.LAUNCH_APP_DELAYED))
        advanceUntilIdle()

        assertThat(f.ports.calls).hasSize(1)
    }

    @Test
    fun runAllNeverExecutesCommandsAndKeepsTheirEarlierResults() = runTest {
        val f = Fixture(this)
        f.vm.onIntent(User.RunCommand(LabCommand.BEEP))
        advanceUntilIdle()
        f.ports.calls.clear()

        f.vm.onIntent(User.RunAll)
        advanceUntilIdle()

        assertThat(f.ports.calls).isEmpty()
        assertThat(f.checkRuns).containsExactly(CheckId.R1)
        assertThat(f.row("C4").summary).contains("ringerMode silent")
        assertThat(f.row("C1").badge).isEqualTo(Badge.PENDING)
    }

    @Test
    fun commandIsIgnoredWhileRunAllIsInProgress() = runTest {
        val f = Fixture(this)

        f.vm.onIntent(User.RunAll)
        f.vm.onIntent(User.RunCommand(LabCommand.BEEP))
        advanceUntilIdle()

        assertThat(f.ports.calls).isEmpty()
    }

    @Test
    fun fieldEditsAreReflectedInTheCommandsSection() = runTest {
        val f = Fixture(this)

        f.vm.onIntent(User.EditCommandPackage("a.b"))
        f.vm.onIntent(User.EditCommandUrl("https://h/p"))

        val commands = f.vm.uiState.value.commands!!
        assertThat(commands.pkg).isEqualTo("a.b")
        assertThat(commands.url).isEqualTo("https://h/p")
    }

    @Test
    fun commandResultReachesTheExportedReportThroughTheJournal() = runTest {
        val f = Fixture(this)
        f.vm.onIntent(User.RunCommand(LabCommand.BEEP))
        advanceUntilIdle()

        assertThat(f.journal.recorded()[CheckId.C4]?.raw).containsExactly("ringerMode", "silent")
    }
}
