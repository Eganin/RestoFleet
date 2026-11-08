package ru.restofleet.agent.domain.model

/**
 * Вид исходящего сообщения. Домен не знает строк топиков: топик по виду выбирает data-слой.
 *
 * [priority]: чем меньше, тем важнее (0 — события, ответы на команды, shadow, статус). На это опирается порядок
 * досылки очереди [ru.restofleet.agent.domain.port.Outbox] в 3.3/3.4: сначала важное, телеметрия — в последнюю очередь.
 */
enum class MessageKind(val priority: Int) {
    EVENT(0),
    COMMAND_RESULT(0),
    SHADOW_REPORTED(0),
    STATUS(0),
    PROBE(1),
    TELEMETRY(2),
}

/**
 * Готовое к отправке сообщение: [payload] уже закодирован. [responseTopic] нужен только ответу на команду,
 * [correlationData] — cmdId в виде байтов (MQTT 5). [createdAt] — wall clock, epoch millis.
 *
 * Содержит массивы, поэтому `equals`/`hashCode` сравнивают их по содержимому, а не по ссылке.
 */
data class OutgoingMessage(
    val kind: MessageKind,
    val payload: ByteArray,
    val qos: Int,
    val retain: Boolean = false,
    val correlationData: ByteArray? = null,
    val responseTopic: String? = null,
    val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean = this === other || (
        other is OutgoingMessage &&
            kind == other.kind &&
            payload.contentEquals(other.payload) &&
            qos == other.qos &&
            retain == other.retain &&
            correlationData.contentEqualsNullable(other.correlationData) &&
            responseTopic == other.responseTopic &&
            createdAt == other.createdAt
        )

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = HASH_PRIME * result + payload.contentHashCode()
        result = HASH_PRIME * result + qos
        result = HASH_PRIME * result + retain.hashCode()
        result = HASH_PRIME * result + (correlationData?.contentHashCode() ?: 0)
        result = HASH_PRIME * result + (responseTopic?.hashCode() ?: 0)
        result = HASH_PRIME * result + createdAt.hashCode()
        return result
    }
}

private const val HASH_PRIME = 31

internal fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    if (this == null || other == null) this == null && other == null else contentEquals(other)
