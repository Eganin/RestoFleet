package ru.restofleet.agent.data.mqtt

import com.google.common.truth.Truth.assertThat
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5ConnAckException
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5DisconnectException
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5PubAckException
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5SubAckException
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAck
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAckReasonCode
import com.hivemq.client.mqtt.mqtt5.message.disconnect.Mqtt5Disconnect
import com.hivemq.client.mqtt.mqtt5.message.disconnect.Mqtt5DisconnectReasonCode
import com.hivemq.client.mqtt.mqtt5.message.publish.puback.Mqtt5PubAck
import com.hivemq.client.mqtt.mqtt5.message.publish.puback.Mqtt5PubAckReasonCode
import com.hivemq.client.mqtt.mqtt5.message.subscribe.suback.Mqtt5SubAck
import com.hivemq.client.mqtt.mqtt5.message.subscribe.suback.Mqtt5SubAckReasonCode
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import ru.restofleet.agent.domain.error.MessageRejected
import java.io.IOException
import java.net.ConnectException
import java.security.cert.CertificateException
import java.util.concurrent.CompletionException
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
    fun pubAckQuotaExceededIsFinalRejection() {
        assertThat(MqttErrorMapper.fromPublishAck("QUOTA_EXCEEDED", 0x97, isError = true))
            .isEqualTo(MqttError.Rejected("quota_exceeded"))
    }

    // I1: HiveMQ 1.4.2 завершает future публикации исключением Mqtt5PubAckException, а не результатом с кодом
    private fun pubAckException(code: Mqtt5PubAckReasonCode): Mqtt5PubAckException {
        val ack = mockk<Mqtt5PubAck> { every { reasonCode } returns code }
        return Mqtt5PubAckException(ack, "PUBACK contained an Error Code")
    }

    private fun subAckException(vararg codes: Mqtt5SubAckReasonCode): Mqtt5SubAckException {
        val ack = mockk<Mqtt5SubAck> { every { reasonCodes } returns codes.toList() }
        return Mqtt5SubAckException(ack, "SUBACK contains only Error Codes")
    }

    @Test
    fun pubAckExceptionNotAuthorizedIsNotAuthorizedNotConnectFailed() {
        val error = MqttErrorMapper.fromConnect(pubAckException(Mqtt5PubAckReasonCode.NOT_AUTHORIZED))

        assertThat(error).isEqualTo(MqttError.NotAuthorized)
        assertThat(error.code).isEqualTo("not_authorized")
        assertThat(error).isInstanceOf(MessageRejected::class.java)
    }

    @Test
    fun pubAckExceptionWrappedInCompletionExceptionIsFound() {
        val wrapped = CompletionException(pubAckException(Mqtt5PubAckReasonCode.NOT_AUTHORIZED))

        assertThat(MqttErrorMapper.fromConnect(wrapped)).isEqualTo(MqttError.NotAuthorized)
    }

    @Test
    fun finalPubAckCodesAreRejectedWithTheirName() {
        val expected = mapOf(
            Mqtt5PubAckReasonCode.TOPIC_NAME_INVALID to "puback_topic_name_invalid",
            Mqtt5PubAckReasonCode.QUOTA_EXCEEDED to "puback_quota_exceeded",
            Mqtt5PubAckReasonCode.PAYLOAD_FORMAT_INVALID to "puback_payload_format_invalid",
        )
        for ((code, name) in expected) {
            val error = MqttErrorMapper.fromConnect(pubAckException(code))

            assertThat(error.code).isEqualTo(name)
            assertThat(error).isInstanceOf(MessageRejected::class.java)
        }
    }

    @Test
    fun transientPubAckCodesStayRetryableFailures() {
        val expected = mapOf(
            Mqtt5PubAckReasonCode.UNSPECIFIED_ERROR to "puback_unspecified_error",
            Mqtt5PubAckReasonCode.IMPLEMENTATION_SPECIFIC_ERROR to "puback_implementation_specific_error",
            Mqtt5PubAckReasonCode.PACKET_IDENTIFIER_IN_USE to "puback_packet_identifier_in_use",
        )
        for ((code, detail) in expected) {
            val error = MqttErrorMapper.fromConnect(pubAckException(code))

            assertThat(error).isEqualTo(MqttError.Failed(detail))
            assertThat(error).isNotInstanceOf(MessageRejected::class.java)
        }
    }

    @Test
    fun subAckExceptionWithNotAuthorizedAmongCodesIsNotAuthorized() {
        val error = MqttErrorMapper.fromConnect(
            subAckException(Mqtt5SubAckReasonCode.TOPIC_FILTER_INVALID, Mqtt5SubAckReasonCode.NOT_AUTHORIZED),
        )

        assertThat(error).isEqualTo(MqttError.NotAuthorized)
    }

    @Test
    fun subAckExceptionWithOtherErrorsKeepsTheFirstName() {
        val error = MqttErrorMapper.fromConnect(subAckException(Mqtt5SubAckReasonCode.TOPIC_FILTER_INVALID))

        assertThat(error).isEqualTo(MqttError.Failed("suback_topic_filter_invalid"))
    }

    @Test
    fun subAckCodesOfAPartlyRefusedSubscription() {
        assertThat(MqttErrorMapper.fromSubAck(listOf(Mqtt5SubAckReasonCode.GRANTED_QOS_1))).isNull()
        assertThat(
            MqttErrorMapper.fromSubAck(
                listOf(
                    Mqtt5SubAckReasonCode.GRANTED_QOS_1,
                    Mqtt5SubAckReasonCode.QUOTA_EXCEEDED,
                    Mqtt5SubAckReasonCode.NOT_AUTHORIZED,
                ),
            ),
        ).isEqualTo(MqttError.NotAuthorized)
    }

    // M17: DISCONNECT 0x8E от брокера — свой код, чтобы переподключаться с длинной паузой
    private fun serverDisconnect(code: Mqtt5DisconnectReasonCode): Mqtt5DisconnectException {
        val disconnect = mockk<Mqtt5Disconnect> { every { reasonCode } returns code }
        return Mqtt5DisconnectException(disconnect, "Server sent DISCONNECT")
    }

    @Test
    fun sessionTakenOverIsItsOwnRetryableCode() {
        val error = MqttErrorMapper.fromConnect(serverDisconnect(Mqtt5DisconnectReasonCode.SESSION_TAKEN_OVER))

        assertThat(error).isEqualTo(MqttError.SessionTakenOver)
        assertThat(error.code).isEqualTo("session_taken_over")
        assertThat(error.retryable).isTrue()
    }

    @Test
    fun otherServerDisconnectKeepsOnlyTheClassName() {
        val error = MqttErrorMapper.fromConnect(serverDisconnect(Mqtt5DisconnectReasonCode.SERVER_SHUTTING_DOWN))

        assertThat(error).isEqualTo(MqttError.Failed("Mqtt5DisconnectException"))
    }
}
