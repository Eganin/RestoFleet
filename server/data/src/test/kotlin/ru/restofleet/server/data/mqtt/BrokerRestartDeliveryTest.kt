package ru.restofleet.server.data.mqtt

import com.google.common.truth.Truth.assertThat
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.restofleet.server.data.support.TestBroker
import ru.restofleet.server.data.support.TestBroker.connectAs
import ru.restofleet.server.data.support.TestBroker.next
import ru.restofleet.server.domain.shadow.DefaultDesired
import ru.restofleet.server.domain.shadow.DesiredState
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.Topics
import ru.restofleet.shared.protocol.dto.ShadowDesiredDto

/**
 * FR-BR-08 (долг К, реш. 326): перезапуск брокера (SIGTERM, `persistence true`) не теряет retained `desired` —
 * устройство, подключившееся после рестарта, получает последнюю версию. Очередь QoS 1 отключённого устройства
 * рестарт не переживает (Mosquitto 2.1.2 не восстанавливает его сессию, реш. 469) — это закреплено в отчёте,
 * а не тестом.
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
        assertThat(device.connectAs(me.value, password, cleanStart = true)).isNull()
        device.publishes(MqttGlobalPublishFilter.ALL).use { inbox ->
            device.subscribeWith().topicFilter(Topics.shadowDesired(me)).qos(MqttQos.AT_LEAST_ONCE).send()
            val retained = inbox.next(5_000)!!
            assertThat(retained.isRetain).isTrue()
            val payload = retained.payloadAsBytes.decodeToString()
            assertThat(RfJson.decodeFromString(ShadowDesiredDto.serializer(), payload).version).isEqualTo(9)
        }
    }
}
