package ru.restofleet.server.data.scenario

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Test
import ru.restofleet.server.data.ingest.IngestMetrics
import ru.restofleet.server.data.mqtt.MqttBackend
import ru.restofleet.server.data.support.KoinBackend
import ru.restofleet.server.data.support.Samples
import ru.restofleet.server.data.support.TestBroker
import ru.restofleet.server.data.support.TestBroker.connectAs
import ru.restofleet.server.data.support.TestBroker.publishQos1
import ru.restofleet.server.data.support.TestDatabase
import ru.restofleet.server.data.support.registeredDevice
import ru.restofleet.shared.protocol.Topics
import java.util.UUID

/**
 * V-1 (валидация КМ5): после перезапуска Mosquitto бэкенд переподключается с `session present: true`, но Mosquitto
 * 2.1.2 не восстанавливает из persistence shared-подписки `$share/backend/…`, и ingest молча стоял. Бэкенд оформляет
 * подписки на каждом (пере)подключении; каждое сообщение устройства доходит до него ровно один раз.
 */
class BrokerRestartIngestTest {
    private val clientId = "backend-restart-${UUID.randomUUID()}"
    private val config = TestBroker.backendConfig(clientId)

    @Before
    fun clean() = TestDatabase.reset()

    @After
    fun purge() = TestBroker.purgeSession(clientId)

    @Test
    fun `after a broker restart the backend subscribes again and device messages reach the database once`() {
        val (id, password) = registeredDevice()
        KoinBackend(config).start().use { backend ->
            val before = TestBroker.deviceClient(id.value)
            assertThat(before.connectAs(id.value, password)).isNull()
            before.publishQos1(Topics.telemetry(id), telemetry(1))
            assertThat(eventually { telemetryRows() == 1 }).isTrue()

            TestBroker.restart()

            val mqtt = backend.get<MqttBackend>()
            assertWithMessage("backend reconnected and subscribed").that(eventually(20_000) { mqtt.isReady }).isTrue()
            // Устройство переподключается (агент — автопереподключением), его постоянная сессия пережила рестарт
            val after = TestBroker.deviceClient(id.value)
            assertThat(after.connectAs(id.value, password)).isNull()
            after.publishQos1(Topics.telemetry(id), telemetry(2))
            assertWithMessage("telemetry published after the restart is in the database")
                .that(eventually(15_000) { telemetryRows() == 2 }).isTrue()

            // Без дублей: повторная подписка не добавила второй доставки того же сообщения
            after.publishQos1(Topics.telemetry(id), telemetry(3))
            assertThat(eventually { telemetryRows() == 3 }).isTrue()
            Thread.sleep(500)
            assertThat(backend.get<IngestMetrics>().received.get()).isEqualTo(3)
            after.disconnect()
        }
    }

    private fun telemetry(seq: Int) = """{"ts":${Samples.T0 + seq * 1_000},"seq":$seq}"""

    private fun telemetryRows(): Int = TestDatabase.scalar("SELECT count(*) FROM telemetry")!!.toInt()
}
