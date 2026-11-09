package ru.restofleet.agent.presentation.lab.viewmodel

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.agent.domain.port.ForeignPublishResult
import ru.restofleet.agent.domain.port.ForeignTopicPublisher
import ru.restofleet.agent.domain.usecase.ExportCapabilityReport
import ru.restofleet.agent.domain.usecase.GenerateDebugEvents
import ru.restofleet.agent.domain.usecase.GetDeviceInfo
import ru.restofleet.agent.domain.usecase.PublishCheckResult
import ru.restofleet.agent.domain.usecase.RunCapabilityChecks
import ru.restofleet.agent.presentation.lab.viewmodel.LabEffect.ShowMessage
import ru.restofleet.agent.presentation.lab.viewmodel.LabIntent.User
import ru.restofleet.agent.presentation.lab.viewmodel.model.LabFilter
import ru.restofleet.agent.presentation.support.MemoryJournal
import ru.restofleet.shared.kernel.Clock

/** Кнопки отладки M2/M7: есть только если им передали реализации (debug-сборка), иначе их нет вовсе. */
class LabDebugViewModelTest {
    private val clock = Clock { 1L }
    private val device = DeviceInfo(36, "P", true, AgentMode.NORMAL, "0.1")

    private fun TestScope.vm(
        events: GenerateDebugEvents?,
        foreign: ForeignTopicPublisher?,
    ): LabViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val run = RunCapabilityChecks(emptyList(), clock)
        return LabViewModel(
            runChecks = run,
            exportReport = ExportCapabilityReport(run, { device }, { error("no") }, clock, MemoryJournal()),
            publishResult = PublishCheckResult { error("no") },
            getDeviceInfo = GetDeviceInfo { device },
            runCommand = null,
            debugEvents = events,
            foreignTopic = foreign,
            io = dispatcher,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )
    }

    private val events = mockk<GenerateDebugEvents>()

    @Test
    fun withoutDebugToolsThereAreNoDebugButtonsAndTheIntentsDoNothing() = runTest {
        val vm = vm(null, null)
        advanceUntilIdle()

        assertThat(vm.uiState.value.debugTools).isFalse()
        vm.effect.test {
            vm.onIntent(User.PublishForeignTopic)
            vm.onIntent(User.GenerateDebugEvents)
            advanceUntilIdle()
            expectNoEvents()
        }
    }

    @Test
    fun withDebugToolsTheButtonsAreOnTheAllTabOnly() = runTest {
        val vm = vm(events, ForeignTopicPublisher { ForeignPublishResult(false, "x") })
        advanceUntilIdle()
        assertThat(vm.uiState.value.debugTools).isTrue()

        vm.onIntent(User.SelectFilter(LabFilter.PROBLEMS))
        advanceUntilIdle()

        assertThat(vm.uiState.value.debugTools).isFalse()
    }

    @Test
    fun foreignTopicShowsThePubackReason() = runTest {
        val vm = vm(null, ForeignTopicPublisher { ForeignPublishResult(false, "0x87 not_authorized") })
        advanceUntilIdle()

        vm.effect.test {
            vm.onIntent(User.PublishForeignTopic)
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(ShowMessage(LabMessage.ForeignTopic(false, "0x87 not_authorized")))
        }
    }

    @Test
    fun foreignTopicFailureBecomesAnErrorMessage() = runTest {
        val vm = vm(null, ForeignTopicPublisher { throw IllegalStateException("boom") })
        advanceUntilIdle()

        vm.effect.test {
            vm.onIntent(User.PublishForeignTopic)
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(ShowMessage(LabMessage.Error("boom")))
        }
    }

    @Test
    fun generateRunsFiveHundredEventsAndReportsTheBatch() = runTest {
        coEvery { events(500) } returns "b-1"
        val vm = vm(events, null)
        advanceUntilIdle()

        vm.effect.test {
            vm.onIntent(User.GenerateDebugEvents)
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(ShowMessage(LabMessage.DebugEventsSent(500, "b-1")))
        }
        coVerify(exactly = 1) { events(500) }
    }

    @Test
    fun generateFailureBecomesAnErrorMessage() = runTest {
        coEvery { events(500) } throws IllegalStateException("db")
        val vm = vm(events, null)
        advanceUntilIdle()

        vm.effect.test {
            vm.onIntent(User.GenerateDebugEvents)
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(ShowMessage(LabMessage.Error("db")))
        }
    }
}
