package ru.restofleet.agent.domain.report

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.fake.CapturingPayloadEncoder
import ru.restofleet.agent.domain.fake.FakeBrokerSession
import ru.restofleet.agent.domain.fake.FakeMessagePublisher
import ru.restofleet.agent.domain.fake.FakeOutbox
import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.agent.domain.usecase.PublishEvent
import ru.restofleet.agent.domain.usecase.PublishOrBuffer
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome

class EventCheckResultPublisherTest {
    private val session = FakeBrokerSession()
    private val publisher = FakeMessagePublisher()
    private val outbox = FakeOutbox()
    private val encoder = CapturingPayloadEncoder()
    private val clock = Clock { 5_000L }
    private val sut = EventCheckResultPublisher(
        PublishEvent(PublishOrBuffer(session, publisher, outbox), encoder, clock),
        clock,
    )

    private val r1 = CheckResult(CheckId.R1, CheckStatus.OK, mapOf("level" to "87"), "ok", 1L)

    @Test
    fun resultGoesOutAsCapabilityCheckEventThroughPublishOrBuffer() = runTest {
        val outcome = sut.publish(r1)

        assertThat(outcome).isEqualTo(Outcome.Success(Unit))
        assertThat(encoder.events).containsExactly(AgentEvent.CapabilityChecked(5_000L, r1))
        val sent = publisher.published.single()
        assertThat(sent.kind).isEqualTo(MessageKind.EVENT)
        assertThat(sent.qos).isEqualTo(1)
    }

    @Test
    fun offlineResultWaitsInTheOutbox() = runTest {
        session.state.value = SessionState.DISCONNECTED

        val outcome = sut.publish(r1)

        assertThat(outcome).isEqualTo(Outcome.Success(Unit))
        assertThat(publisher.calls).isEqualTo(0)
        assertThat(outbox.messages.single().kind).isEqualTo(MessageKind.EVENT)
    }

    @Test
    fun maskedSerialOfR13IsPassedAsIsAndNothingIsAdded() = runTest {
        val r13 = CheckResult(CheckId.R13, CheckStatus.OK, mapOf("serial" to "****A1B2"), null, 2L)

        sut.publish(r13)

        val event = encoder.events.single() as AgentEvent.CapabilityChecked
        assertThat(event.result.raw).containsExactly("serial", "****A1B2")
        assertThat(event.result).isEqualTo(r13)
    }
}
