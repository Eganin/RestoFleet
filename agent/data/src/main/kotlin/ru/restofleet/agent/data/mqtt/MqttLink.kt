package ru.restofleet.agent.data.mqtt

import kotlinx.coroutines.channels.ReceiveChannel
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.shared.kernel.Outcome
import javax.net.ssl.TrustManagerFactory

/** Событие соединения, которое клиент сообщает после первого успешного подключения. */
internal sealed interface LinkEvent {
    /** CONNACK получен после автопереподключения; [connId] — подключения, чей will сейчас действует. */
    data class Connected(val connId: String) : LinkEvent

    /** Соединение потеряно; клиент может переподключаться сам. */
    data class Lost(val error: MqttError) : LinkEvent

    /** Клиент больше не переподключается (отказ учётки или остановка): нужна новая попытка сверху. */
    data class Closed(val error: MqttError) : LinkEvent
}

/** Входящая публикация. [ack] подтверждает её брокеру (ручное подтверждение); безопасен при потерянной сессии. */
internal class IncomingMessage(
    val topic: String,
    val payload: ByteArray,
    val responseTopic: String?,
    val correlationData: ByteArray?,
    val ack: () -> Unit,
)

internal class OutboundMessage(
    val topic: String,
    val payload: ByteArray,
    val qos: Int,
    val retain: Boolean,
    val correlationData: ByteArray?,
)

/**
 * Тонкая граница к MQTT-клиенту: вся логика сессии живёт выше и тестируется с фейком. Одно подключение: после
 * [close] объект не используется. Не бросает: сбои — [Outcome.Failure] с [MqttError].
 */
internal interface MqttLink {
    val events: ReceiveChannel<LinkEvent>
    val incoming: ReceiveChannel<IncomingMessage>

    /** Первое подключение: при успехе возвращает `connId` этого подключения. */
    suspend fun connect(): Outcome<String, MqttError>

    /** Подписка QoS 1 на все [topics]. */
    suspend fun subscribe(topics: List<String>): Outcome<Unit, MqttError>

    suspend fun publish(message: OutboundMessage): Outcome<Unit, MqttError>

    /** Штатное отключение (DISCONNECT 0x00: будет не срабатывает) и освобождение ресурсов. */
    suspend fun close()
}

internal fun interface MqttLinkFactory {
    fun create(settings: BrokerSettings, trust: TrustManagerFactory, configs: ConnectionConfigs): MqttLink
}

/** Источник PEM CA стенда: debug читает `R.raw.stand_ca`, release возвращает `null` (системным CA не доверяем). */
fun interface CaSource {
    fun pem(): ByteArray?
}
