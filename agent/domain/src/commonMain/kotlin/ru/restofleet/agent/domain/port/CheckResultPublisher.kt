package ru.restofleet.agent.domain.port

import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/** Отправляет результат проверки наружу (MQTT). Не бросает: сбой — [Outcome.Failure]. */
fun interface CheckResultPublisher {
    suspend fun publish(result: CheckResult): Outcome<Unit, DomainError>
}
