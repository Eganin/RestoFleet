package ru.restofleet.server.data.mqtt

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Повторная подписка на каждом (пере)подключении (V-1, реш. 301) с повтором по таймеру (остаток V-1, П): SUBACK с
 * ошибкой или сбой SUBSCRIBE повторяются через [retryMs], пока соединение то же ([onConnected] не вызывался снова) и
 * [connected]. Раньше повтор ждал следующего переподключения, и до него ingest стоял. [subscribe] возвращает число
 * отклонённых фильтров (0 — все приняты); [onActive] — все подписки подтверждены на текущем соединении.
 */
internal class Resubscription(
    private val subscribe: (List<String>) -> CompletableFuture<Int>,
    private val connected: () -> Boolean,
    private val onActive: () -> Unit,
    private val retryMs: Long = RETRY_MS,
    private val schedule: (Long, () -> Unit) -> Unit = { ms, task ->
        CompletableFuture.delayedExecutor(ms, TimeUnit.MILLISECONDS).execute(task)
    },
) {
    private val log = KotlinLogging.logger {}
    private val connection = AtomicLong()

    /** Новое соединение: прежние повторы больше не действуют. */
    fun onConnected(filters: List<String>) {
        val epoch = connection.incrementAndGet()
        if (filters.isNotEmpty()) attempt(filters, epoch)
    }

    private fun attempt(filters: List<String>, epoch: Long) {
        subscribe(filters).whenComplete { rejected, error ->
            when {
                error == null && rejected == 0 -> {
                    onActive()
                    log.info { "MQTT resubscribed ${filters.size} filters" }
                }
                else -> {
                    if (error != null) {
                        log.error { "resubscribe failed: ${error.javaClass.simpleName}" }
                    } else {
                        log.error { "resubscribe: SUBACK rejected $rejected of ${filters.size} filters" }
                    }
                    schedule(retryMs) { if (connection.get() == epoch && connected()) attempt(filters, epoch) }
                }
            }
        }
    }

    companion object {
        const val RETRY_MS = 5_000L
    }
}
