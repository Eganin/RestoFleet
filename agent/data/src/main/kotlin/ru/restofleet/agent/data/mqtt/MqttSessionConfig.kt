package ru.restofleet.agent.data.mqtt

import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.IdGenerator
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.Topics
import ru.restofleet.shared.protocol.dto.DeviceStateDto
import ru.restofleet.shared.protocol.dto.StatusDto

/**
 * Параметры одного подключения. Пароля здесь нет: он берётся из [BrokerSettings] только в момент CONNECT и не
 * попадает ни в `toString`, ни в will. Содержит массив, поэтому `equals`/`hashCode` — по содержимому.
 */
data class MqttSessionConfig(
    val clientId: String,
    val host: String,
    val port: Int,
    val keepAliveSec: Int,
    val cleanStart: Boolean,
    val sessionExpirySec: Long,
    val willTopic: String,
    val willPayload: ByteArray,
    val willRetain: Boolean,
    val willQos: Int,
    val connId: String,
) {
    override fun equals(other: Any?): Boolean = this === other || (
        other is MqttSessionConfig &&
            clientId == other.clientId &&
            host == other.host &&
            port == other.port &&
            keepAliveSec == other.keepAliveSec &&
            cleanStart == other.cleanStart &&
            sessionExpirySec == other.sessionExpirySec &&
            willTopic == other.willTopic &&
            willPayload.contentEquals(other.willPayload) &&
            willRetain == other.willRetain &&
            willQos == other.willQos &&
            connId == other.connId
        )

    override fun hashCode(): Int = listOf(
        clientId, host, port, keepAliveSec, cleanStart, sessionExpirySec, willTopic, willPayload.contentHashCode(),
        willRetain, willQos, connId,
    ).hashCode()

    override fun toString(): String =
        "MqttSessionConfig(clientId=$clientId, endpoint=$host:$port, connId=$connId, cleanStart=$cleanStart)"
}

/** Параметры сессии ТЗ 7.3 и брифа 3.6: keep-alive 30 с (`max_keepalive` брокера 120), 24 ч, `cleanStart=false`. */
object MqttSessionConfigFactory {
    const val KEEP_ALIVE_SEC = 30
    const val SESSION_EXPIRY_SEC = 86_400L
    const val STATUS_QOS = 1

    fun from(s: BrokerSettings, connId: String, clock: Clock): MqttSessionConfig {
        val will = StatusDto(DeviceStateDto.OFFLINE, ts = clock.nowMillis(), connId = connId, reason = "lwt")
        return MqttSessionConfig(
            clientId = s.deviceId.value,
            host = s.host,
            port = s.port,
            keepAliveSec = KEEP_ALIVE_SEC,
            cleanStart = false,
            sessionExpirySec = SESSION_EXPIRY_SEC,
            willTopic = Topics.status(s.deviceId),
            willPayload = RfJson.encodeToString(StatusDto.serializer(), will).encodeToByteArray(),
            willRetain = true,
            willQos = STATUS_QOS,
            connId = connId,
        )
    }
}

/**
 * Выдаёт конфигурацию с новым `connId` для каждого (пере)подключения (M3): will и `online` одного подключения несут
 * один и тот же `connId`, а соединения между собой различаются.
 */
class ConnectionConfigs(
    private val settings: BrokerSettings,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    fun next(): MqttSessionConfig = MqttSessionConfigFactory.from(settings, ids.newId(), clock)
}
