package ru.restofleet.server.data.mqtt

import com.google.common.truth.Truth.assertThat
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5PubAckException
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5SubAckException
import com.hivemq.client.mqtt.mqtt5.message.publish.puback.Mqtt5PubAckReasonCode
import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.restofleet.server.data.ingest.InboundMessage
import ru.restofleet.server.data.support.Samples
import ru.restofleet.server.data.support.TestBroker
import ru.restofleet.server.data.support.TestBroker.connectAs
import ru.restofleet.server.data.support.TestBroker.next
import ru.restofleet.server.data.support.TestBroker.publishQos1
import ru.restofleet.server.domain.shadow.DefaultDesired
import ru.restofleet.server.domain.shadow.DesiredState
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.SharedSubscriptions
import ru.restofleet.shared.protocol.Topics
import ru.restofleet.shared.protocol.dto.CommandRequestDto
import ru.restofleet.shared.protocol.dto.ShadowDesiredDto
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith

/** ACL роли `device` (P5, P6) и публикации бэкенда (`HiveMqPublisher`) на живом брокере. */
internal class BrokerAclAndPublisherTest : BrokerTest() {

    /** Бэкенд с shared-подпиской: всё, что дошло до ingest, — в очередь. */
    private fun ingestingBackend(): Pair<HiveMqClient, LinkedBlockingQueue<InboundMessage>> {
        val received = LinkedBlockingQueue<InboundMessage>()
        val backend = backend()
        backend.onDeviceMessage { it.ack(); received += it }
        runBlocking { backend.subscribe(SharedSubscriptions.backend()) }
        return backend to received
    }

    @Test
    fun `device reaches the backend on its own channels only`() {
        val (backend, received) = ingestingBackend()
        val accounts = accounts(backend)
        val (me, myPassword) = provisionedDevice(accounts)
        val (other, _) = provisionedDevice(accounts)
        val device = client(me.value).also { assertThat(it.connectAs(me.value, myPassword)).isNull() }

        val own = device.publishQos1(Topics.telemetry(me), """{"ts":1,"seq":1}""")
        // MQTT 5: на запрещённую публикацию брокер отвечает PUBACK «not authorized» и не пересылает её
        val foreign = assertFailsWith<Mqtt5PubAckException> {
            device.publishQos1(Topics.telemetry(other), """{"ts":2,"seq":2}""")
        }
        val toDevice = assertFailsWith<Mqtt5PubAckException> {
            device.publishQos1(Topics.cmdReq(other), """{"cmdId":"c-x","type":"reboot"}""")
        }

        assertThat(own.error.isPresent).isFalse()
        assertThat(received.poll(5, TimeUnit.SECONDS)!!.topic).isEqualTo(Topics.telemetry(me))
        assertThat(foreign.mqttMessage.reasonCode).isEqualTo(Mqtt5PubAckReasonCode.NOT_AUTHORIZED)
        assertThat(toDevice.mqttMessage.reasonCode).isEqualTo(Mqtt5PubAckReasonCode.NOT_AUTHORIZED)
        assertThat(received.poll(1_500, TimeUnit.MILLISECONDS)).isNull()
    }

    @Test
    fun `device cannot subscribe to another device or to the whole tree`() {
        val accounts = accounts(backend())
        val (me, password) = provisionedDevice(accounts)
        val (other, _) = provisionedDevice(accounts)
        val device = client(me.value).also { assertThat(it.connectAs(me.value, password)).isNull() }

        device.subscribeWith().topicFilter(Topics.cmdReq(me)).qos(MqttQos.AT_LEAST_ONCE).send()
        listOf(Topics.cmdReq(other), "rf/v1/dev/+/telemetry", "#").forEach { filter ->
            assertFailsWith<Mqtt5SubAckException>(filter) {
                device.subscribeWith().topicFilter(filter).qos(MqttQos.AT_LEAST_ONCE).send()
            }
        }
    }

    @Test
    fun `desired is retained with its version and cleared by an empty payload`() {
        val backend = backend()
        val accounts = accounts(backend)
        val publisher = HiveMqPublisher(backend, clock)
        val (me, password) = provisionedDevice(accounts)

        assertThat(runBlocking { publisher.publishDesired(me, DesiredState(7, DefaultDesired.document)) })
            .isEqualTo(Outcome.Success(Unit))

        val late = client(me.value).also { assertThat(it.connectAs(me.value, password, cleanStart = true)).isNull() }
        late.publishes(MqttGlobalPublishFilter.ALL).use { inbox ->
            late.subscribeWith().topicFilter(Topics.shadowDesired(me)).qos(MqttQos.AT_LEAST_ONCE).send()
            val retained = inbox.next(5_000)!!
            assertThat(retained.isRetain).isTrue()
            val dto = RfJson.decodeFromString(ShadowDesiredDto.serializer(), retained.payloadAsBytes.decodeToString())
            assertThat(dto.version).isEqualTo(7)
        }
        late.disconnect()

        assertThat(runBlocking { publisher.clearRetained(me) }).isEqualTo(Outcome.Success(Unit))

        val after = client(me.value).also { assertThat(it.connectAs(me.value, password, cleanStart = true)).isNull() }
        after.publishes(MqttGlobalPublishFilter.ALL).use { inbox ->
            after.subscribeWith().topicFilter(Topics.shadowDesired(me)).qos(MqttQos.AT_LEAST_ONCE).send()
            assertThat(inbox.next(1_500)).isNull()
        }
    }

    @Test
    fun `cmd req carries response topic, correlation data and message expiry`() {
        val backend = backend()
        val accounts = accounts(backend)
        val publisher = HiveMqPublisher(backend, clock)
        val (me, password) = provisionedDevice(accounts)
        val device = client(me.value).also { assertThat(it.connectAs(me.value, password, cleanStart = true)).isNull() }
        val command = Samples.command(me, createdAt = clock.now, expiresAt = clock.now + 120_000)

        device.publishes(MqttGlobalPublishFilter.ALL).use { inbox ->
            device.subscribeWith().topicFilter(Topics.cmdReq(me)).qos(MqttQos.AT_LEAST_ONCE).send()
            assertThat(runBlocking { publisher.publishCommand(command) }).isEqualTo(Outcome.Success(Unit))

            val req = inbox.next(5_000)!!
            assertThat(req.responseTopic.get().toString()).isEqualTo(Topics.cmdRes(me))
            val correlation = req.correlationData.get().let { b -> ByteArray(b.remaining()).also { b.get(it) } }
            assertThat(correlation.decodeToString())
                .isEqualTo(command.cmdId)
            assertThat(req.messageExpiryInterval.asLong).isIn(119L..120L)
            val dto = RfJson.decodeFromString(CommandRequestDto.serializer(), req.payloadAsBytes.decodeToString())
            assertThat(dto.cmdId).isEqualTo(command.cmdId)
            assertThat(dto.expiresAt).isEqualTo(command.expiresAt)
            assertThat(dto.params["n"].toString()).isEqualTo("1")
        }
    }

    @Test
    fun `publishing without a connection is broker unavailable, not an exception`() {
        val offline = HiveMqClient(TestBroker.backendConfig(), trust = null)
        val publisher = HiveMqPublisher(offline, clock)

        val desired = DesiredState(1, DefaultDesired.document)
        assertThat(runBlocking { publisher.publishDesired(Samples.deviceId(), desired) })
            .isInstanceOf(Outcome.Failure::class.java)
    }
}
