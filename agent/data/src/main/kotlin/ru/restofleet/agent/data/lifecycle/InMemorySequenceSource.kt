package ru.restofleet.agent.data.lifecycle

import ru.restofleet.agent.domain.port.SequenceSource
import java.util.concurrent.atomic.AtomicLong

/** Счётчик `seq` снимков в памяти процесса; персистентный счётчик появится вместе с MQTT (M3). */
class InMemorySequenceSource : SequenceSource {
    private val counter = AtomicLong(0)

    override fun next(): Long = counter.incrementAndGet()
}
