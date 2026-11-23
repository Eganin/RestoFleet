package ru.restofleet.server.data.mqtt

import com.google.common.truth.Truth.assertThat
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAck
import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.restofleet.server.data.support.Samples
import ru.restofleet.server.data.support.TestBroker
import ru.restofleet.server.data.support.TestBroker.connectAs
import ru.restofleet.server.data.support.TestBroker.next
import ru.restofleet.server.domain.shadow.DefaultDesired
import ru.restofleet.server.domain.shadow.DesiredState
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.Topics
import ru.restofleet.shared.protocol.dto.CommandRequestDto
import ru.restofleet.shared.protocol.dto.ShadowDesiredDto

/**
 * FR-BR-08 (долг К, реш. 326, 469, 530): перезапуск брокера (SIGTERM, `persistence true`) не теряет ни retained
 * `desired`, ни очередь QoS 1 отключённого устройства, если устройство, как агент, подключается с Clean Start = 0
 * и Session Expiry > 0 (`MqttSessionConfig`). Эксперимент ПМ12 показал, что прежняя «потеря очереди» — артефакт
 * теста: Mosquitto 2.1.2 не пишет в persistence сессию, созданную подключением с Clean Start = 1 (в памяти она живёт
 * до рестарта), а Clean Start = 1 после рестарта сессию отбрасывает по MQTT 5. `Message Expiry` и способ создания
 * клиента DynSec на результат не влияют.
 */
internal class BrokerRestartDeliveryTest : BrokerTest() {
    @Test
    fun `retained desired survives a broker restart`() {
        val backend = backend()
        val accounts = accounts(backend)
        val publisher = HiveMqPublisher(backend, clock)
        val (me, password) = provisionedDevice(accounts)
        assertThat(runBlocking { publisher.publishDesired(me, DesiredState(9, DefaultDesired.document)) })
            .isEqualTo(Outcome.Success(Unit))

        TestBroker.restart()

        val device = client(me.value)
        assertThat(device.connectAs(me.value, password)).isNull()
        device.publishes(MqttGlobalPublishFilter.ALL).use { inbox ->
            device.subscribeWith().topicFilter(Topics.shadowDesired(me)).qos(MqttQos.AT_LEAST_ONCE).send()
            val retained = inbox.next(5_000)!!
            assertThat(retained.isRetain).isTrue()
            val payload = retained.payloadAsBytes.decodeToString()
            assertThat(RfJson.decodeFromString(ShadowDesiredDto.serializer(), payload).version).isEqualTo(9)
        }
    }

    @Test
    fun `command queued for an offline device is delivered after a broker restart`() {
        val backend = backend()
        val publisher = HiveMqPublisher(backend, clock)
        val (me, password) = provisionedDevice(accounts(backend))
        subscribeAndGoOffline(me, password, cleanStart = false)
        val command = Samples.command(me, createdAt = clock.now, expiresAt = clock.now + 300_000)
        assertThat(runBlocking { publisher.publishCommand(command) }).isEqualTo(Outcome.Success(Unit))

        TestBroker.restart()

        val again = client(me.value)
        again.publishes(MqttGlobalPublishFilter.ALL).use { inbox ->
            assertThat(again.reconnect(me, password).isSessionPresent).isTrue()
            val req = inbox.next(5_000)!!
            val dto = RfJson.decodeFromString(CommandRequestDto.serializer(), req.payloadAsBytes.decodeToString())
            assertThat(dto.cmdId).isEqualTo(command.cmdId)
        }
    }

    /** Свойство брокера, из-за которого агенту нельзя начинать сессию с Clean Start = 1 (реш. 530). */
    @Test
    fun `session created with clean start is not persisted by Mosquitto 2_1_2`() {
        val backend = backend()
        val (me, password) = provisionedDevice(accounts(backend))
        subscribeAndGoOffline(me, password, cleanStart = true)

        TestBroker.restart()

        assertThat(client(me.value).reconnect(me, password).isSessionPresent).isFalse()
    }

    private fun subscribeAndGoOffline(me: DeviceId, password: String, cleanStart: Boolean) {
        val device = client(me.value)
        assertThat(device.connectAs(me.value, password, cleanStart = cleanStart)).isNull()
        device.subscribeWith().topicFilter(Topics.cmdReq(me)).qos(MqttQos.AT_LEAST_ONCE).send()
        device.disconnect()
    }

    private fun Mqtt5BlockingClient.reconnect(me: DeviceId, password: String): Mqtt5ConnAck = connectWith()
        .cleanStart(false)
        .sessionExpiryInterval(SESSION_EXPIRY_SEC)
        .simpleAuth().username(me.value).password(password.toByteArray()).applySimpleAuth()
        .send()

    private companion object {
        const val SESSION_EXPIRY_SEC = 3_600L
    }
}
