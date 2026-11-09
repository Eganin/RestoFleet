package ru.restofleet.agent.data.mqtt

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.data.mqtt.DisconnectPolicy.Action
import kotlin.random.Random

class DisconnectPolicyTest {
    private val policy = DisconnectPolicy(Random(seed = 7))

    @Test
    fun ownCloseStopsSilently() {
        assertThat(policy.decide(MqttError.Failed("x"), closing = true, connectedOnce = true, attempts = 0))
            .isEqualTo(Action.Stop(null))
    }

    @Test
    fun failedFirstConnectIsLeftToTheSession() {
        assertThat(policy.decide(MqttError.TlsError, closing = false, connectedOnce = false, attempts = 0))
            .isEqualTo(Action.Stop(null))
    }

    @Test
    fun credentialRefusalAfterConnectStopsAndClosesTheLink() {
        assertThat(policy.decide(MqttError.AuthFailed, closing = false, connectedOnce = true, attempts = 1))
            .isEqualTo(Action.Stop(LinkEvent.Closed(MqttError.AuthFailed)))
    }

    @Test
    fun ordinaryLossReconnectsWithTheClientPauseAndReportsOnlyTheFirstAttempt() {
        val error = MqttError.Failed("IOException")

        assertThat(policy.decide(error, closing = false, connectedOnce = true, attempts = 0))
            .isEqualTo(Action.Reconnect(null, LinkEvent.Lost(error)))
        assertThat(policy.decide(error, closing = false, connectedOnce = true, attempts = 3))
            .isEqualTo(Action.Reconnect(null, null))
    }

    @Test
    fun sessionTakenOverReconnectsAfterFiveMinutesWithJitter() {
        val delays = (1..20).map {
            val action = policy.decide(MqttError.SessionTakenOver, closing = false, connectedOnce = true, attempts = 0)
            assertThat(action.notify).isEqualTo(LinkEvent.Lost(MqttError.SessionTakenOver))
            (action as Action.Reconnect).delayMs!!
        }

        assertThat(delays.min()).isAtLeast(5 * 60_000L)
        assertThat(delays.max()).isAtMost(6 * 60_000L)
        assertThat(delays.toSet().size).isGreaterThan(1)
    }
}
