package ru.restofleet.shared.probes

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.shared.probes.ProbeErrorClass.CONN_REFUSED
import ru.restofleet.shared.probes.ProbeErrorClass.TIMEOUT
import ru.restofleet.shared.probes.ProbeState.DOWN
import ru.restofleet.shared.probes.ProbeState.UNKNOWN
import ru.restofleet.shared.probes.ProbeState.UP

class ProbeStateMachineTest {

    @Test
    fun startsUnknownWithDefaultThreshold() {
        val machine = ProbeStateMachine()
        assertThat(machine.state).isEqualTo(UNKNOWN)
        assertThat(machine.consecutiveFailures).isEqualTo(0)
        assertThat(machine.failThreshold).isEqualTo(3)
    }

    @Test
    fun firstSuccessFromUnknownIsTransitionToUp() {
        val machine = ProbeStateMachine(3)
        assertThat(machine.onSuccess()).isEqualTo(Transition(UNKNOWN, UP, failures = 0, error = null))
        assertThat(machine.onSuccess()).isNull()
        assertThat(machine.state).isEqualTo(UP)
    }

    @Test
    fun kConsecutiveFailuresGoDownOnce() {
        val machine = ProbeStateMachine(3)
        machine.onSuccess()

        assertThat(machine.onFailure(TIMEOUT)).isNull()
        assertThat(machine.onFailure(TIMEOUT)).isNull()
        assertThat(machine.state).isEqualTo(UP)
        assertThat(machine.onFailure(CONN_REFUSED)).isEqualTo(Transition(UP, DOWN, failures = 3, error = CONN_REFUSED))
        assertThat(machine.onFailure(TIMEOUT)).isNull()
        assertThat(machine.consecutiveFailures).isEqualTo(4)
        assertThat(machine.state).isEqualTo(DOWN)
    }

    @Test
    fun successResetsCounterAndRecoversFromDown() {
        val machine = ProbeStateMachine(2)
        machine.onFailure(TIMEOUT)
        assertThat(machine.onFailure(TIMEOUT)).isEqualTo(Transition(UNKNOWN, DOWN, failures = 2, error = TIMEOUT))

        assertThat(machine.onSuccess()).isEqualTo(Transition(DOWN, UP, failures = 0, error = null))
        assertThat(machine.consecutiveFailures).isEqualTo(0)
    }

    @Test
    fun interruptedFailureStreakDoesNotGoDown() {
        val machine = ProbeStateMachine(3)
        val transitions = listOf(false, false, true, false, false, true).mapNotNull { ok ->
            machine.record(ok, if (ok) null else TIMEOUT)
        }
        assertThat(transitions).containsExactly(Transition(UNKNOWN, UP, failures = 0, error = null))
    }

    @Test
    fun failureWithoutClassIsOther() {
        val machine = ProbeStateMachine(1)
        assertThat(machine.record(ok = false, error = null)?.error).isEqualTo(ProbeErrorClass.OTHER)
    }

    @Test
    fun thresholdBelowOneIsCoercedToOne() {
        val machine = ProbeStateMachine(0)
        assertThat(machine.failThreshold).isEqualTo(1)
        assertThat(machine.onFailure(TIMEOUT)?.to).isEqualTo(DOWN)
    }

    @Test
    fun wireCodesOfStates() {
        assertThat(ProbeState.entries.map { it.code }).containsExactly("unknown", "up", "down").inOrder()
    }
}
