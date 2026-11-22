package ru.restofleet.server.data.realtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import ru.restofleet.server.domain.realtime.EventBus
import ru.restofleet.server.domain.realtime.RealtimeEvent
import java.util.concurrent.atomic.AtomicLong

/**
 * In-process шина (реш. 419, 445): `SharedFlow` без replay с буфером [capacity]. Публикация — `tryEmit`, приём не
 * ждёт подписчиков: если самый медленный подписчик отстал на весь буфер, событие отбрасывается и считается в
 * [dropped]. Подписчик (рассылка WS, ПМ3) видит рост счётчика и просит клиентов перечитать состояние (`resync`).
 * Отставание одного WS-клиента ограничивает его собственная очередь, а не шина.
 */
class SharedFlowEventBus(capacity: Int = DEFAULT_CAPACITY) : EventBus {
    private val flow = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = capacity)
    private val droppedCounter = AtomicLong()

    val dropped: Long get() = droppedCounter.get()

    override fun publish(event: RealtimeEvent) {
        if (!flow.tryEmit(event)) droppedCounter.incrementAndGet()
    }

    override fun events(): Flow<RealtimeEvent> = flow.asSharedFlow()

    companion object {
        const val DEFAULT_CAPACITY = 4_096
    }
}
