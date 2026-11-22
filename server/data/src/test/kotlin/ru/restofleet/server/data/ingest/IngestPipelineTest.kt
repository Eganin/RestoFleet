package ru.restofleet.server.data.ingest

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.data.config.IngestConfig
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class IngestPipelineTest {
    private val metrics = IngestMetrics()
    private val config = IngestConfig(batchSize = 3, batchWindowMs = 500, retryInitialMs = 1_000, retryMaxMs = 4_000)

    /** Сообщение со счётчиком подтверждений. */
    private class Msg(val name: String) {
        val acks = AtomicInteger()
        val inbound = InboundMessage("rf/v1/dev/d-1/$name", name.toByteArray()) { acks.incrementAndGet() }
    }

    /** Фейковый обработчик: пишет пачки и может падать по сценарию. */
    private class Recorder(var failure: (List<String>) -> Exception? = { null }) : InboundProcessor {
        val batches = mutableListOf<List<String>>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun process(messages: List<InboundMessage>) {
            val names = messages.map { it.payload.decodeToString() }
            batches += names
            gate?.await()
            failure(names)?.let { throw it }
        }
    }

    private class Transient : RuntimeException("db down")

    private fun TestScope.pipeline(
        processor: InboundProcessor,
        isTransient: (Throwable) -> Boolean = { it is Transient },
    ) =
        IngestPipeline(processor, config, metrics, isTransient, timeSource = testScheduler.timeSource)

    @Test
    fun `batch closes at batchSize and the rest waits for its own window`() = runTest {
        val recorder = Recorder()
        val pipeline = pipeline(recorder)
        val msgs = (1..5).map { Msg("m$it") }
        msgs.forEach { pipeline.offer(it.inbound) }

        val job = launch { pipeline.run() }
        runCurrent()

        assertThat(recorder.batches).containsExactly(listOf("m1", "m2", "m3"))
        advanceTimeBy(499)
        runCurrent()
        assertThat(recorder.batches).hasSize(1)
        advanceTimeBy(1)
        runCurrent()
        assertThat(recorder.batches).containsExactly(listOf("m1", "m2", "m3"), listOf("m4", "m5")).inOrder()
        assertThat(msgs.map { it.acks.get() }).containsExactly(1, 1, 1, 1, 1)
        assertThat(metrics.batches.get()).isEqualTo(2)
        assertThat(metrics.received.get()).isEqualTo(5)
        job.cancel()
    }

    @Test
    fun `window counts from the first message, later arrivals join until it ends`() = runTest {
        val recorder = Recorder()
        val pipeline = pipeline(recorder)
        val job = launch { pipeline.run() }
        runCurrent()

        pipeline.offer(Msg("a").inbound)
        advanceTimeBy(300)
        pipeline.offer(Msg("b").inbound)
        advanceTimeBy(199)
        runCurrent()
        assertThat(recorder.batches).isEmpty()
        advanceTimeBy(1)
        runCurrent()
        pipeline.offer(Msg("c").inbound)
        advanceTimeBy(500)
        runCurrent()

        assertThat(recorder.batches).containsExactly(listOf("a", "b"), listOf("c")).inOrder()
        job.cancel()
    }

    @Test
    fun `messages arriving around the window boundary are never lost`() = runTest {
        val recorder = Recorder()
        val pipeline = pipeline(recorder)
        val job = launch { pipeline.run() }
        val msgs = (1..40).map { Msg("x$it") }

        msgs.forEachIndexed { i, m ->
            pipeline.offer(m.inbound)
            advanceTimeBy((i % 7) * 83L)
        }
        advanceTimeBy(10_000)
        runCurrent()

        assertThat(recorder.batches.flatten()).containsExactlyElementsIn(msgs.map { it.name }).inOrder()
        assertThat(msgs.all { it.acks.get() == 1 }).isTrue()
        job.cancel()
    }

    @Test
    fun `ack happens only after the processor returns`() = runTest {
        val recorder = Recorder().apply { gate = CompletableDeferred() }
        val pipeline = pipeline(recorder)
        val m = Msg("only")
        pipeline.offer(m.inbound)
        val job = launch { pipeline.run() }

        advanceTimeBy(600)
        runCurrent()
        assertThat(recorder.batches).hasSize(1)
        assertThat(m.acks.get()).isEqualTo(0)

        recorder.gate!!.complete(Unit)
        runCurrent()
        assertThat(m.acks.get()).isEqualTo(1)
        job.cancel()
    }

    @Test
    fun `cancelling while a batch is processed leaves it unacknowledged for redelivery`() = runTest {
        val recorder = Recorder().apply { gate = CompletableDeferred() }
        val pipeline = pipeline(recorder)
        val m = Msg("inflight")
        pipeline.offer(m.inbound)
        val job = launch { pipeline.run() }
        advanceTimeBy(600)
        runCurrent()

        job.cancel()
        runCurrent()

        assertThat(m.acks.get()).isEqualTo(0)
        assertThat(metrics.batches.get()).isEqualTo(0)
    }

    @Test
    fun `transient failure retries the same batch with growing pause and no ack in between`() = runTest {
        var failuresLeft = 3
        val recorder = Recorder(failure = { if (failuresLeft-- > 0) Transient() else null })
        val pipeline = pipeline(recorder)
        val msgs = listOf(Msg("t1"), Msg("t2"))
        msgs.forEach { pipeline.offer(it.inbound) }
        val job = launch { pipeline.run() }

        advanceTimeBy(500)
        runCurrent()
        assertThat(recorder.batches).hasSize(1)
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(recorder.batches).hasSize(2)
        advanceTimeBy(1_999)
        runCurrent()
        assertThat(recorder.batches).hasSize(2)
        assertThat(msgs.map { it.acks.get() }).containsExactly(0, 0)
        advanceTimeBy(1)
        runCurrent()
        assertThat(recorder.batches).hasSize(3)
        advanceTimeBy(4_000)
        runCurrent()

        assertThat(recorder.batches).hasSize(4)
        assertThat(recorder.batches.toSet()).containsExactly(listOf("t1", "t2"))
        assertThat(msgs.map { it.acks.get() }).containsExactly(1, 1)
        assertThat(metrics.retries.get()).isEqualTo(3)
        assertThat(metrics.poison.get()).isEqualTo(0)
        job.cancel()
    }

    @Test
    fun `pause between retries is capped by retryMaxMs`() = runTest {
        var failuresLeft = 5
        val recorder = Recorder(failure = { if (failuresLeft-- > 0) Transient() else null })
        val pipeline = pipeline(recorder)
        pipeline.offer(Msg("cap").inbound)
        val job = launch { pipeline.run() }

        // окно 500 + паузы 1000, 2000, 4000, 4000, 4000
        advanceTimeBy(500 + 1_000 + 2_000 + 4_000 + 4_000 + 3_999)
        runCurrent()
        assertThat(recorder.batches).hasSize(5)
        advanceTimeBy(1)
        runCurrent()
        assertThat(recorder.batches).hasSize(6)
        job.cancel()
    }

    @Test
    fun `permanent failure splits the batch and acknowledges a poison message without blocking the rest`() = runTest {
        val recorder = Recorder(failure = { if ("bad" in it) IllegalStateException("constraint") else null })
        val pipeline = pipeline(recorder)
        val msgs = listOf(Msg("ok1"), Msg("bad"), Msg("ok2"))
        msgs.forEach { pipeline.offer(it.inbound) }
        val job = launch { pipeline.run() }
        runCurrent()

        assertThat(recorder.batches).containsExactly(
            listOf("ok1", "bad", "ok2"), listOf("ok1"), listOf("bad"), listOf("ok2"),
        ).inOrder()
        assertThat(msgs.map { it.acks.get() }).containsExactly(1, 1, 1)
        assertThat(metrics.poison.get()).isEqualTo(1)
        assertThat(metrics.retries.get()).isEqualTo(0)
        job.cancel()
    }

    @Test
    fun `cancellation exception thrown by processing itself is a failure, not a stop`() = runTest {
        var thrown = false
        val recorder = Recorder(
            failure = { if (!thrown) CancellationException("inner timeout").also { thrown = true } else null },
        )
        val pipeline = pipeline(recorder, isTransient = { true })
        val m = Msg("c")
        pipeline.offer(m.inbound)
        val job = launch { pipeline.run() }

        advanceTimeBy(500 + 1_000)
        runCurrent()

        assertThat(recorder.batches).hasSize(2)
        assertThat(m.acks.get()).isEqualTo(1)
        job.cancel()
    }
}
