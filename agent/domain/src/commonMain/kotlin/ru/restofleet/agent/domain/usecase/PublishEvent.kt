package ru.restofleet.agent.domain.usecase

import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.agent.domain.port.PayloadEncoder
import ru.restofleet.shared.kernel.Clock

/** Отправляет событие агента: QoS 1 без retain (ТЗ 7.1); без связи [PublishOrBuffer] сохраняет его в outbox. */
class PublishEvent(
    private val publish: PublishOrBuffer,
    private val encoder: PayloadEncoder,
    private val clock: Clock,
) {
    suspend operator fun invoke(event: AgentEvent) {
        publish(
            OutgoingMessage(
                kind = MessageKind.EVENT,
                payload = encoder.event(event),
                qos = EVENT_QOS,
                createdAt = clock.nowMillis(),
            ),
        )
    }

    private companion object {
        const val EVENT_QOS = 1
    }
}
