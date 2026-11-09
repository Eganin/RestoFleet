package ru.restofleet.agent.data.mqtt

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.agent.domain.model.AgentEvent.Lifecycle.Phase
import ru.restofleet.agent.domain.usecase.ApplyDesiredShadow
import ru.restofleet.agent.domain.usecase.FlushOutbox
import ru.restofleet.agent.domain.usecase.HandleIncomingCommand
import ru.restofleet.agent.domain.usecase.PublishEvent
import ru.restofleet.shared.kernel.Clock

class SessionRuntimeTest {
    private val trace = mutableListOf<String>()
    private val session = ScriptedSession(trace)
    private val publishEvent = mockk<PublishEvent>().also { pe ->
        coEvery { pe(any()) } coAnswers { trace += "event:${(firstArg<AgentEvent>() as AgentEvent.Lifecycle).phase}" }
    }

    private fun TestScopeRuntime(scope: kotlinx.coroutines.CoroutineScope): SessionRuntime {
        val supervisor = SessionSupervisor(
            scope, session, MutableStateFlow(0), mockk<HandleIncomingCommand>(), mockk<ApplyDesiredShadow>(),
            mockk<FlushOutbox>(), MemoryOutbox(),
        )
        val pump = EventPump(scope, { kotlinx.coroutines.flow.emptyFlow() }, { kotlinx.coroutines.flow.emptyFlow() },
            { emptySet() }, publishEvent, Clock { 1 })
        return SessionRuntime(supervisor, pump, publishEvent, Clock { 42 })
    }

    @Test
    fun startPublishesBootEventForBootReasonAndStartsSession() = runTest {
        val runtime = TestScopeRuntime(backgroundScope)

        runtime.start("boot")
        runCurrent()

        assertThat(trace).contains("event:BOOT")
        assertThat(session.connects).isEqualTo(1)
    }

    @Test
    fun otherReasonsPublishStart() = runTest {
        val runtime = TestScopeRuntime(backgroundScope)

        runtime.start("watchdog")
        runCurrent()

        assertThat(trace).contains("event:START")
        assertThat(trace).doesNotContain("event:BOOT")
    }

    @Test
    fun stopPublishesStopEventBeforeTheSessionGoesOffline() = runTest {
        val runtime = TestScopeRuntime(backgroundScope)
        runtime.start("manual")
        runCurrent()

        runtime.stop()

        assertThat(trace.filter { it == "event:STOP" || it == "disconnect" })
            .containsExactly("event:STOP", "disconnect").inOrder()
        assertThat(Phase.entries).contains(Phase.STOP)
    }

    @Test
    fun stopWithoutStartIsSafe() = runTest {
        TestScopeRuntime(backgroundScope).stop()

        assertThat(session.disconnects).isEqualTo(0)
    }

    @Test
    fun failingEventPublicationDoesNotPreventStartOrStop() = runTest {
        coEvery { publishEvent(any()) } throws IllegalStateException("db")
        val runtime = TestScopeRuntime(backgroundScope)

        runtime.start("boot")
        runCurrent()
        runtime.stop()

        assertThat(session.connects).isEqualTo(1)
        assertThat(session.disconnects).isEqualTo(1)
    }
}
