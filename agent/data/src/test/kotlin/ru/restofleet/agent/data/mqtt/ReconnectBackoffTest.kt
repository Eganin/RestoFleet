package ru.restofleet.agent.data.mqtt

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun doublesFromOneSecondUpToTwoMinutes() {
        val backoff = ReconnectBackoff()

        val delays = List(10) { backoff.next() }

        assertThat(delays).containsExactly(
            1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 64_000L, 120_000L, 120_000L, 120_000L,
        ).inOrder()
    }

    @Test
    fun resetStartsOver() {
        val backoff = ReconnectBackoff()
        repeat(4) { backoff.next() }

        backoff.reset()

        assertThat(backoff.next()).isEqualTo(1_000L)
    }
}

class ReconnectPausesTest {
    private val minute = 60_000L

    @Test
    fun authFailureIsRetriedRarelyFromFifteenUpToSixtyMinutes() {
        val pauses = ReconnectPauses()

        val delays = List(5) { pauses.next(MqttError.AuthFailed) }

        assertThat(delays).containsExactly(15 * minute, 30 * minute, 60 * minute, 60 * minute, 60 * minute).inOrder()
    }

    @Test
    fun retryableErrorsUseTheRegularBackoffIndependentlyOfAuth() {
        val pauses = ReconnectPauses()
        pauses.next(MqttError.AuthFailed)

        assertThat(pauses.next(MqttError.TlsError)).isEqualTo(1_000L)
        assertThat(pauses.next(MqttError.CredentialsUnavailable)).isEqualTo(2_000L)
        assertThat(pauses.next(MqttError.AuthFailed)).isEqualTo(30 * minute)
    }

    @Test
    fun provisioningErrorsWaitForCredentialChangeOnly() {
        val pauses = ReconnectPauses()

        assertThat(pauses.next(MqttError.NotConfigured)).isNull()
        assertThat(pauses.next(MqttError.CaMissing)).isNull()
        assertThat(pauses.next(MqttError.CaInvalid)).isNull()
    }

    @Test
    fun resetStartsBothSequencesOver() {
        val pauses = ReconnectPauses()
        repeat(3) {
            pauses.next(MqttError.AuthFailed)
            pauses.next(MqttError.TlsError)
        }

        pauses.reset()

        assertThat(pauses.next(MqttError.AuthFailed)).isEqualTo(15 * minute)
        assertThat(pauses.next(MqttError.TlsError)).isEqualTo(1_000L)
    }
}
