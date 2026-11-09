package ru.restofleet.agent.data.mqtt

import com.google.common.truth.Truth.assertThat
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5ConnAckException
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAck
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAckReasonCode
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class MqttErrorMapperTest {
    private fun connAck(code: Mqtt5ConnAckReasonCode): Mqtt5ConnAckException {
        val ack = mockk<Mqtt5ConnAck> { every { reasonCode } returns code }
        return Mqtt5ConnAckException(ack, "refused")
    }

    @Test
    fun handshakeFailureIsTlsError() {
        assertThat(MqttErrorMapper.fromConnect(SSLHandshakeException("bad cert"))).isEqualTo(MqttError.TlsError)
    }

    @Test
    fun hostnameMismatchIsTlsError() {
        assertThat(MqttErrorMapper.fromConnect(SSLPeerUnverifiedException("no SAN"))).isEqualTo(MqttError.TlsError)
    }

    @Test
    fun tlsErrorHiddenInCauseChainIsFound() {
        val wrapped = IOException("connect failed", RuntimeException("x", CertificateException("untrusted")))

        assertThat(MqttErrorMapper.fromConnect(wrapped)).isEqualTo(MqttError.TlsError)
    }

    @Test
    fun badCredentialsAreAuthFailed() {
        assertThat(MqttErrorMapper.fromConnect(connAck(Mqtt5ConnAckReasonCode.BAD_USER_NAME_OR_PASSWORD)))
            .isEqualTo(MqttError.AuthFailed)
    }

    @Test
    fun notAuthorizedConnAckIsAuthFailed() {
        assertThat(MqttErrorMapper.fromConnect(connAck(Mqtt5ConnAckReasonCode.NOT_AUTHORIZED)))
            .isEqualTo(MqttError.AuthFailed)
    }

    @Test
    fun otherConnAckKeepsOnlyTheReasonName() {
        val error = MqttErrorMapper.fromConnect(connAck(Mqtt5ConnAckReasonCode.SERVER_BUSY))

        assertThat(error).isEqualTo(MqttError.Failed("connack_server_busy"))
    }

    @Test
    fun networkFailureNeverLeaksTheMessage() {
        val error =
            MqttErrorMapper.fromConnect(ConnectException("Connection refused: 10.0.2.2:8883 user=dev pw=hunter2"))

        assertThat(error).isEqualTo(MqttError.Failed("ConnectException"))
        assertThat(error.toString()).doesNotContain("hunter2")
    }

    @Test
    fun authAndCredentialErrorsAreNotRetried() {
        assertThat(MqttError.AuthFailed.retryable).isFalse()
        assertThat(MqttError.NotConfigured.retryable).isFalse()
        assertThat(MqttError.CaInvalid.retryable).isFalse()
        assertThat(MqttError.CaMissing.retryable).isFalse()
        assertThat(MqttError.TlsError.retryable).isTrue()
        assertThat(MqttError.Failed("x").retryable).isTrue()
    }

    @Test
    fun pubAckNotAuthorizedIsMapped() {
        assertThat(MqttErrorMapper.fromPublishAck("NOT_AUTHORIZED", 0x87, isError = true))
            .isEqualTo(MqttError.NotAuthorized)
    }

    @Test
    fun pubAckSuccessIsNoError() {
        assertThat(MqttErrorMapper.fromPublishAck("SUCCESS", 0, isError = false)).isNull()
    }

    @Test
    fun pubAckOtherErrorKeepsName() {
        assertThat(MqttErrorMapper.fromPublishAck("QUOTA_EXCEEDED", 0x97, isError = true))
            .isEqualTo(MqttError.Failed("puback_quota_exceeded"))
    }
}
