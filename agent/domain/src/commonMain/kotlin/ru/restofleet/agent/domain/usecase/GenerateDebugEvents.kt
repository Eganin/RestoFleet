package ru.restofleet.agent.domain.usecase

import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.IdGenerator

/**
 * Только debug (проверка M7): публикует [count] событий `debug_seq` с `seq` 1..[count] через [PublishEvent]. Офлайн они
 * копятся в outbox и досылаются по порядку. Возвращает `batchId` пачки. В release use case в графе не регистрируется.
 */
class GenerateDebugEvents(
    private val publishEvent: PublishEvent,
    private val clock: Clock,
    private val ids: IdGenerator,
) {
    suspend operator fun invoke(count: Int = DEFAULT_COUNT): String {
        require(count in 1..MAX_COUNT) { "count must be in 1..$MAX_COUNT, was $count" }
        val batchId = ids.newId()
        for (seq in 1..count) publishEvent(AgentEvent.DebugSeq(clock.nowMillis(), seq, batchId))
        return batchId
    }

    companion object {
        const val DEFAULT_COUNT = 500
        const val MAX_COUNT = 10_000
    }
}
