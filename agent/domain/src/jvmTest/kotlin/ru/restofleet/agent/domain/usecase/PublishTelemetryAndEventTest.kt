package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.fake.FakeBrokerSession
import ru.restofleet.agent.domain.fake.FakeMessagePublisher
import ru.restofleet.agent.domain.fake.FakeOutbox
import ru.restofleet.agent.domain.fake.FakePayloadEncoder
import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.TelemetrySnapshot
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.shared.kernel.Clock

class PublishTelemetryAndEventTest {
    private val session = FakeBrokerSession()
    private val publisher = FakeMessagePublisher()
    private val outbox = FakeOutbox()
    private val publish = PublishOrBuffer(session, publisher, outbox)
    private val encoder = FakePayloadEncoder()
    private val clock = Clock { 777 }

    private fun snapshot(seq: Long) = TelemetrySnapshot(
        ts = 1, seq = seq, battery = null, thermal = null, network = null, resources = null,
        foregroundApp = null, apps = emptyList(), unavailable = emptyMap(),
    )

    @Test
    fun telemetryGoesOutAsQosZeroWithoutRetain() = runTest {
        PublishTelemetry(publish, encoder, clock)(snapshot(5))

        val sent = publisher.published.single()
        assertThat(sent.kind).isEqualTo(MessageKind.TELEMETRY)
        assertThat(sent.qos).isEqualTo(0)
        assertThat(sent.retain).isFalse()
        assertThat(String(sent.payload)).isEqualTo("telemetry:5")
        assertThat(sent.createdAt).isEqualTo(777)
    }

    @Test
    fun offlineTelemetryIsBufferedWithQosOne() = runTest {
        session.state.value = SessionState.DISCONNECTED

        PublishTelemetry(publish, encoder, clock)(snapshot(6))

        assertThat(publisher.calls).isEqualTo(0)
        assertThat(outbox.messages.single().qos).isEqualTo(1)
        assertThat(outbox.messages.single().kind).isEqualTo(MessageKind.TELEMETRY)
    }

    @Test
    fun eventGoesOutAsQosOneWithoutRetain() = runTest {
        PublishEvent(publish, encoder, clock)(AgentEvent.Lifecycle(9, AgentEvent.Lifecycle.Phase.START))

        val sent = publisher.published.single()
        assertThat(sent.kind).isEqualTo(MessageKind.EVENT)
        assertThat(sent.qos).isEqualTo(1)
        assertThat(sent.retain).isFalse()
        assertThat(String(sent.payload)).isEqualTo("event:9")
    }

    @Test
    fun offlineEventIsBuffered() = runTest {
        session.state.value = SessionState.CONNECTING

        PublishEvent(publish, encoder, clock)(AgentEvent.Lifecycle(9, AgentEvent.Lifecycle.Phase.STOP))

        assertThat(outbox.messages.map { it.kind }).containsExactly(MessageKind.EVENT)
    }
}
