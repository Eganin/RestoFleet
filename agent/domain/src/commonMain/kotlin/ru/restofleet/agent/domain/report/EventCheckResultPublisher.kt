package ru.restofleet.agent.domain.report

import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.agent.domain.port.CheckResultPublisher
import ru.restofleet.agent.domain.usecase.PublishEvent
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/**
 * Публикует результат проверки Lab как событие `capability_check` через [PublishEvent] (`PublishOrBuffer`: без связи
 * событие ждёт в outbox). `raw` идёт как есть: серийный номер R13 маскируется у источника (R13), здесь не дополняется.
 */
class EventCheckResultPublisher(
    private val publishEvent: PublishEvent,
    private val clock: Clock,
) : CheckResultPublisher {
    override suspend fun publish(result: CheckResult): Outcome<Unit, DomainError> {
        publishEvent(AgentEvent.CapabilityChecked(clock.nowMillis(), result))
        return Outcome.Success(Unit)
    }
}
