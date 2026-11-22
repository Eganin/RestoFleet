package ru.restofleet.server.api.ws

import ru.restofleet.shared.protocol.rest.console.ws.WsLimits

/** Настройки WebSocket консоли (реш. 418, 419); умолчания — `WsLimits` протокола. */
data class RealtimeSettings(
    /** 0 — без ping (тесты на виртуальном времени). */
    val pingPeriodMillis: Long = WsLimits.PING_PERIOD_SEC * MS,
    val timeoutMillis: Long = WsLimits.TIMEOUT_SEC * MS,
    val maxFrameBytes: Long = WsLimits.MAX_FRAME_BYTES,
    val maxConnectionsPerUser: Int = WsLimits.MAX_CONNECTIONS_PER_USER,
    val queueCapacity: Int = WsLimits.QUEUE_CAPACITY,
    val maxSubscriptions: Int = WsLimits.MAX_SUBSCRIPTIONS,
    val summaryMinIntervalMillis: Long = WsLimits.SUMMARY_MIN_INTERVAL_MS,
    /** Как часто проверять `exp` access-токена соединения (закрытие 4401). */
    val expiryCheckMillis: Long = DEFAULT_EXPIRY_CHECK_MS,
) {
    init {
        require(maxConnectionsPerUser > 0 && queueCapacity > 0 && maxSubscriptions > 0) { "limits must be positive" }
    }

    private companion object {
        const val MS = 1_000L
        const val DEFAULT_EXPIRY_CHECK_MS = 1_000L
    }
}
