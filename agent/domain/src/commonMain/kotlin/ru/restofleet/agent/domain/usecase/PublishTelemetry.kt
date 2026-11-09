package ru.restofleet.agent.domain.usecase

import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.agent.domain.model.TelemetrySnapshot
import ru.restofleet.agent.domain.port.PayloadEncoder
import ru.restofleet.shared.kernel.Clock

/**
 * Отправляет снимок телеметрии: QoS 0 без retain, пока есть связь; без связи [PublishOrBuffer] кладёт его в outbox
 * (досылка QoS 1, ТЗ 7.1).
 */
class PublishTelemetry(
    private val publish: PublishOrBuffer,
    private val encoder: PayloadEncoder,
    private val clock: Clock,
) {
    suspend operator fun invoke(snapshot: TelemetrySnapshot) {
        publish(
            OutgoingMessage(
                kind = MessageKind.TELEMETRY,
                payload = encoder.telemetry(snapshot),
                qos = TELEMETRY_QOS,
                createdAt = clock.nowMillis(),
            ),
        )
    }

    private companion object {
        const val TELEMETRY_QOS = 0
    }
}
