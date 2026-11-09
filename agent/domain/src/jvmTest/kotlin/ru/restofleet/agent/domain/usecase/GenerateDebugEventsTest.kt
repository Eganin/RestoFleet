package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.fake.CapturingPayloadEncoder
import ru.restofleet.agent.domain.fake.FakeBrokerSession
import ru.restofleet.agent.domain.fake.FakeMessagePublisher
import ru.restofleet.agent.domain.fake.FakeOutbox
import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.IdGenerator

class GenerateDebugEventsTest {
    private val session = FakeBrokerSession()
    private val publisher = FakeMessagePublisher()
    private val outbox = FakeOutbox()
    private val encoder = CapturingPayloadEncoder()
    private val clock = Clock { 11 }
    private val sut = GenerateDebugEvents(
        PublishEvent(PublishOrBuffer(session, publisher, outbox), encoder, clock),
        clock,
        IdGenerator { "batch-1" },
    )

    @Test
    fun nEventsBecomeNMessagesWithAscendingSeq() = runTest {
        val batchId = sut(5)

        assertThat(batchId).isEqualTo("batch-1")
        assertThat(publisher.published).hasSize(5)
        val events = encoder.events.map { it as AgentEvent.DebugSeq }
        assertThat(events.map { it.seq }).containsExactly(1, 2, 3, 4, 5).inOrder()
        assertThat(events.map { it.batchId }.toSet()).containsExactly("batch-1")
    }

    @Test
    fun defaultCountIsFiveHundred() = runTest {
        sut()

        assertThat(publisher.published).hasSize(500)
        assertThat((encoder.events.last() as AgentEvent.DebugSeq).seq).isEqualTo(500)
    }

    @Test
    fun offlineEventsAccumulateInTheOutboxInOrder() = runTest {
        session.state.value = SessionState.DISCONNECTED

        sut(3)

        assertThat(publisher.calls).isEqualTo(0)
        assertThat(outbox.messages).hasSize(3)
        assertThat(encoder.events.map { (it as AgentEvent.DebugSeq).seq }).containsExactly(1, 2, 3).inOrder()
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveCountIsRejected() = runTest {
        sut(0)
    }
}
