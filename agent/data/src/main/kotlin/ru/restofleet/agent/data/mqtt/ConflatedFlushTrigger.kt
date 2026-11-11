package ru.restofleet.agent.data.mqtt

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import ru.restofleet.agent.domain.port.FlushTrigger

/**
 * [FlushTrigger] на канале `CONFLATED` (реш. 203): запросы, пришедшие во время досылки, сливаются в один. Потребитель
 * один — [SessionSupervisor] (поток [requests]).
 */
class ConflatedFlushTrigger : FlushTrigger {
    private val channel = Channel<Unit>(Channel.CONFLATED)

    val requests: Flow<Unit> = channel.receiveAsFlow()

    override fun request() {
        channel.trySend(Unit)
    }
}
