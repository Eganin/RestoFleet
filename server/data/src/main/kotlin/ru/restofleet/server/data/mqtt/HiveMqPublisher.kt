package ru.restofleet.server.data.mqtt

import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.restofleet.shared.protocol.ShadowJson
import ru.restofleet.server.domain.command.Command
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.ServerError.BrokerUnavailable
import ru.restofleet.server.domain.device.MessagePublisher
import ru.restofleet.server.domain.shadow.DesiredState
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.Topics
import ru.restofleet.shared.protocol.dto.CommandRequestDto
import ru.restofleet.shared.shadow.ShadowKeys

/**
 * Публикации бэкенда (QoS 1, ожидание PUBACK): retained `desired` с `version` (FR-BE-21), очистка retained `desired`
 * и `status` пустым payload (P8), `cmd/req` со свойствами MQTT 5 (FR-BE-40). Отказ — [ServerError.BrokerUnavailable].
 */
class HiveMqPublisher(private val client: HiveMqClient, private val clock: Clock) : MessagePublisher {
    private val log = KotlinLogging.logger {}

    override suspend fun publishDesired(id: DeviceId, desired: DesiredState): Outcome<Unit, BrokerUnavailable> {
        val body = JsonObject(
            mapOf(ShadowKeys.VERSION to JsonPrimitive(desired.version)) + ShadowJson.toJson(desired.document),
        )
        val payload = RfJson.encodeToString(JsonObject.serializer(), body).toByteArray()
        return send(retained(Topics.shadowDesired(id), payload))
    }

    override suspend fun clearRetained(id: DeviceId): Outcome<Unit, BrokerUnavailable> {
        val desired = send(retained(Topics.shadowDesired(id), ByteArray(0)))
        val status = send(retained(Topics.status(id), ByteArray(0)))
        return if (desired is Outcome.Failure) desired else status
    }

    override suspend fun publishCommand(command: Command): Outcome<Unit, BrokerUnavailable> {
        val dto = CommandRequestDto(
            cmdId = command.cmdId,
            type = command.type,
            params = ShadowJson.toJson(command.params),
            expiresAt = command.expiresAt,
        )
        val publish = Mqtt5Publish.builder()
            .topic(Topics.cmdReq(command.deviceId))
            .qos(MqttQos.AT_LEAST_ONCE)
            .payload(RfJson.encodeToString(CommandRequestDto.serializer(), dto).toByteArray())
            .responseTopic(Topics.cmdRes(command.deviceId))
            .correlationData(command.cmdId.toByteArray())
            .messageExpiryInterval(command.expirySecondsAt(clock.nowMillis()))
            .build()
        return send(publish)
    }

    private fun retained(topic: String, payload: ByteArray): Mqtt5Publish = Mqtt5Publish.builder()
        .topic(topic)
        .qos(MqttQos.AT_LEAST_ONCE)
        .retain(true)
        .payload(payload)
        .build()

    private suspend fun send(publish: Mqtt5Publish): Outcome<Unit, BrokerUnavailable> {
        val error = client.publish(publish) ?: return Outcome.Success(Unit)
        log.warn { "publish to ${publish.topic} failed: $error" }
        return Outcome.Failure(BrokerUnavailable("publish"))
    }
}
