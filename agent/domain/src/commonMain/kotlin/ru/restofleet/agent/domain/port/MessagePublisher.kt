package ru.restofleet.agent.domain.port

import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/** Отправляет сообщение брокеру. Не бросает: сбой (нет сессии, отказ брокера) — [Outcome.Failure]. */
interface MessagePublisher {
    suspend fun publish(message: OutgoingMessage): Outcome<Unit, DomainError>
}
