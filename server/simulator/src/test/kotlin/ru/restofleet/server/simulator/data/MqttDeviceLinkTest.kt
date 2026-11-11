package ru.restofleet.server.simulator.data

import com.google.common.truth.Truth.assertThat
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import ru.restofleet.server.simulator.data.mqtt.MqttDeviceConnector
import ru.restofleet.server.simulator.data.mqtt.MqttSettings
import ru.restofleet.server.simulator.domain.model.CommandReply
import ru.restofleet.server.simulator.domain.model.DeviceConfig
import ru.restofleet.server.simulator.domain.model.DeviceCredentials
import ru.restofleet.server.simulator.domain.model.DeviceRole
import ru.restofleet.server.simulator.domain.model.LossMode
import ru.restofleet.server.simulator.domain.model.ProbeReport
import ru.restofleet.server.simulator.domain.model.ProbeResult
import ru.restofleet.server.simulator.domain.model.ReplyStatus
import ru.restofleet.server.simulator.domain.model.ReportedShadow
import ru.restofleet.server.simulator.domain.model.SimEvent
import ru.restofleet.server.simulator.domain.model.StatusInfo
import ru.restofleet.server.simulator.domain.model.TelemetrySample
import ru.restofleet.server.simulator.domain.port.DeviceLink
import ru.restofleet.server.simulator.domain.port.Incoming
import ru.restofleet.server.simulator.domain.runtime.RunMetrics
import ru.restofleet.shared.kernel.UuidIdGenerator
import ru.restofleet.shared.protocol.RfJson
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * MQTT-транспорт на настоящем Mosquitto 2.1.2 (образ стенда, Testcontainers): `status` + LWT с `connId`, QoS и
 * топики протокола, тень и команды в `incoming`, Correlation Data в `cmd/res`, штатная остановка и «пропажа».
 * Листенер без TLS и без DynSec (TLS и ACL проверяют тесты бэкенда и стенд); keep-alive 2 с — LWT через ≈ 3 с.
 */
class MqttDeviceLinkTest {
    private val metrics = RunMetrics()
    private val status = StatusInfo(DeviceRole.KITCHEN, "simulator-test", "boot-1")
    private val fixtures = File(System.getProperty("fixturesDir"))

    private fun connector(port: Int = broker.getMappedPort(PORT), keepAlive: Int = 2) = MqttDeviceConnector(
        MqttSettings(broker.host, port, keepAliveSec = keepAlive, nettyThreads = 2, connectTimeoutMs = 3_000),
        trust = null, metrics = metrics, clock = { System.currentTimeMillis() }, ticker = { System.nanoTime() },
        ids = UuidIdGenerator("k-"),
    )

    private fun device(): DeviceCredentials =
        DeviceCredentials("sim-${UUID.randomUUID().toString().take(8)}", "pw", "r-1", DeviceRole.KITCHEN)

    /** Наблюдатель на всё дерево устройства: (топик, полезная нагрузка, Correlation Data). */
    private class Observer(id: String) : AutoCloseable {
        val messages = LinkedBlockingQueue<Triple<String, String, String?>>()
        val client: Mqtt5BlockingClient = Mqtt5Client.builder().identifier("obs-${UUID.randomUUID()}")
            .serverHost(broker.host).serverPort(broker.getMappedPort(PORT)).buildBlocking()

