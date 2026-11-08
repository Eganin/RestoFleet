package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.fake.FakeBrokerSession
import ru.restofleet.agent.domain.fake.FakeMessagePublisher
import ru.restofleet.agent.domain.fake.FakeOutbox
import ru.restofleet.agent.domain.fake.TestError
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.shared.kernel.Outcome
import kotlin.test.assertFailsWith

class PublishOrBufferTest {
    private val session = FakeBrokerSession()
    private val publisher = FakeMessagePublisher()
    private val outbox = FakeOutbox()
    private val publish = PublishOrBuffer(session, publisher, outbox)

    private fun message(
        text: String = "m",
        kind: MessageKind = MessageKind.EVENT,
        qos: Int = 1,
        retain: Boolean = false,
    ) = OutgoingMessage(kind, text.toByteArray(), qos, retain, createdAt = 5L)

    @Test
    fun connectedAndSuccessLeavesOutboxEmpty() = runTest {
        val m = message()

        publish(m)

        assertThat(publisher.published).containsExactly(m)
        assertThat(outbox.messages).isEmpty()
    }

    @Test
    fun connectedButPublishFailureBuffersMessage() = runTest {
        publisher.result = { Outcome.Failure(TestError("no_session")) }

        publish(message("a"))

        assertThat(outbox.messages.map { String(it.payload) }).containsExactly("a")
    }

    @Test
    fun disconnectedBuffersWithoutCallingPublisher() = runTest {
        session.state.value = SessionState.DISCONNECTED

        publish(message("a"))

        assertThat(publisher.calls).isEqualTo(0)
        assertThat(outbox.messages.map { String(it.payload) }).containsExactly("a")
    }

    @Test
    fun connectingBuffersWithoutCallingPublisher() = runTest {
        session.state.value = SessionState.CONNECTING

        publish(message("a"))

        assertThat(publisher.calls).isEqualTo(0)
        assertThat(outbox.messages).hasSize(1)
    }

    @Test
    fun nonEmptyOutboxKeepsOrderEvenWhenConnected() = runTest {
        outbox.enqueue(message("received"))
        outbox.enqueued.clear()

        publish(message("succeeded"))

        assertThat(publisher.calls).isEqualTo(0)
        assertThat(outbox.messages.map { String(it.payload) }).containsExactly("received", "succeeded").inOrder()
    }

    @Test
    fun bufferedMessageGetsQosOneAndKeepsOtherFields() = runTest {
        session.state.value = SessionState.DISCONNECTED
        val original = OutgoingMessage(
            kind = MessageKind.STATUS,
            payload = "online".toByteArray(),
            qos = 0,
            retain = true,
            correlationData = "c1".toByteArray(),
            responseTopic = "rf/v1/dev/d/cmd/res",
            createdAt = 77L,
        )

        publish(original)

        assertThat(outbox.messages.single()).isEqualTo(original.copy(qos = 1))
        assertThat(outbox.messages.single().retain).isTrue()
    }

    @Test
    fun successfulDirectPublishKeepsOriginalQos() = runTest {
        val telemetry = message("t", MessageKind.TELEMETRY, qos = 0)

        publish(telemetry)

        assertThat(publisher.published.single().qos).isEqualTo(0)
    }

    @Test
    fun publisherExceptionBuffersMessage() = runTest {
        publisher.result = { error("boom") }

        publish(message("a"))

        assertThat(outbox.messages).hasSize(1)
    }

    @Test
    fun strayCancellationFromPublisherBuffersMessage() = runTest {
        publisher.result = { throw CancellationException("stray") }

        publish(message("a"))

        assertThat(outbox.messages).hasSize(1)
    }

    @Test
    fun genuineCancellationPropagatesAndDoesNotBuffer() = runTest {
        publisher.result = {
            delay(Long.MAX_VALUE)
            Outcome.Success(Unit)
        }
        val job: Job = launch(start = CoroutineStart.UNDISPATCHED) { publish(message("a")) }
        runCurrent()

        job.cancel()
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(outbox.messages).isEmpty()
    }

    @Test
    fun errorFromPublisherPropagates() = runTest {
        publisher.result = { throw AssertionError("fatal") }

        assertFailsWith<AssertionError> { publish(message("a")) }
    }
}
