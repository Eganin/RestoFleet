package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.fake.FakeMessagePublisher
import ru.restofleet.agent.domain.fake.FakeOutbox
import ru.restofleet.agent.domain.fake.TestError
import ru.restofleet.agent.domain.fake.TestRejection
import ru.restofleet.agent.domain.model.FlushResult
import ru.restofleet.agent.domain.model.FlushScope
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

    // I2: окончательный отказ брокера не блокирует очередь навсегда
    @Test
    fun brokerRejectedHeadIsDroppedAndTheNextRecordIsSent() = runTest {
        val log = mutableListOf<String>()
        val flush = FlushOutbox(publisher, outbox, log = { log += it })
        fill(2)
        publisher.result = { m ->
            if (String(m.payload) == "m0") Outcome.Failure(TestRejection()) else Outcome.Success(Unit)
        }

        val result = flush()

        assertThat(result).isEqualTo(FlushResult(sent = 1, remaining = 0, dropped = 1))
        assertThat(payloads(publisher.published)).containsExactly("m1")
        assertThat(outbox.size()).isEqualTo(0)
        assertThat(log).hasSize(1)
        assertThat(log.single()).contains("not_authorized")
        assertThat(log.single()).contains("EVENT")
        assertThat(log.single()).doesNotContain("m0")
    }

    @Test
    fun droppedRecordsAreCountedAcrossFlushes() = runTest {
        val log = mutableListOf<String>()
        val flush = FlushOutbox(publisher, outbox, log = { log += it })
        publisher.result = { Outcome.Failure(TestRejection("puback_topic_name_invalid")) }
        fill(1)
        flush()
        fill(2)

        val second = flush()

        assertThat(second).isEqualTo(FlushResult(sent = 0, remaining = 0, dropped = 2))
        assertThat(log.last()).contains("total=3")
    }

    @Test
    fun transientFailureAfterADropStillStopsAndKeepsTheRecord() = runTest {
        fill(3)
        publisher.result = { m ->
            when (String(m.payload)) {
                "m0" -> Outcome.Failure(TestRejection())
                "m1" -> Outcome.Failure(TestError("publish_timeout"))
                else -> Outcome.Success(Unit)
            }
        }

        assertThat(flush()).isEqualTo(FlushResult(sent = 0, remaining = 2, dropped = 1))
        assertThat(payloads(outbox.messages)).containsExactly("m1", "m2").inOrder()
    }

    // I3: замок отпускается между пачками, итоги команд не ждут конца досылки телеметрии
    private suspend fun enqueue(kind: MessageKind, text: String) {
        outbox.enqueue(OutgoingMessage(kind, text.toByteArray(), 1, createdAt = 0))
    }

    @Test
    fun commandResultsScopeSendsOnlyCommandResultsInTheirOrder() = runTest {
        enqueue(MessageKind.TELEMETRY, "t0")
        enqueue(MessageKind.EVENT, "e0")
        enqueue(MessageKind.COMMAND_RESULT, "received:c1")
        enqueue(MessageKind.PROBE, "p0")
        enqueue(MessageKind.COMMAND_RESULT, "succeeded:c1")

        val result = flush(FlushScope.COMMAND_RESULTS)

        assertThat(result).isEqualTo(FlushResult(sent = 2, remaining = 3))
        assertThat(payloads(publisher.published)).containsExactly("received:c1", "succeeded:c1").inOrder()
        assertThat(payloads(outbox.messages)).containsExactly("t0", "e0", "p0").inOrder()
    }

    @Test
    fun commandResultsDuringALongDrainGoOutAfterTheCurrentBatchAndEventsKeepTheirOrder() = runTest {
        repeat(1_000) { enqueue(MessageKind.TELEMETRY, "t$it") }
        val drain = async { flush() }
        advanceTimeBy(1_000)
        runCurrent()
        repeat(60) { enqueue(MessageKind.EVENT, "seq-$it") }
        enqueue(MessageKind.COMMAND_RESULT, "succeeded:c1")

        val results = async { flush(FlushScope.COMMAND_RESULTS) }
        // текущая пачка досылки: 50 записей по 50 мс от t=0
        advanceTimeBy(1_500)
        runCurrent()

        assertThat(results.isCompleted).isTrue()
        assertThat(results.await().sent).isEqualTo(1)
        assertThat(payloads(publisher.published)).contains("succeeded:c1")
        assertThat(publisher.published.size).isLessThan(100)
        assertThat(drain.isCompleted).isFalse()

        drain.await()
        val all = payloads(publisher.published)
        assertThat(all).hasSize(1_061)
        assertThat(all.toSet()).hasSize(1_061)
        assertThat(all.filter { it.startsWith("seq-") }).isEqualTo((0 until 60).map { "seq-$it" })
        assertThat(outbox.size()).isEqualTo(0)
    }

    @Test
    fun fullDrainReleasesTheLockBetweenBatches() = runTest {
        repeat(120) { enqueue(MessageKind.TELEMETRY, "t$it") }
        val drain = async { flush() }
        runCurrent()
        enqueue(MessageKind.COMMAND_RESULT, "r")

        val results = async { flush(FlushScope.COMMAND_RESULTS) }
        advanceTimeBy(49 * 50L)
        runCurrent()
        assertThat(results.isCompleted).isTrue()
        assertThat(payloads(publisher.published).indexOf("r")).isEqualTo(50)

        drain.await()
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
