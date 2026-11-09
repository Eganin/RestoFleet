package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.fake.CapturingPayloadEncoder
import ru.restofleet.agent.domain.fake.FakeBrokerSession
import ru.restofleet.agent.domain.fake.FakeMessagePublisher
import ru.restofleet.agent.domain.fake.FakeOutbox
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.ProbePhases
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.shared.kernel.Clock

class PublishProbeResultTest {
    private val session = FakeBrokerSession()
    private val publisher = FakeMessagePublisher()
    private val outbox = FakeOutbox()
    private val encoder = CapturingPayloadEncoder()
    private val sut = PublishProbeResult(PublishOrBuffer(session, publisher, outbox), encoder, Clock { 321 })
    private val result = ProbeResult("api", true, 200, null, 40, ProbePhases(1, 2, 3, 4), 100)

    @Test
    fun reportGoesOutAsQosZeroProbeMessageWithoutRetain() = runTest {
        sut(listOf(result))

        val sent = publisher.published.single()
        assertThat(sent.kind).isEqualTo(MessageKind.PROBE)
        assertThat(sent.qos).isEqualTo(0)
        assertThat(sent.retain).isFalse()
        assertThat(sent.createdAt).isEqualTo(321)
        assertThat(encoder.probeReports).containsExactly(listOf(result))
    }

    @Test
    fun offlineReportIsBufferedWithQosOne() = runTest {
        session.state.value = SessionState.DISCONNECTED

        sut(listOf(result))

        assertThat(publisher.calls).isEqualTo(0)
        assertThat(outbox.messages.single().kind).isEqualTo(MessageKind.PROBE)
        assertThat(outbox.messages.single().qos).isEqualTo(1)
    }
}
