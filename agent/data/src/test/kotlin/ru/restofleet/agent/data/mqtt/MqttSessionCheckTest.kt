package ru.restofleet.agent.data.mqtt

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.shared.kernel.Clock

class MqttSessionCheckTest {
    private val status = MutableStateFlow(SessionStatus(SessionState.DISCONNECTED))
    private val check = MqttSessionCheck(status, Clock { 99 })

    @Test
    fun connectedIsOkWithEndpointAndConnId() = runTest {
        status.value = SessionStatus(SessionState.CONNECTED, null, "10.0.2.2:8883", "k-1")

        val result = check.run()

        assertThat(result.id).isEqualTo(CheckId.M1)
        assertThat(result.status).isEqualTo(CheckStatus.OK)
        assertThat(result.raw).containsExactly("endpoint", "10.0.2.2:8883", "connId", "k-1")
        assertThat(result.checkedAt).isEqualTo(99)
    }

    @Test
    fun missingCredentialsOrCaAreNotCheckedWithTheCode() = runTest {
        status.value = SessionStatus(SessionState.DISCONNECTED, MqttError.NotConfigured)
        val noCreds = check.run()
        status.value = SessionStatus(SessionState.DISCONNECTED, MqttError.CaMissing, "10.0.2.2:8883")
        val noCa = check.run()

        assertThat(noCreds.status).isEqualTo(CheckStatus.NOT_CHECKED)
        assertThat(noCreds.raw["error"]).isEqualTo("not_configured")
        assertThat(noCa.status).isEqualTo(CheckStatus.NOT_CHECKED)
        assertThat(noCa.raw["error"]).isEqualTo("ca_missing")
    }

    @Test
    fun tlsAuthAndInvalidCaAreUnavailableWithTheCode() = runTest {
        listOf(MqttError.TlsError, MqttError.AuthFailed, MqttError.CaInvalid, MqttError.Failed("ConnectException"))
            .forEach { error ->
                status.value = SessionStatus(SessionState.CONNECTING, error, "10.0.2.2:8883")

                val result = check.run()

                assertThat(result.status).isEqualTo(CheckStatus.UNAVAILABLE)
                assertThat(result.raw["error"]).isEqualTo(error.code)
                assertThat(result.raw["endpoint"]).isEqualTo("10.0.2.2:8883")
            }
    }

    @Test
    fun connectedButOnlineOrSubscribeFailedIsLimited() = runTest {
        status.value = SessionStatus(SessionState.CONNECTED, MqttError.NotAuthorized, "10.0.2.2:8883", "k-1")

        val result = check.run()

        assertThat(result.status).isEqualTo(CheckStatus.LIMITED)
        assertThat(result.raw["error"]).isEqualTo("not_authorized")
    }

    @Test
    fun notYetConnectedWithoutErrorIsNotChecked() = runTest {
        status.value = SessionStatus(SessionState.CONNECTING, null, "10.0.2.2:8883")

        assertThat(check.run().status).isEqualTo(CheckStatus.NOT_CHECKED)
    }
}
