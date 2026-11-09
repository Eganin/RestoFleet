package ru.restofleet.agent.data.mqtt

import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.protocol.Topics

/**
 * Топик исходящего сообщения по его виду (домен строк топиков не знает). Ответ на команду всегда уходит в `cmd/res`
 * этого устройства: `Response Topic` запроса допустим только равный ему (роль `device` в DynSec разрешает публикацию
 * лишь в свои топики протокола), а отказ брокера на одной записи остановил бы досылку всей очереди. Поэтому чужой
 * или произвольный `Response Topic` игнорируется, а `Correlation Data` переносится в ответ.
 */
internal class TopicRouter(private val deviceId: DeviceId) {
    private val cmdResTopic = Topics.cmdRes(deviceId)

    val statusTopic: String = Topics.status(deviceId)
    val cmdReqTopic: String = Topics.cmdReq(deviceId)
    val desiredTopic: String = Topics.shadowDesired(deviceId)

    fun outbound(m: OutgoingMessage): OutboundMessage = OutboundMessage(
        topic = topicFor(m),
        payload = m.payload,
        qos = m.qos,
        retain = m.retain,
        correlationData = m.correlationData,
    )

    private fun topicFor(m: OutgoingMessage): String = when (m.kind) {
        MessageKind.TELEMETRY -> Topics.telemetry(deviceId)
        MessageKind.PROBE -> Topics.probe(deviceId)
        MessageKind.EVENT -> Topics.events(deviceId)
        MessageKind.SHADOW_REPORTED -> Topics.shadowReported(deviceId)
        MessageKind.STATUS -> statusTopic
        MessageKind.COMMAND_RESULT -> cmdResTopic
    }
}
