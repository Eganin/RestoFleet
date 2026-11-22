package ru.restofleet.server.simulator.domain.runtime

import ru.restofleet.server.simulator.domain.model.CounterSnapshot
import java.util.concurrent.atomic.AtomicLong

/**
 * Счётчики прогона: их увеличивают и раннер устройств, и транспорты (сообщения, запросы, байты полезной нагрузки).
 * Потокобезопасны: колбэки HiveMQ и OkHttp идут из своих потоков.
 */
class RunMetrics {
    private val connected = AtomicLong()
    private val connectFailures = AtomicLong()
    private val connectionLosses = AtomicLong()
    private val messagesOut = AtomicLong()
    private val messagesIn = AtomicLong()
    private val requests = AtomicLong()
    private val requestErrors = AtomicLong()
    private val publishErrors = AtomicLong()
    private val payloadBytesOut = AtomicLong()
    private val payloadBytesIn = AtomicLong()

    fun connected() = connected.incrementAndGet()

    fun disconnected() = connected.decrementAndGet()

    fun connectFailed() = connectFailures.incrementAndGet()

    fun connectionLost() = connectionLosses.incrementAndGet()

    fun messageOut(bytes: Int) {
        messagesOut.incrementAndGet()
        payloadBytesOut.addAndGet(bytes.toLong())
    }

    fun messageIn(bytes: Int) {
        messagesIn.incrementAndGet()
        payloadBytesIn.addAndGet(bytes.toLong())
    }

    fun request(bytesOut: Int, bytesIn: Int) {
        requests.incrementAndGet()
        payloadBytesOut.addAndGet(bytesOut.toLong())
        payloadBytesIn.addAndGet(bytesIn.toLong())
    }

    fun requestFailed() = requestErrors.incrementAndGet()

    fun publishFailed() = publishErrors.incrementAndGet()

    fun snapshot() = CounterSnapshot(
        connected = connected.get(),
        connectFailures = connectFailures.get(),
        connectionLosses = connectionLosses.get(),
        messagesOut = messagesOut.get(),
        messagesIn = messagesIn.get(),
        requests = requests.get(),
        requestErrors = requestErrors.get(),
        publishErrors = publishErrors.get(),
        payloadBytesOut = payloadBytesOut.get(),
        payloadBytesIn = payloadBytesIn.get(),
    )
}

/** Разность накопительных счётчиков; `connected` — значение на конце окна. */
operator fun CounterSnapshot.minus(start: CounterSnapshot) = CounterSnapshot(
    connected = connected,
    connectFailures = connectFailures - start.connectFailures,
    connectionLosses = connectionLosses - start.connectionLosses,
    messagesOut = messagesOut - start.messagesOut,
    messagesIn = messagesIn - start.messagesIn,
    requests = requests - start.requests,
    requestErrors = requestErrors - start.requestErrors,
    publishErrors = publishErrors - start.publishErrors,
    payloadBytesOut = payloadBytesOut - start.payloadBytesOut,
    payloadBytesIn = payloadBytesIn - start.payloadBytesIn,
)
