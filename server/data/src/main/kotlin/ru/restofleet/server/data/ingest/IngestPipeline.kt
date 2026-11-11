package ru.restofleet.server.data.ingest

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import ru.restofleet.server.data.config.IngestConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Конвейер ingest (P10, FR-BE-10): сообщения копятся в очереди, пачка — до [IngestConfig.batchSize] штук или
 * [IngestConfig.batchWindowMs] от первого, обрабатывается [InboundProcessor] и **только после этого**
 * подтверждается брокеру. Временный сбой (БД недоступна) — та же пачка повторяется с паузой 1…30 с, сообщения не
 * подтверждаются (брокер держит их в сессии; при остановке процесса передоставит). Детерминированный сбой — пачка
 * разбирается по одному; сообщение, которое падает само по себе, подтверждается и считается «ядовитым» (иначе оно
 * блокировало бы поток навсегда; реш. 235).
 *
 * Окно пачки отмеряет [timeSource] (в тестах — виртуальное время).
 */
class IngestPipeline(
    private val processor: InboundProcessor,
    private val config: IngestConfig,
    private val metrics: IngestMetrics,
    private val isTransient: (Throwable) -> Boolean = TransientErrors::isTransient,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val log = KotlinLogging.logger {}
    private val queue = Channel<InboundMessage>(Channel.UNLIMITED)

    /** Из потока HiveMQ: не блокирует. */
    fun offer(message: InboundMessage) {
        metrics.received.incrementAndGet()
        queue.trySend(message)
    }

    /** Цикл обработки; завершается отменой корутины (неподтверждённая пачка остаётся у брокера). */
    suspend fun run() {
        while (true) {
            val batch = nextBatch()
            handle(batch)
            batch.forEach { it.ack() }
            metrics.batches.incrementAndGet()
        }
    }

    /**
     * Первое сообщение ждём сколько угодно, остальные — до конца окна. Ожидание — через `select` с `onTimeout`:
     * в отличие от `withTimeoutOrNull { receive() }` таймаут не может отменить приём уже вынутого из очереди
     * сообщения (оно потерялось бы без обработки и без ack).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal suspend fun nextBatch(): List<InboundMessage> {
        val batch = mutableListOf(queue.receive())
        val deadline = timeSource.markNow() + config.batchWindowMs.milliseconds
        while (batch.size < config.batchSize) {
            val remaining = -deadline.elapsedNow()
            val next = queue.tryReceive().getOrNull()
                ?: if (remaining > Duration.ZERO) {
                    select {
                        queue.onReceive { it }
                        onTimeout(remaining) { null }
                    }
                } else {
                    null
                }
            if (next == null) break
            batch += next
        }
        return batch
    }

    private suspend fun handle(batch: List<InboundMessage>) {
        if (attempt(batch)) return
        log.warn { "batch of ${batch.size} failed permanently: processing one by one" }
        batch.forEach { message ->
            if (!attempt(listOf(message))) {
                metrics.poison.incrementAndGet()
                log.error { "poison message on ${message.topic} acknowledged without processing" }
            }
        }
    }

    /** `true` — обработано; `false` — детерминированный сбой. Временный сбой повторяется до успеха или отмены. */
    private suspend fun attempt(messages: List<InboundMessage>): Boolean {
        var pause = config.retryInitialMs
        var outcome: Boolean? = null
        while (outcome == null) {
            val error = try {
                processor.process(messages)
                null
            } catch (e: CancellationException) {
                // Отмена самого конвейера пробрасывается; CancellationException изнутри обработки — обычный сбой
                currentCoroutineContext().ensureActive()
                e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                e
            }
            when {
                error == null -> outcome = true
                !retryable(error) -> outcome = false
                else -> {
                    metrics.retries.incrementAndGet()
                    delay(pause)
                    pause = (pause * 2).coerceAtMost(config.retryMaxMs)
                }
            }
        }
        return outcome
    }

    private fun retryable(error: Throwable): Boolean {
        val transient = isTransient(error)
        val kind = if (transient) "transient" else "permanent"
        log.warn { "ingest batch failed ($kind): ${error.javaClass.simpleName}" }
        return transient
    }
}
