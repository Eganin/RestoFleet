package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.fake.FakeBrokerSession
import ru.restofleet.agent.domain.fake.FakeMessagePublisher
import ru.restofleet.agent.domain.fake.FakeOutbox
import ru.restofleet.agent.domain.fake.TestError
import ru.restofleet.agent.domain.fake.TestRejection
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.agent.domain.port.FlushTrigger
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.shared.kernel.Outcome

/** Реш. 203: постановка в outbox при живой связи будит досылку сразу, а не через таймер 60 с. */
class PublishOrBufferFlushTriggerTest {
    private val session = FakeBrokerSession()
    private val publisher = FakeMessagePublisher()
    private val outbox = FakeOutbox()
    private var requests = 0
    private val publish = PublishOrBuffer(session, publisher, outbox, FlushTrigger { requests++ })

    private fun message(text: String) = OutgoingMessage(MessageKind.EVENT, text.toByteArray(), 1, createdAt = 5L)

    @Test
    fun queuedBehindNonEmptyOutboxWhileConnectedRequestsFlush() = runTest {
        outbox.enqueue(message("old"))

        publish(message("new"))

        assertThat(outbox.messages.map { String(it.payload) }).containsExactly("old", "new").inOrder()
        assertThat(requests).isEqualTo(1)
    }

    @Test
    fun failedPublishWhileConnectedRequestsFlush() = runTest {
        publisher.result = { Outcome.Failure(TestError("timeout")) }

        publish(message("a"))

        assertThat(outbox.messages).hasSize(1)
        assertThat(requests).isEqualTo(1)
    }

    @Test
    fun directPublishDoesNotRequestFlush() = runTest {
        publish(message("a"))

        assertThat(publisher.published).hasSize(1)
        assertThat(requests).isEqualTo(0)
    }

    @Test
    fun bufferingWhileDisconnectedDoesNotRequestFlush() = runTest {
        // Досылку запустит само подключение (connectedEpoch), будить нечего
        session.state.value = SessionState.DISCONNECTED

        publish(message("a"))

        assertThat(outbox.messages).hasSize(1)
        assertThat(requests).isEqualTo(0)
    }

    @Test
    fun brokerRejectionIsNotBufferedAndDoesNotRequestFlush() = runTest {
        publisher.result = { Outcome.Failure(TestRejection("not_authorized")) }

        publish(message("a"))

        assertThat(outbox.messages).isEmpty()
        assertThat(requests).isEqualTo(0)
    }

    @Test
    fun defaultTriggerIsNoOp() = runTest {
        outbox.enqueue(message("old"))

        PublishOrBuffer(session, publisher, outbox)(message("new"))

        assertThat(outbox.messages).hasSize(2)
    }
}
