package ru.restofleet.agent.domain.usecase

import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.agent.domain.port.PayloadEncoder
import ru.restofleet.shared.kernel.Clock

/** Отправляет отчёт проб (топик `probe`): QoS 0 без retain, без связи [PublishOrBuffer] кладёт его в outbox. */
class PublishProbeResult(
    private val publish: PublishOrBuffer,
    private val encoder: PayloadEncoder,
    private val clock: Clock,
) {
    suspend operator fun invoke(results: List<ProbeResult>) {
        publish(
            OutgoingMessage(
                kind = MessageKind.PROBE,
                payload = encoder.probes(results),
                qos = PROBE_QOS,
                createdAt = clock.nowMillis(),
            ),
        )
    }

    private companion object {
        const val PROBE_QOS = 0
    }
}
