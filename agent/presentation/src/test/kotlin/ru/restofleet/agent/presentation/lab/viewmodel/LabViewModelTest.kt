package ru.restofleet.agent.presentation.lab.viewmodel

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.presentation.lab.viewmodel.LabEffect.OpenSystemSettings
import ru.restofleet.agent.presentation.lab.viewmodel.LabEffect.ShowMessage
import ru.restofleet.agent.presentation.lab.viewmodel.LabIntent.User
import ru.restofleet.agent.presentation.lab.viewmodel.model.LabFilter
import ru.restofleet.agent.presentation.lab.viewmodel.model.SettingsTarget
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.agent.domain.error.MqttNotConfigured
import ru.restofleet.agent.domain.port.CapabilityReportWriter
import ru.restofleet.agent.domain.port.CheckResultPublisher
import ru.restofleet.agent.domain.usecase.ExportCapabilityReport
import ru.restofleet.agent.domain.usecase.GetDeviceInfo
import ru.restofleet.agent.domain.usecase.PublishCheckResult
import ru.restofleet.agent.domain.usecase.RunCapabilityChecks
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.kernel.UnexpectedError

class LabViewModelTest {
    private val clock = Clock { 1L }
    private val device = DeviceInfo(36, "Pixel 9a", true, AgentMode.NORMAL, "0.1.0")

    /** Проверка с подсчётом запусков; [gate] удерживает её, пока тест не откроет. */
    private class FakeCheck(
        override val id: CheckId,
        private val status: CheckStatus,
        private val gate: CompletableDeferred<Unit>? = null,
        private val failure: Throwable? = null,
    ) : CapabilityCheck {
        var runs = 0

        override suspend fun run(): CheckResult {
            runs++
            gate?.await()
            failure?.let { throw it }
            return CheckResult(id, status, mapOf("k" to "v"), null, 1L)
        }
    }

    private inner class Fixture(
        scope: TestScope,
        val checks: List<FakeCheck> = listOf(
            FakeCheck(CheckId.R1, CheckStatus.OK),
            FakeCheck(CheckId.R2, CheckStatus.UNAVAILABLE),
        ),
        private val writerOutcome: () -> Outcome<String, DomainError> = { Outcome.Success("/files/report.json") },
        private val publisherOutcome: () -> Outcome<Unit, DomainError> = { Outcome.Failure(MqttNotConfigured) },
        private val writerGate: CompletableDeferred<Unit>? = null,
        runClock: Clock = clock,
    ) {
        var writes = 0
        val published = mutableListOf<CheckResult>()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val run = RunCapabilityChecks(checks, runClock)
        private val writer = CapabilityReportWriter {
            writes++
            writerGate?.await()
            writerOutcome()
        }
        val vm = LabViewModel(
            runChecks = run,
            exportReport = ExportCapabilityReport(run, { device }, writer, clock),
            publishResult = PublishCheckResult(
                CheckResultPublisher {
                    published += it
                    publisherOutcome()
                },
            ),
            getDeviceInfo = GetDeviceInfo { device },
            io = dispatcher,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )
    }

    @Test
    fun loadsDeviceIntoHeader() = runTest {
        val f = Fixture(this)

        advanceUntilIdle()

        assertThat(f.vm.uiState.value.header).isEqualTo("Capability Lab · API 36")
    }

    @Test
    fun runAllMarksRunningThenDeliversResultsAndStops() = runTest {
        val f = Fixture(this)
        advanceUntilIdle()

        f.vm.uiState.test {
            awaitItem()
            f.vm.onIntent(User.RunAll)
            val started = awaitItem()
            assertThat(started.isRunningAll).isTrue()
            assertThat(started.rows.all { it.isRunning }).isTrue()

            advanceUntilIdle()
            val done = expectMostRecentItem()
            assertThat(done.isRunningAll).isFalse()
            assertThat(done.rows.none { it.isRunning }).isTrue()
            assertThat(done.rows.first { it.id == "R1" }.badge).isEqualTo(Badge.OK)
            assertThat(done.rows.first { it.id == "R2" }.badge).isEqualTo(Badge.FAIL)
            assertThat(done.rows.first { it.id == "R3" }.badge).isEqualTo(Badge.NA)
            assertThat(done.rows.none { it.badge == Badge.PENDING }).isTrue()
        }
    }

