package ru.restofleet.agent.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OutgoingMessageTest {
    private fun message(
        payload: ByteArray = byteArrayOf(1, 2, 3),
        correlation: ByteArray? = byteArrayOf(9),
        qos: Int = 1,
    ) = OutgoingMessage(
        kind = MessageKind.COMMAND_RESULT,
        payload = payload,
        qos = qos,
        correlationData = correlation,
        responseTopic = "rf/v1/dev/x/cmd/res",
        createdAt = 100L,
    )

    @Test
    fun messagesWithEqualContentAndDifferentArraysAreEqual() {
        val a = message()
        val b = message(payload = byteArrayOf(1, 2, 3), correlation = byteArrayOf(9))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun differentPayloadMakesMessagesDifferent() {
        assertThat(message()).isNotEqualTo(message(payload = byteArrayOf(1, 2, 4)))
    }

    @Test
    fun differentCorrelationDataMakesMessagesDifferent() {
        assertThat(message()).isNotEqualTo(message(correlation = byteArrayOf(8)))
        assertThat(message()).isNotEqualTo(message(correlation = null))
        assertThat(message(correlation = null)).isEqualTo(message(correlation = null))
    }

    @Test
    fun differentScalarFieldMakesMessagesDifferent() {
        assertThat(message()).isNotEqualTo(message(qos = 0))
    }

    @Test
    fun importantKindsHaveLowerPriorityNumberThanTelemetry() {
        assertThat(MessageKind.EVENT.priority).isLessThan(MessageKind.PROBE.priority)
        assertThat(MessageKind.PROBE.priority).isLessThan(MessageKind.TELEMETRY.priority)
        assertThat(MessageKind.COMMAND_RESULT.priority).isEqualTo(0)
    }
}
