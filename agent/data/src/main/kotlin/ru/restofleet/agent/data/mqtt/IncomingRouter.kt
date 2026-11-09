package ru.restofleet.agent.data.mqtt

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ru.restofleet.agent.data.mqtt.mapping.CommandMapper
import ru.restofleet.agent.data.mqtt.mapping.ShadowMapper
import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.model.IncomingCommand
import ru.restofleet.shared.kernel.Outcome

/**
 * Разбор входящих публикаций и выдача их потребителю: холодные потоки для одного потребителя, ack брокеру уходит после
 * возврата потребителя (`emit` не возвращается, пока блок `collect` не завершён). Пока потребитель не подключён,
 * сообщения ждут в очереди без ack. Нечитаемое сообщение, пустой retained `desired` (очистка) и чужие топики
 * подтверждаются и отбрасываются: брокер повторял бы их бесконечно. В лог идёт только код причины.
 */
internal class IncomingRouter(private val log: (String) -> Unit) {
    private val commandChannel = Channel<Delivery<IncomingCommand>>(Channel.UNLIMITED)
    private val desiredChannel = Channel<Delivery<DesiredShadow>>(Channel.UNLIMITED)

    val commands: Flow<IncomingCommand> = deliveries(commandChannel)
    val desired: Flow<DesiredShadow> = deliveries(desiredChannel)

    private class Delivery<T>(val value: T, val ack: () -> Unit)

    fun route(m: IncomingMessage, topics: TopicRouter) {
        when (m.topic) {
            topics.cmdReqTopic -> routeCommand(m)
            topics.desiredTopic -> routeDesired(m)
            else -> drop(m, "unexpected_topic")
        }
    }

    private fun routeCommand(m: IncomingMessage) {
        when (val parsed = CommandMapper.parse(m.payload, m.responseTopic, m.correlationData)) {
            is Outcome.Success -> commandChannel.trySend(Delivery(parsed.value, m.ack))
            is Outcome.Failure -> drop(m, parsed.error.code)
        }
    }

    private fun routeDesired(m: IncomingMessage) {
        if (m.payload.isEmpty()) {
            m.ack() // очистка retained: применять нечего
            return
        }
        when (val parsed = ShadowMapper.parse(m.payload)) {
            is Outcome.Success -> desiredChannel.trySend(Delivery(parsed.value, m.ack))
            is Outcome.Failure -> drop(m, parsed.error.code)
        }
    }

    private fun drop(m: IncomingMessage, code: String) {
        log("dropped incoming message: $code")
        m.ack()
    }

    private fun <T> deliveries(channel: Channel<Delivery<T>>): Flow<T> = flow {
        for (delivery in channel) {
            emit(delivery.value)
            delivery.ack()
        }
    }
}
