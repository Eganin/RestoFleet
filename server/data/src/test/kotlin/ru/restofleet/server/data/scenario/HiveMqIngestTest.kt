package ru.restofleet.server.data.scenario

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import ru.restofleet.server.data.config.IngestConfig
import ru.restofleet.server.data.db.ExposedDeviceRepository
import ru.restofleet.server.data.db.ExposedRestaurantRepository
import ru.restofleet.server.data.ingest.InboundProcessor
import ru.restofleet.server.data.ingest.IngestMetrics
import ru.restofleet.server.data.ingest.IngestPipeline
import ru.restofleet.server.data.mqtt.DynSecBrokerAccounts
import ru.restofleet.server.data.mqtt.HiveMqClient
import ru.restofleet.server.data.mqtt.MqttBackend
import ru.restofleet.server.data.support.KoinBackend
import ru.restofleet.server.data.support.Samples
import ru.restofleet.server.data.support.TestBroker
import ru.restofleet.server.data.support.TestBroker.connectAs
import ru.restofleet.server.data.support.TestBroker.publishQos1
import ru.restofleet.server.data.support.TestDatabase
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.Topics
import java.sql.SQLTransientConnectionException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ingest на живом брокере (P10): сообщение подтверждается только после commit. Пока запись не удаётся, брокер держит
 * сообщения в постоянной сессии бэкенда и передоставляет их после перезапуска с тем же `clientId`.
 */
class HiveMqIngestTest {
    private val clientId = "backend-ingest-${UUID.randomUUID()}"
    private val config = TestBroker.backendConfig(clientId)
    private val device by lazy { registeredDevice() }

    @Before
    fun clean() = TestDatabase.reset()

    @After
    fun purge() = TestBroker.purgeSession(clientId)

    /** Устройство есть и в DynSec, и в реестре. */
    private fun registeredDevice(): Pair<DeviceId, String> {
        val admin = HiveMqClient(TestBroker.backendConfig(), trust = null)
        val id = Samples.deviceId()
        val password = "pw-${Samples.uuid()}"
        runBlocking {
            admin.connect()
            admin.subscribe(listOf(DynSecBrokerAccounts.RESPONSE_TOPIC))
            check(DynSecBrokerAccounts(admin, 5_000).createDevice(id, password) is Outcome.Success)
            admin.disconnect()
        }
        val restaurant = Samples.restaurant()
        TestDatabase.inTx {
            ExposedRestaurantRepository().insert(restaurant)
            ExposedDeviceRepository().insert(Samples.device(restaurant, id))
        }
        return id to password
    }

    private fun telemetryRows(): Int = TestDatabase.scalar("SELECT count(*) FROM telemetry")!!.toInt()

    @Test
    fun `failed commit leaves messages unacknowledged and a restarted backend gets them again`() {
        val (id, password) = device
        val attempts = AtomicInteger()
        // «БД недоступна»: каждая попытка записи пачки — временный сбой
        val dbDown = InboundProcessor { attempts.incrementAndGet(); throw SQLTransientConnectionException("db down") }
        val broken = HiveMqClient(config, trust = null)
        val metrics = IngestMetrics()
        val brokenBackend = MqttBackend(
            broken,
            IngestPipeline(dbDown, IngestConfig(batchWindowMs = 50, retryInitialMs = 50, retryMaxMs = 100), metrics),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        brokenBackend.start(scope)
        runBlocking { withTimeout(15_000) { brokenBackend.awaitReady() } }

        val agent = TestBroker.deviceClient(id.value)
        assertThat(agent.connectAs(id.value, password)).isNull()
        (1..3).forEach { agent.publishQos1(Topics.telemetry(id), """{"ts":${Samples.T0 + it * 1_000},"seq":$it}""") }

        assertThat(eventually { attempts.get() >= 3 && metrics.received.get() == 3L }).isTrue()
        assertThat(telemetryRows()).isEqualTo(0)
        runBlocking { brokenBackend.stop() }
        scope.cancel()

        // Пока бэкенда нет, устройство пишет дальше: брокер копит в его сессии
        agent.publishQos1(Topics.telemetry(id), """{"ts":${Samples.T0 + 4_000},"seq":4}""")

        KoinBackend(config).start().use { healthy ->
            assertThat(eventually { telemetryRows() == 4 }).isTrue()
            Thread.sleep(300) // ack идёт сразу после commit; даём ему уйти до остановки
            assertThat(healthy.get<IngestMetrics>().received.get()).isEqualTo(4)
        }

        // Всё подтверждено после commit: следующий запуск ничего не получает повторно
        agent.publishQos1(Topics.telemetry(id), """{"ts":${Samples.T0 + 5_000},"seq":5}""")
        KoinBackend(config).start().use { again ->
            assertThat(eventually { telemetryRows() == 5 }).isTrue()
            Thread.sleep(500)
            assertThat(again.get<IngestMetrics>().received.get()).isEqualTo(1)
        }
        agent.disconnect()
    }
}
