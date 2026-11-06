package ru.restofleet.agent.data.report

import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.error.MqttNotConfigured
import ru.restofleet.agent.domain.port.CheckResultPublisher
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/** Заглушка R-M1: пока MQTT-клиента нет (до задачи 3.6), публикация всегда отвечает [MqttNotConfigured]. */
class NotConfiguredCheckResultPublisher : CheckResultPublisher {
    override suspend fun publish(result: CheckResult): Outcome<Unit, DomainError> = Outcome.Failure(MqttNotConfigured)
}
