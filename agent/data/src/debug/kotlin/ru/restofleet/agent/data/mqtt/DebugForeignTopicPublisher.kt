package ru.restofleet.agent.data.mqtt

import ru.restofleet.agent.domain.port.ForeignPublishResult
import ru.restofleet.agent.domain.port.ForeignTopicPublisher
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.Topics

/**
 * Только debug (проверка M2): одна публикация QoS 1 в `telemetry` чужого устройства [FOREIGN_DEVICE]. Роль `device` в
 * DynSec разрешает писать лишь в свои топики, поэтому брокер должен ответить PUBACK `0x87` (`not_authorized`). Принятая
 * публикация означает дыру в ACL. Если агент сам запущен как [FOREIGN_DEVICE], топик окажется своим: проверка
 * бессмысленна. Файл лежит в `src/debug` модуля, в release его нет.
 */
internal class DebugForeignTopicPublisher(
    private val publishRaw: suspend (topic: String, payload: ByteArray, qos: Int) -> Outcome<Unit, DomainError>,
) : ForeignTopicPublisher {
    constructor(session: MqttBrokerSession) : this({ topic, payload, qos -> session.publishTo(topic, payload, qos) })

    override suspend fun publish(): ForeignPublishResult {
        val topic = Topics.telemetry(requireNotNull(DeviceId.parse(FOREIGN_DEVICE)))
        return when (val outcome = publishRaw(topic, PAYLOAD, QOS)) {
            is Outcome.Success -> ForeignPublishResult(accepted = true, reason = "accepted: ACL did not refuse")
            is Outcome.Failure -> ForeignPublishResult(
                accepted = false,
                reason = if (outcome.error == MqttError.NotAuthorized) "0x87 not_authorized" else outcome.error.code,
            )
        }
    }

    private companion object {
        const val FOREIGN_DEVICE = "dev-emu-31"
        const val QOS = 1
        val PAYLOAD = "{}".encodeToByteArray()
    }
}