    @Test
    fun runOneRunsOnlyThatCheck() = runTest {
        val f = Fixture(this)

        f.vm.onIntent(User.RunOne(CheckId.R2))
        advanceUntilIdle()

        assertThat(f.checks.map { it.runs }).containsExactly(0, 1).inOrder()
        val rows = f.vm.uiState.value.rows
        assertThat(rows.first { it.id == "R2" }.badge).isEqualTo(Badge.FAIL)
        assertThat(rows.first { it.id == "R1" }.badge).isEqualTo(Badge.PENDING)
    }

    @Test
    fun runAllAndRunOneAreIgnoredWhileRunAllIsInProgress() = runTest {
        val gate = CompletableDeferred<Unit>()
        val checks = listOf(FakeCheck(CheckId.R1, CheckStatus.OK, gate), FakeCheck(CheckId.R2, CheckStatus.OK))
        val f = Fixture(this, checks = checks)
        f.vm.onIntent(User.RunAll)
        runCurrent()

        f.vm.onIntent(User.RunAll)
        f.vm.onIntent(User.RunOne(CheckId.R2))
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(f.checks.map { it.runs }).containsExactly(1, 1).inOrder()
    }

    @Test
    fun runOneIsIgnoredWhileTheSameCheckRuns() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(this, checks = listOf(FakeCheck(CheckId.R1, CheckStatus.OK, gate)))
        f.vm.onIntent(User.RunOne(CheckId.R1))
        runCurrent()

