package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.fake.FakeMessagePublisher
import ru.restofleet.agent.domain.fake.FakeOutbox
import ru.restofleet.agent.domain.fake.TestError
import ru.restofleet.agent.domain.model.FlushResult
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.shared.kernel.Outcome
import kotlin.test.assertFailsWith

class FlushOutboxTest {
    private val publisher = FakeMessagePublisher()
    private val outbox = FakeOutbox()
    private val flush = FlushOutbox(publisher, outbox)

    private suspend fun fill(count: Int) {
        repeat(count) { i ->
            outbox.enqueue(OutgoingMessage(MessageKind.EVENT, "m$i".toByteArray(), 1, createdAt = i.toLong()))
        }
    }

    private fun payloads(list: List<OutgoingMessage>) = list.map { String(it.payload) }

    @Test
    fun emptyOutboxSendsNothing() = runTest {
        assertThat(flush()).isEqualTo(FlushResult(sent = 0, remaining = 0))
        assertThat(publisher.calls).isEqualTo(0)
    }

    @Test
    fun sendsEverythingInPeekOrder() = runTest {
        fill(120)
        val expectedOrder = payloads(outbox.messages)

        val result = flush()

        assertThat(result).isEqualTo(FlushResult(sent = 120, remaining = 0))
        assertThat(payloads(publisher.published)).isEqualTo(expectedOrder)
    }

    @Test
    fun failureOnSixtyFirstStopsAfterSixtyAndResumesFromSixtyFirst() = runTest {
        fill(120)
        val all = payloads(outbox.messages)
        var count = 0
        publisher.result = {
            count++
            if (count == 61) Outcome.Failure(TestError("down")) else Outcome.Success(Unit)
        }

        val first = flush()

        assertThat(first).isEqualTo(FlushResult(sent = 60, remaining = 60))
        assertThat(payloads(publisher.published)).isEqualTo(all.take(60))
        assertThat(payloads(outbox.messages)).isEqualTo(all.drop(60))
        assertThat(outbox.removeCalls.flatten()).isEqualTo((1L..60L).toList())

        publisher.published.clear()
        publisher.result = { Outcome.Success(Unit) }
        val second = flush()

        assertThat(second).isEqualTo(FlushResult(sent = 60, remaining = 0))
        assertThat(payloads(publisher.published)).isEqualTo(all.drop(60))
    }

    @Test
    fun removesRecordsOneByOneAfterEachSuccess() = runTest {
        fill(120)
        var count = 0
        publisher.result = {
            count++
            if (count == 61) Outcome.Failure(TestError("down")) else Outcome.Success(Unit)
        }

        flush()

        assertThat(outbox.removeCalls).hasSize(60)
        assertThat(outbox.removeCalls.all { it.size == 1 }).isTrue()
    }

    @Test
    fun failureOnFirstMessageKeepsEverything() = runTest {
        fill(3)
        publisher.result = { Outcome.Failure(TestError("down")) }

        assertThat(flush()).isEqualTo(FlushResult(sent = 0, remaining = 3))
        assertThat(outbox.removeCalls).isEmpty()
    }

    @Test
    fun publisherExceptionStopsFlushAndKeepsRecord() = runTest {
        fill(5)
        var count = 0
        publisher.result = {
            count++
            if (count == 3) error("boom") else Outcome.Success(Unit)
        }

        assertThat(flush()).isEqualTo(FlushResult(sent = 2, remaining = 3))
    }

    @Test
    fun errorFromPublisherPropagates() = runTest {
        fill(2)
        publisher.result = { throw AssertionError("fatal") }

        assertFailsWith<AssertionError> { flush() }
        assertThat(outbox.size()).isEqualTo(2)
    }

    @Test
    fun paceDoesNotExceedRatePerSecond() = runTest {
        fill(41)
        val times = mutableListOf<Long>()
        publisher.result = {
            times += testScheduler.currentTime
            Outcome.Success(Unit)
        }

        flush()

        assertThat(times).hasSize(41)
        assertThat(times.zipWithNext { a, b -> b - a }.all { it >= 50L }).isTrue()
        // 20 в секунду: в любом окне в 1 с не больше 20 отправок
        assertThat(times.last() - times.first()).isAtLeast(2_000L)
    }

    @Test
    fun customRateIsRespected() = runTest {
        val slow = FlushOutbox(publisher, outbox, batch = 2, ratePerSec = 4)
        fill(5)
        val times = mutableListOf<Long>()
        publisher.result = {
            times += testScheduler.currentTime
            Outcome.Success(Unit)
        }

        assertThat(slow()).isEqualTo(FlushResult(sent = 5, remaining = 0))

        assertThat(times.zipWithNext { a, b -> b - a }.all { it >= 250L }).isTrue()
    }

    @Test
    fun concurrentFlushesNeverSendSameRecordTwice() = runTest {
        fill(10)

        val results = listOf(async { flush() }, async { flush() }).awaitAll()

        assertThat(publisher.calls).isEqualTo(10)
        assertThat(results.sumOf { it.sent }).isEqualTo(10)
        assertThat(outbox.size()).isEqualTo(0)
    }
}
