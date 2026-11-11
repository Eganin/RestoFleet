package ru.restofleet.server.data.scenario

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client
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
import ru.restofleet.server.data.config.MqttConfig
import ru.restofleet.server.data.ingest.InboundProcessor
import ru.restofleet.server.data.ingest.IngestMetrics
import ru.restofleet.server.data.ingest.IngestPipeline
import ru.restofleet.server.data.mqtt.HiveMqClient
import ru.restofleet.server.data.mqtt.MqttBackend
import ru.restofleet.server.data.support.KoinBackend
import ru.restofleet.server.data.support.Samples
import ru.restofleet.server.data.support.TestBroker
import ru.restofleet.server.data.support.TestBroker.connectAs
import ru.restofleet.server.data.support.TestBroker.publishQos1
import ru.restofleet.server.data.support.TestDatabase
import ru.restofleet.server.data.support.registeredDevice
import ru.restofleet.shared.protocol.Topics
import java.io.File
import java.sql.SQLTransientConnectionException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Пределы брокера стенда (`deploy/mosquitto/mosquitto.conf`; [TestBroker] берёт тот же файл):
 * - R-1: пакет больше `max_packet_size` брокер не принимает — до бэкенда он не доходит, отправитель отключается;
 * - R-2: очередь постоянной сессии бэкенда вмещает больше 1 000 QoS 1 сообщений (умолчание Mosquitto), пока
 *   бэкенда нет или пока недоступна БД (в полёте ещё Receive Maximum).
 */
class BrokerLimitsTest {
    private val clientId = "backend-limits-${UUID.randomUUID()}"
    private val config = TestBroker.backendConfig(clientId)

    @Before
    fun clean() = TestDatabase.reset()

    @After
    fun purge() = TestBroker.purgeSession(clientId)

    @Test
    fun `broker packet limit matches the backend client and leaves room for the decoder limit`() {
        val conf = File(System.getProperty("rf.deployDir"), "mosquitto/mosquitto.conf").readLines()
        val brokerLimit = conf.single { it.startsWith("max_packet_size ") }.substringAfter(' ').trim().toInt()

        assertThat(brokerLimit).isEqualTo(MqttConfig.DEFAULT_MAX_PACKET_SIZE)
        assertThat(brokerLimit).isGreaterThan(IngestConfig.DEFAULT_MAX_PAYLOAD_BYTES)
    }

    @Test
    fun `an oversized packet never reaches the backend and its sender is disconnected`() {
        val (id, password) = registeredDevice()
        KoinBackend(config).start().use { backend ->
            // MQTT 3.1.1: клиент не знает Maximum Packet Size брокера и отправляет пакет как есть
            val legacy = Mqtt3Client.builder().identifier(id.value)
                .serverHost(TestBroker.host).serverPort(TestBroker.port).buildBlocking()
            legacy.connectWith().simpleAuth().username(id.value).password(password.toByteArray()).applySimpleAuth()
                .send()
            val oversized = ByteArray(MqttConfig.DEFAULT_MAX_PACKET_SIZE + 1) { 'x'.code.toByte() }
            runCatching {
                legacy.publishWith().topic(Topics.telemetry(id)).qos(MqttQos.AT_LEAST_ONCE).payload(oversized).send()
            }
            assertWithMessage("sender disconnected").that(eventually { !legacy.state.isConnected }).isTrue()

            val agent = TestBroker.deviceClient(id.value)
            assertThat(agent.connectAs(id.value, password)).isNull()
            agent.publishQos1(Topics.telemetry(id), telemetry(1))
            assertThat(eventually { telemetryRows() == 1 }).isTrue()
            Thread.sleep(300)
            val metrics = backend.get<IngestMetrics>()
            assertThat(metrics.received.get()).isEqualTo(1)
            assertThat(metrics.undecodable.get()).isEqualTo(0)
            agent.disconnect()
        }
    }

    @Test
    fun `backend session keeps more than the default 1000 queued messages while the backend is away`() {
        val (id, password) = registeredDevice()
        // Сессия бэкенда с подписками есть, сам бэкенд остановлен (рестарт, сбой)
        KoinBackend(config).start().close()

        val agent = TestBroker.deviceClient(id.value)
        assertThat(agent.connectAs(id.value, password)).isNull()
        (1..QUEUED).forEach { agent.publishQos1(Topics.telemetry(id), telemetry(it)) }
        agent.disconnect()

        KoinBackend(config).start().use {
            eventually(60_000) { telemetryRows() == QUEUED }
            assertWithMessage("queued QoS 1 messages written").that(telemetryRows()).isEqualTo(QUEUED)
        }
    }

    @Test
    fun `while the database is down more than 2000 QoS 1 messages wait and all arrive after recovery`() {
        val (id, password) = registeredDevice()
        val down = AtomicBoolean(true)
        val written = ConcurrentHashMap.newKeySet<String>()
        // «БД недоступна»: пачка не записывается и не подтверждается; в полёте — Receive Maximum (1 000), остальное
        // ждёт в очереди сессии бэкенда у брокера (по умолчанию ещё 1 000, сверх — выброс)
        val db = InboundProcessor { batch ->
            if (down.get()) throw SQLTransientConnectionException("db down")
            batch.forEach { written += it.payload.decodeToString() }
        }
        val retryFast = IngestConfig(batchWindowMs = 50, retryInitialMs = 50, retryMaxMs = 200)
        val backend = MqttBackend(HiveMqClient(config, trust = null), IngestPipeline(db, retryFast, IngestMetrics()))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        backend.start(scope)
        runBlocking { withTimeout(15_000) { backend.awaitReady() } }

        val agent = TestBroker.deviceClient(id.value)
        assertThat(agent.connectAs(id.value, password)).isNull()
        (1..QUEUED).forEach { agent.publishQos1(Topics.telemetry(id), telemetry(it)) }
        down.set(false)

        eventually(60_000) { written.size == QUEUED }
        assertWithMessage("QoS 1 messages written after the database is back").that(written.size).isEqualTo(QUEUED)
        runBlocking { backend.stop() }
        scope.cancel()
        agent.disconnect()
    }

    private fun telemetry(seq: Int) = """{"ts":${Samples.T0 + seq * 1_000L},"seq":$seq}"""

    private fun telemetryRows(): Int = TestDatabase.scalar("SELECT count(*) FROM telemetry")!!.toInt()

    private companion object {
        /** Больше, чем Receive Maximum бэкенда (1 000 в полёте) + очередь Mosquitto по умолчанию (1 000). */
        const val QUEUED = 2_500
    }
}
