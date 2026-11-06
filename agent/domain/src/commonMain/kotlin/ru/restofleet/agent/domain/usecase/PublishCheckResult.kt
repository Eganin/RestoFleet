package ru.restofleet.agent.domain.usecase

import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.port.CheckResultPublisher
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/** Публикует результат одной проверки через [CheckResultPublisher]. */
class PublishCheckResult(private val publisher: CheckResultPublisher) {
    suspend operator fun invoke(result: CheckResult): Outcome<Unit, DomainError> = publisher.publish(result)
}