        init {
            client.connect()
            client.toAsync().subscribeWith().topicFilter("rf/v1/dev/$id/#").qos(MqttQos.AT_LEAST_ONCE)
                .callback { p ->
                    val correlation = p.correlationData
                        .map { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) }.decodeToString() }
                        .orElse(null)
                    messages.put(Triple(p.topic.toString(), p.payloadAsBytes.decodeToString(), correlation))
                }
                .send().get(5, TimeUnit.SECONDS)
        }

        fun next(suffix: String, timeoutMs: Long = 5_000): Triple<String, String, String?> {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val left = deadline - System.currentTimeMillis()
                val message = messages.poll(left.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    ?: error("no message on $suffix in $timeoutMs ms")
                if (message.first.endsWith("/$suffix")) return message
            }
        }

        fun publish(topic: String, payload: String, retain: Boolean = false, correlation: String? = null) {
            client.publish(
                Mqtt5Publish.builder().topic(topic).payload(payload.toByteArray())
                    .qos(MqttQos.AT_LEAST_ONCE).retain(retain)
                    .apply { correlation?.let { correlationData(it.toByteArray()) } }.build(),
            )
        }

        override fun close() = client.disconnect()
    }

    private fun json(text: String) = RfJson.parseToJsonElement(text).jsonObject

    private fun sample() = TelemetrySample(
        ts = 1, seq = 1, batteryLevel = 90, charging = true, plugged = "ac", batteryTempC = 31.0,
        thermalStatus = "NONE", rssi = -50, freeMb = 1_000, availMb = 900, uptimeSec = 10,
        foregroundApp = "ru.restofleet.dummy.kitchen",
    )

    @Test
    fun deviceSessionSpeaksTheProtocol() = runBlocking<Unit> {
        val creds = device()
        Observer(creds.deviceId).use { observer ->
            val link: DeviceLink = connector().link(creds, victim = false)
            assertThat(link.open(status)).isTrue()
            val online = json(observer.next("status").second)
            assertThat(online["state"]!!.jsonPrimitive.content).isEqualTo("online")
            assertThat(online["role"]!!.jsonPrimitive.content).isEqualTo("kitchen")
            val connId = online["connId"]!!.jsonPrimitive.content
            assertThat(connId).startsWith("k-")

            assertThat(link.sendTelemetry(sample())).isTrue()
            assertThat(json(observer.next("telemetry").second)["seq"]!!.jsonPrimitive.content).isEqualTo("1")
            assertThat(link.sendEvents(listOf(SimEvent.Threshold(1, "temp_high", "enter", 46.0, 45.0)))).isTrue()
            assertThat(json(observer.next("events").second)["type"]!!.jsonPrimitive.content).isEqualTo("threshold")
            assertThat(link.sendProbe(ProbeReport(1, listOf(ProbeResult("api", true, 200, null, 5))))).isTrue()
            observer.next("probe")
            assertThat(link.sendReported(ReportedShadow(DeviceConfig(version = 1), 1, 1, emptyList()))).isTrue()
            val reported = json(observer.next("shadow/reported").second)
            assertThat(reported["appliedVersion"]!!.jsonPrimitive.content).isEqualTo("1")

            val base = "rf/v1/dev/${creds.deviceId}"
            observer.publish("$base/shadow/desired", File(fixtures, "shadow-desired.json").readText(), retain = true)
            val desired = withTimeout(5_000) { link.incoming.first() } as Incoming.Desired
            assertThat(desired.update.version).isEqualTo(12)
            observer.publish("$base/cmd/req", File(fixtures, "cmd-req-ping.json").readText(), correlation = "corr-1")
            val command = (withTimeout(5_000) { link.incoming.first() } as Incoming.Command).command
            assertThat(command.cmdId).isEqualTo("c-01J9Z5")
            assertThat(command.correlationData!!.decodeToString()).isEqualTo("corr-1")
            assertThat(command.receivedAtNanos).isGreaterThan(0L)

            val received = CommandReply(
                command.cmdId, ReplyStatus.RECEIVED, 2, correlationData = command.correlationData,
            )
            assertThat(link.reply(received)).isTrue()
            val reply = observer.next("cmd/res")
            assertThat(json(reply.second)["status"]!!.jsonPrimitive.content).isEqualTo("received")
            assertThat(reply.third).isEqualTo("corr-1")
            assertThat(link.poll()).isNull()

            link.close()
            val offline = json(observer.next("status").second)
            assertThat(offline["state"]!!.jsonPrimitive.content).isEqualTo("offline")
            assertThat(offline["reason"]!!.jsonPrimitive.content).isEqualTo("stop")
            assertThat(offline["connId"]!!.jsonPrimitive.content).isEqualTo(connId)
            assertThat(link.sendTelemetry(sample())).isFalse()
            observer.publish("$base/shadow/desired", "", retain = true)
        }
        assertThat(metrics.snapshot().messagesOut).isAtLeast(6)
        assertThat(metrics.snapshot().messagesIn).isAtLeast(2)
    }

    @Test
    fun silentLossIsDetectedByTheBrokerOnlyAfterKeepAlive() = runBlocking<Unit> {
        val creds = device()
        Observer(creds.deviceId).use { observer ->
            val link = connector().link(creds, victim = true)
            assertThat(link.open(status)).isTrue()
            observer.next("status")
            val lostAt = System.nanoTime()
            link.vanish(LossMode.SILENT)
            val lwt = json(observer.next("status", timeoutMs = 10_000).second)
            val afterMs = (System.nanoTime() - lostAt) / 1_000_000
            assertThat(lwt["reason"]!!.jsonPrimitive.content).isEqualTo("lwt")
            // keep-alive 2 с: брокер ждёт 1,5 × keep-alive, а не закрытия сокета
            assertThat(afterMs).isAtLeast(1_500L)
            link.release()
        }
    }

    @Test
    fun closedConnectionGivesTheWillImmediately() = runBlocking<Unit> {
        val creds = device()
        Observer(creds.deviceId).use { observer ->
            val link = connector(keepAlive = 30).link(creds, victim = true)
            assertThat(link.open(status)).isTrue()
            observer.next("status")
            val lostAt = System.nanoTime()
            link.vanish(LossMode.CLOSE)
            val lwt = json(observer.next("status", timeoutMs = 5_000).second)
            assertThat(lwt["reason"]!!.jsonPrimitive.content).isEqualTo("lwt")
            assertThat((System.nanoTime() - lostAt) / 1_000_000).isLessThan(5_000L)
            link.release()
        }
    }

    @Test
    fun takenOverSessionReconnectsWithANewConnId() = runBlocking<Unit> {
        val creds = device()
        Observer(creds.deviceId).use { observer ->
            val first = connector().link(creds, victim = false)
            assertThat(first.open(status)).isTrue()
            val firstConn = json(observer.next("status").second)["connId"]!!.jsonPrimitive.content
            val second = connector().link(creds, victim = false)
            assertThat(second.open(status)).isTrue()
            Thread.sleep(2_500)
            assertThat(metrics.snapshot().connectionLosses).isAtLeast(1)
            val connIds = generateSequence { observer.messages.poll() }.filter { it.first.endsWith("/status") }
                .map { json(it.second)["connId"]!!.jsonPrimitive.content }.toSet()
            assertThat(connIds - firstConn).isNotEmpty()
            first.close()
            second.close()
        }
    }

    @Test
    fun unreachableBrokerFailsToOpen() = runBlocking<Unit> {
        val closed = java.net.ServerSocket(0).also { it.close() }.localPort
        val link = connector(port = closed).link(device(), victim = false)
        assertThat(link.open(status)).isFalse()
        link.close()
        val notVictim = connector().link(device(), victim = false)
        assertThat(runCatching { notVictim.vanish(LossMode.SILENT) }.isFailure).isTrue()
    }

    companion object {
        private const val PORT = 1883
        private val CONF = """
            listener $PORT
            allow_anonymous true
            persistence false
            max_keepalive 120
        """.trimIndent()

        val broker: GenericContainer<*> by lazy {
            GenericContainer(DockerImageName.parse("eclipse-mosquitto:2.1.2-alpine"))
                .withExposedPorts(PORT)
                .withCopyToContainer(Transferable.of(CONF), "/mosquitto/config/mosquitto.conf")
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)))
                .also { it.start() }
        }

        @JvmStatic
        @AfterClass
        fun stop() {
            broker.stop()
        }
    }
}
