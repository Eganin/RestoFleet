package ru.restofleet.agent.data.mqtt

import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5ConnAckException
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5DisconnectException
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5PubAckException
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5SubAckException
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAckReasonCode
import com.hivemq.client.mqtt.mqtt5.message.disconnect.Mqtt5DisconnectReasonCode
import com.hivemq.client.mqtt.mqtt5.message.subscribe.suback.Mqtt5SubAckReasonCode
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * Классификация сбоев клиента HiveMQ в коды Lab. В результат попадает только класс исключения или имя кода причины:
 * текст исключения (адрес, учётка, значения) наружу не выходит.
 */
internal object MqttErrorMapper {
    /**
     * Любое исключение клиента: подключения, публикации, подписки, обрыва. Ответ брокера с кодом ищется по всей цепочке
     * причин. HiveMQ 1.4.2 сообщает ошибочный PUBACK и SUBACK только так: future завершается `Mqtt5PubAckException`
     * или `Mqtt5SubAckException` (решение 170), а не результатом с кодом.
     */
    fun fromConnect(cause: Throwable): MqttError {
        val chain = generateSequence(cause) { it.cause?.takeIf { next -> next !== it } }.take(MAX_CHAIN).toList()
        return chain.firstNotNullOfOrNull(::fromBrokerReply)
            ?: if (chain.any { it is SSLException || it is CertificateException }) {
                MqttError.TlsError
            } else {
                MqttError.Failed(cause::class.java.simpleName.ifEmpty { "unknown" })
            }
    }

    /**
     * [isError] — `reasonCode.isError`. `0x87` (нет прав на топик) — `not_authorized`; `0x90`, `0x97`, `0x99` —
     * окончательный отказ `puback_<имя>` (решение 171); остальные коды ошибок (`0x80`, `0x83`, `0x91`) — временный
     * сбой.
     */
    fun fromPublishAck(reasonCodeName: String, reasonCode: Int, isError: Boolean): MqttError? = when {
        !isError -> null
        reasonCode == NOT_AUTHORIZED_CODE -> MqttError.NotAuthorized
        reasonCode in FINAL_PUBACK_CODES -> MqttError.Rejected(reasonCodeName.lowercase())
        else -> MqttError.Failed("puback_${reasonCodeName.lowercase()}")
    }

    /** Коды SUBACK: `null`, если ошибок нет; `0x87` среди них — `not_authorized`, иначе имя первой ошибки. */
    fun fromSubAck(codes: List<Mqtt5SubAckReasonCode>): MqttError? {
        val failed = codes.filter { it.isError }
        return when {
            failed.isEmpty() -> null
            Mqtt5SubAckReasonCode.NOT_AUTHORIZED in failed -> MqttError.NotAuthorized
            else -> MqttError.Failed("suback_${failed.first().name.lowercase()}")
        }
    }

    private fun fromBrokerReply(e: Throwable): MqttError? = when (e) {
        is Mqtt5ConnAckException -> fromConnAck(e)
        is Mqtt5PubAckException -> e.mqttMessage.reasonCode.let { fromPublishAck(it.name, it.code, it.isError) }
        is Mqtt5SubAckException -> fromSubAck(e.mqttMessage.reasonCodes)
        is Mqtt5DisconnectException -> MqttError.SessionTakenOver.takeIf {
            e.mqttMessage.reasonCode == Mqtt5DisconnectReasonCode.SESSION_TAKEN_OVER
        }
        else -> null
    }

    private fun fromConnAck(e: Mqtt5ConnAckException): MqttError = when (val code = e.mqttMessage.reasonCode) {
        Mqtt5ConnAckReasonCode.BAD_USER_NAME_OR_PASSWORD,
        Mqtt5ConnAckReasonCode.NOT_AUTHORIZED,
        Mqtt5ConnAckReasonCode.CLIENT_IDENTIFIER_NOT_VALID,
        Mqtt5ConnAckReasonCode.BANNED,
        -> MqttError.AuthFailed
        else -> MqttError.Failed("connack_${code.name.lowercase()}")
    }

    private const val NOT_AUTHORIZED_CODE = 0x87

    /** `TOPIC_NAME_INVALID`, `QUOTA_EXCEEDED`, `PAYLOAD_FORMAT_INVALID`: повтор того же сообщения не поможет. */
    private val FINAL_PUBACK_CODES = setOf(0x90, 0x97, 0x99)
    private const val MAX_CHAIN = 10
}
