package ru.restofleet.console.core.network.realtime

import kotlin.random.Random

/** Задержка переподключения: 1 с, 2, 4 … до 30 с; [jitter] размывает «залп» клиентов после падения сервера. */
class Backoff(
    private val minMs: Long = DEFAULT_MIN_MS,
    private val maxMs: Long = DEFAULT_MAX_MS,
    private val jitter: (Long) -> Long = { base -> base - Random.nextLong(base / JITTER_DIVISOR + 1) },
) {
    fun delayMs(attempt: Int): Long {
        val shift = attempt.coerceIn(0, MAX_SHIFT)
        val base = minOf(maxMs, minMs shl shift)
        return jitter(base).coerceIn(minMs, maxMs)
    }

    companion object {
        const val DEFAULT_MIN_MS = 1_000L
        const val DEFAULT_MAX_MS = 30_000L
        private const val JITTER_DIVISOR = 5L
        private const val MAX_SHIFT = 20
    }
}