        f.vm.onIntent(User.RunOne(CheckId.R1))
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(f.checks.single().runs).isEqualTo(1)
    }

    @Test
    fun selectFilterSwitchesRows() = runTest {
        val f = Fixture(this)
        f.vm.onIntent(User.RunOne(CheckId.R2))
        advanceUntilIdle()

        f.vm.onIntent(User.SelectFilter(LabFilter.PROBLEMS))

        assertThat(f.vm.uiState.value.rows.map { it.id }).containsExactly("R2")
        assertThat(f.vm.uiState.value.tabs.first { it.selected }.filter).isEqualTo(LabFilter.PROBLEMS)
    }

    @Test
    fun exportSuccessShowsPathAndShowsProgressMeanwhile() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(this, writerGate = gate)
        advanceUntilIdle()

        f.vm.onIntent(User.Export)
        runCurrent()
        assertThat(f.vm.uiState.value.isExporting).isTrue()

        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(f.vm.uiState.value.isExporting).isFalse()
        f.vm.effect.test {
            assertThat(awaitItem()).isEqualTo(ShowMessage(LabMessage.Exported("/files/report.json")))
        }
    }

    @Test
    fun exportFailureShowsError() = runTest {
        val f = Fixture(this, writerOutcome = { Outcome.Failure(UnexpectedError("disk full")) })

        f.vm.onIntent(User.Export)
        advanceUntilIdle()

        f.vm.effect.test {
            assertThat(awaitItem()).isEqualTo(ShowMessage(LabMessage.Error("unexpected")))
        }
        assertThat(f.vm.uiState.value.isExporting).isFalse()
    }

    @Test
    fun repeatedExportIsIgnoredWhileExporting() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(this, writerGate = gate)
        f.vm.onIntent(User.Export)
        runCurrent()

        f.vm.onIntent(User.Export)
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(f.writes).isEqualTo(1)
    }

    @Test
    fun sendToMqttWithoutResultSaysThereIsNothingToSend() = runTest {
        val f = Fixture(this)

        f.vm.onIntent(User.SendToMqtt(CheckId.R1))
        advanceUntilIdle()

        f.vm.effect.test { assertThat(awaitItem()).isEqualTo(ShowMessage(LabMessage.NoResult)) }
        assertThat(f.published).isEmpty()
    }

    @Test
    fun sendToMqttWhenNotConfiguredShowsDedicatedMessage() = runTest {
        val f = Fixture(this)
        f.vm.onIntent(User.RunOne(CheckId.R1))
        advanceUntilIdle()

        f.vm.onIntent(User.SendToMqtt(CheckId.R1))
        advanceUntilIdle()

        f.vm.effect.test { assertThat(awaitItem()).isEqualTo(ShowMessage(LabMessage.MqttNotConfigured)) }
        assertThat(f.published.map { it.id }).containsExactly(CheckId.R1)
    }

    @Test
    fun sendToMqttOtherFailureShowsError() = runTest {
        val f = Fixture(this, publisherOutcome = { Outcome.Failure(UnexpectedError("broker down")) })
        f.vm.onIntent(User.RunOne(CheckId.R1))
        advanceUntilIdle()

        f.vm.onIntent(User.SendToMqtt(CheckId.R1))
        advanceUntilIdle()

        f.vm.effect.test { assertThat(awaitItem()).isEqualTo(ShowMessage(LabMessage.Error("unexpected"))) }
    }

    @Test
    fun sendToMqttSuccessConfirms() = runTest {
        val f = Fixture(this, publisherOutcome = { Outcome.Success(Unit) })
        f.vm.onIntent(User.RunOne(CheckId.R1))
        advanceUntilIdle()

        f.vm.onIntent(User.SendToMqtt(CheckId.R1))
        advanceUntilIdle()

        f.vm.effect.test { assertThat(awaitItem()).isEqualTo(ShowMessage(LabMessage.Published(CheckId.R1))) }
    }

    @Test
    fun openSettingsEmitsSystemSettingsEffect() = runTest {
        val f = Fixture(this)

        f.vm.onIntent(User.OpenSettings(SettingsTarget.USAGE_ACCESS))

        f.vm.effect.test { assertThat(awaitItem()).isEqualTo(OpenSystemSettings(SettingsTarget.USAGE_ACCESS)) }
    }

    /** Часы, ломающие первый вызов: use case бросает исключение там, где сам его не перехватывает. */
    private fun clockFailingOnce(failure: Throwable): Clock {
        var calls = 0
        return Clock { if (++calls == 1) throw failure else 1L }
    }

    @Test
    fun throwingExportResetsProgressAndShowsError() = runTest {
        val f = Fixture(this, writerOutcome = { throw SecurityException("denied") })

        f.vm.onIntent(User.Export)
        advanceUntilIdle()

        assertThat(f.vm.uiState.value.isExporting).isFalse()
        f.vm.effect.test { assertThat(awaitItem()).isEqualTo(ShowMessage(LabMessage.Error("denied"))) }
    }

    @Test
    fun strayCancellationInExportResetsProgressAndShowsError() = runTest {
        val f = Fixture(this, writerOutcome = { throw CancellationException("stray") })

        f.vm.onIntent(User.Export)
        advanceUntilIdle()

        assertThat(f.vm.uiState.value.isExporting).isFalse()
        f.vm.effect.test { assertThat(awaitItem()).isEqualTo(ShowMessage(LabMessage.Error("stray"))) }
    }

    @Test
    fun exceptionWithoutMessageIsShownByClassName() = runTest {
        val f = Fixture(this, writerOutcome = { throw IllegalStateException() })

        f.vm.onIntent(User.Export)
        advanceUntilIdle()

        f.vm.effect.test {
            assertThat(awaitItem()).isEqualTo(ShowMessage(LabMessage.Error("IllegalStateException")))
        }
    }

    @Test
    fun destroyedViewModelDuringExportStaysQuiet() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(this, writerGate = gate)
        f.vm.onIntent(User.Export)
        runCurrent()

        f.vm.onDestroy()
        gate.complete(Unit)
        advanceUntilIdle()

        f.vm.effect.test { expectNoEvents() }
    }

    @Test
    fun strayCancellationInRunAllDoesNotLeaveRunAllStuck() = runTest {
        val checks = listOf(
            FakeCheck(CheckId.R1, CheckStatus.OK, failure = CancellationException("stray")),
            FakeCheck(CheckId.R2, CheckStatus.OK),
        )
        val f = Fixture(this, checks = checks)

        f.vm.onIntent(User.RunAll)
        advanceUntilIdle()

        val state = f.vm.uiState.value
        assertThat(state.isRunningAll).isFalse()
        assertThat(state.rows.none { it.isRunning }).isTrue()
        assertThat(state.rows.first { it.id == "R1" }.badge).isEqualTo(Badge.FAIL)
        assertThat(state.rows.first { it.id == "R2" }.badge).isEqualTo(Badge.OK)
    }

    @Test
    fun useCaseFailureInRunAllResetsFlagsKeepsGoingAndReportsOnce() = runTest {
        val checks = listOf(
            FakeCheck(CheckId.R1, CheckStatus.OK, failure = IllegalStateException("boom")),
            FakeCheck(CheckId.R2, CheckStatus.OK),
        )
        val f = Fixture(this, checks = checks, runClock = clockFailingOnce(IllegalStateException("clock")))

        f.vm.onIntent(User.RunAll)
        advanceUntilIdle()

        val state = f.vm.uiState.value
        assertThat(state.isRunningAll).isFalse()
        assertThat(state.rows.none { it.isRunning }).isTrue()
        // сломались часы на первом id каталога (там нет проверки, нужен synthetic-результат): строка без результата
        assertThat(state.rows.first { it.id == CheckId.entries.first().name }.badge).isEqualTo(Badge.PENDING)
        assertThat(state.rows.first { it.id == "R2" }.badge).isEqualTo(Badge.OK)
        f.vm.effect.test {
            assertThat(awaitItem()).isEqualTo(ShowMessage(LabMessage.Error("clock")))
            expectNoEvents()
        }
    }

    @Test
    fun strayCancellationFromUseCaseInRunAllIsAFailureNotAnAbort() = runTest {
        val checks = listOf(
            FakeCheck(CheckId.R1, CheckStatus.OK, failure = IllegalStateException("boom")),
            FakeCheck(CheckId.R2, CheckStatus.OK),
        )
        val f = Fixture(this, checks = checks, runClock = clockFailingOnce(CancellationException("stray")))

        f.vm.onIntent(User.RunAll)
        advanceUntilIdle()

        assertThat(f.vm.uiState.value.isRunningAll).isFalse()
        assertThat(f.vm.uiState.value.rows.first { it.id == "R2" }.badge).isEqualTo(Badge.OK)
    }

    @Test
    fun useCaseFailureInRunOneClearsRunningAndShowsError() = runTest {
        val checks = listOf(FakeCheck(CheckId.R1, CheckStatus.OK, failure = IllegalStateException("boom")))
        val f = Fixture(this, checks = checks, runClock = clockFailingOnce(IllegalStateException("clock")))

        f.vm.onIntent(User.RunOne(CheckId.R1))
        advanceUntilIdle()

        assertThat(f.vm.uiState.value.rows.first { it.id == "R1" }.isRunning).isFalse()
        f.vm.effect.test { assertThat(awaitItem()).isEqualTo(ShowMessage(LabMessage.Error("clock"))) }
    }

    @Test
    fun throwingPublisherShowsErrorInsteadOfCrashing() = runTest {
        val f = Fixture(this, publisherOutcome = { throw IllegalStateException("mqtt down") })
        f.vm.onIntent(User.RunOne(CheckId.R1))
        advanceUntilIdle()

        f.vm.onIntent(User.SendToMqtt(CheckId.R1))
        advanceUntilIdle()

        f.vm.effect.test { assertThat(awaitItem()).isEqualTo(ShowMessage(LabMessage.Error("mqtt down"))) }
    }

    @Test
    fun exportIsIgnoredWhileRunAllIsInProgress() = runTest {
        val gate = CompletableDeferred<Unit>()
        val checks = listOf(FakeCheck(CheckId.R1, CheckStatus.OK, gate))
        val f = Fixture(this, checks = checks)
        f.vm.onIntent(User.RunAll)
        runCurrent()

        f.vm.onIntent(User.Export)
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(f.writes).isEqualTo(0)
        assertThat(f.vm.uiState.value.isExporting).isFalse()
    }

    @Test
    fun unhandledExceptionHandlerLogsInsteadOfCrashing() = runTest {
        val logged = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + labExceptionHandler { logged += it })

        scope.launch(Dispatchers.Unconfined) { throw IllegalStateException("late") }.join()

        assertThat(logged.single()).hasMessageThat().isEqualTo("late")
    }
}
