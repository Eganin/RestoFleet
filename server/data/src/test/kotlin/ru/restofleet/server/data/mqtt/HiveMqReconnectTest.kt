package ru.restofleet.server.data.mqtt

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import ru.restofleet.server.data.ingest.InboundMessage
import ru.restofleet.server.data.scenario.eventually
import ru.restofleet.server.data.support.Samples
import ru.restofleet.server.data.support.TestBroker
import ru.restofleet.server.data.support.TestBroker.connectAs
import ru.restofleet.server.data.support.TestBroker.publishQos1
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.SharedSubscriptions
import ru.restofleet.shared.protocol.Topics
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Автопереподключение бэкенда после разрыва со стороны брокера (перезапуск брокера, вытеснение сессии): повторный
 * CONNECT несёт ту же учётку и `Clean Start = false`. Найдено на стенде (КМ2b): после `docker compose restart
 * mosquitto` HiveMQ переподключался без логина и пароля и получал «not authorised» бесконечно.
 */
class HiveMqReconnectTest {
    private val clientId = "backend-reconnect-${UUID.randomUUID()}"
    private val client = HiveMqClient(TestBroker.backendConfig(clientId), null)
    private val received = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        runBlocking { client.disconnect() }
        TestBroker.purgeSession(clientId)
    }

    @Test
    fun `backend reconnects with its credentials and keeps receiving after the broker drops it`() {
        client.onDeviceMessage { message: InboundMessage ->
            received += message.topic
            message.ack()
        }
        runBlocking {
            client.connect()
            client.subscribe(SharedSubscriptions.backend())
        }

        // Разрыв со стороны брокера: другое соединение с тем же clientId вытесняет бэкенд и уходит
        val intruder = TestBroker.deviceClient(clientId)
        assertThat(intruder.connectAs("backend", TestBroker.BACKEND_PASSWORD)).isNull()
        intruder.disconnect()

        assertWithMessage("reconnected").that(eventually(timeoutMs = 15_000) { client.isConnected }).isTrue()
        val (device, password) = deviceAccount()
        val publisher = TestBroker.deviceClient(device.value)
        assertThat(publisher.connectAs(device.value, password)).isNull()
        val topic = Topics.telemetry(device)
        publisher.publishQos1(topic, """{"ts":1793865630000,"seq":1}""")

        assertWithMessage("delivered after reconnect").that(eventually { topic in received }).isTrue()
        publisher.disconnect()
    }

    /** Учётка устройства в DynSec (отдельным соединением-администратором, как в `HiveMqIngestTest`). */
    private fun deviceAccount(): Pair<DeviceId, String> {
        val admin = HiveMqClient(TestBroker.backendConfig(), trust = null)
        val id = Samples.deviceId()
        val password = "pw-${Samples.uuid()}"
        runBlocking {
            admin.connect()
            admin.subscribe(listOf(DynSecBrokerAccounts.RESPONSE_TOPIC))
            check(DynSecBrokerAccounts(admin, 5_000).createDevice(id, password) is Outcome.Success)
            admin.disconnect()
        }
        return id to password
    }
}
