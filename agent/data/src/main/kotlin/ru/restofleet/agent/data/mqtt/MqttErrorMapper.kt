package ru.restofleet.agent.data.mqtt

import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5ConnAckException
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAckReasonCode
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * Классификация сбоев клиента HiveMQ в коды Lab. В результат попадает только класс исключения или имя кода причины:
 * текст исключения (адрес, учётка, значения) наружу не выходит.
 */
internal object MqttErrorMapper {
    fun fromConnect(cause: Throwable): MqttError {
        val chain = generateSequence(cause) { it.cause?.takeIf { next -> next !== it } }.take(MAX_CHAIN).toList()
        val refusal = chain.filterIsInstance<Mqtt5ConnAckException>().firstOrNull()
        return when {
            refusal != null -> fromConnAck(refusal)
            chain.any { it is SSLException || it is CertificateException } -> MqttError.TlsError
            else -> MqttError.Failed(cause::class.java.simpleName.ifEmpty { "unknown" })
        }
    }

    /** [isError] — `reasonCode.isError`; PUBACK `0x87` (нет прав на топик) — `not_authorized`. */
    fun fromPublishAck(reasonCodeName: String, reasonCode: Int, isError: Boolean): MqttError? = when {
        !isError -> null
        reasonCode == NOT_AUTHORIZED_CODE -> MqttError.NotAuthorized
        else -> MqttError.Failed("puback_${reasonCodeName.lowercase()}")
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
    private const val MAX_CHAIN = 10
}
