package ru.restofleet.server.simulator.data.mqtt

import com.hivemq.client.mqtt.MqttClientExecutorConfig
import com.hivemq.client.mqtt.MqttClientSslConfig
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.lifecycle.MqttClientConnectedContext
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import com.hivemq.client.mqtt.mqtt5.lifecycle.Mqtt5ClientConnectedContext
import com.hivemq.client.mqtt.mqtt5.lifecycle.Mqtt5ClientDisconnectedContext
import com.hivemq.client.mqtt.mqtt5.message.connect.Mqtt5Connect
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5Subscribe
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5Subscription
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import ru.restofleet.server.simulator.data.net.BlackholeRelay
import ru.restofleet.server.simulator.data.protocol.InboundMapper
import ru.restofleet.server.simulator.data.protocol.ProtocolMapper
import ru.restofleet.server.simulator.domain.model.CommandReply
import ru.restofleet.server.simulator.domain.model.DeviceCredentials
import ru.restofleet.server.simulator.domain.model.LossMode
import ru.restofleet.server.simulator.domain.model.ProbeReport
import ru.restofleet.server.simulator.domain.model.ReportedShadow
import ru.restofleet.server.simulator.domain.model.SimEvent
import ru.restofleet.server.simulator.domain.model.StatusInfo
import ru.restofleet.server.simulator.domain.model.TelemetrySample
import ru.restofleet.server.simulator.domain.port.DeviceConnector
import ru.restofleet.server.simulator.domain.port.DeviceLink
import ru.restofleet.server.simulator.domain.port.Incoming
import ru.restofleet.server.simulator.domain.port.Ticker
import ru.restofleet.server.simulator.domain.runtime.RunMetrics
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.IdGenerator
import ru.restofleet.shared.protocol.DeviceChannel
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.Topics
import ru.restofleet.shared.protocol.dto.CommandResultDto
import ru.restofleet.shared.protocol.dto.EventDto
import ru.restofleet.shared.protocol.dto.ProbeReportDto
import ru.restofleet.shared.protocol.dto.ShadowReportedDto
import ru.restofleet.shared.protocol.dto.StatusDto
import ru.restofleet.shared.protocol.dto.TelemetryDto
import java.util.concurrent.TimeUnit
import javax.net.ssl.TrustManagerFactory

/** Адрес брокера и параметры сессии устройства (как у агента: keep-alive 30 с, сессия 24 ч, `Clean Start = false`). */
data class MqttSettings(
    val host: String,
    val port: Int,
    val keepAliveSec: Int = KEEP_ALIVE_SEC,
    val sessionExpirySec: Long = SESSION_EXPIRY_SEC,
    val nettyThreads: Int = Runtime.getRuntime().availableProcessors(),
    val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    val publishTimeoutMs: Long = PUBLISH_TIMEOUT_MS,
) {
    companion object {
        const val KEEP_ALIVE_SEC = 30
        const val SESSION_EXPIRY_SEC = 86_400L
        const val CONNECT_TIMEOUT_MS = 60_000L
        const val PUBLISH_TIMEOUT_MS = 30_000L
    }
}

/**
 * MQTT-транспорт симулятора (арх. разд. 10): по клиенту HiveMQ на устройство, все клиенты — на одном Netty event loop
 * ([MqttSettings.nettyThreads] потоков). TLS — доверие только CA стенда ([trust]; `null` — без TLS, только тесты).
 */
class MqttDeviceConnector(
    private val settings: MqttSettings,
    private val trust: TrustManagerFactory?,
    private val metrics: RunMetrics,
    private val clock: Clock,
    private val ticker: Ticker,
    private val ids: IdGenerator,
) : DeviceConnector {
    private val executor = MqttClientExecutorConfig.builder().nettyThreads(settings.nettyThreads).build()

    override fun link(credentials: DeviceCredentials, victim: Boolean): DeviceLink =
        MqttDeviceLink(credentials, victim, settings, trust, executor, metrics, clock, ticker, ids)

    override suspend fun shutdown() = Unit
}

/**
 * Сессия одного устройства — как у агента (`HiveMqLink`, `MqttSessionConfigFactory`): LWT `status offline` с `connId`
 * подключения (retained, QoS 1), после CONNACK — `status online` (retained) и подписка на `shadow/desired` и
 * `cmd/req`; автопереподключение 1…120 с тем же CONNECT, но с новым `connId`. QoS: телеметрия и пробы 0, события,
 * `reported` и `cmd/res` — 1. Входящие ставятся в [incoming] сразу из колбэка HiveMQ; момент получения команды
 * берётся первым действием колбэка. Штатная остановка — `status offline` (`stop`) и DISCONNECT с Session Expiry 0
 * (брокер не копит сессии и команды между прогонами).
 */
@Suppress("TooManyFunctions", "LongParameterList")
class MqttDeviceLink(
    private val credentials: DeviceCredentials,
    private val victim: Boolean,
    private val settings: MqttSettings,
    private val trust: TrustManagerFactory?,
    private val executor: MqttClientExecutorConfig,
    private val metrics: RunMetrics,
    private val clock: Clock,
    private val ticker: Ticker,
    private val ids: IdGenerator,
) : DeviceLink {
    override val pushesCommands: Boolean = true

    private val channel = Channel<Incoming>(Channel.UNLIMITED)
    override val incoming: Flow<Incoming> = channel.receiveAsFlow()

    private val id = DeviceId.parse(credentials.deviceId) ?: error("invalid deviceId in credentials")
    private val desiredTopic = Topics.shadowDesired(id)
    private val commandTopic = Topics.cmdReq(id)

    @Volatile private var connId: String = ids.newId()

    @Volatile private var stopping = false

    @Volatile private var status: StatusInfo? = null

    private var relay: BlackholeRelay? = null
    private var client: Mqtt5AsyncClient? = null

    override suspend fun open(status: StatusInfo): Boolean {
        this.status = status
        val (host, port) = if (victim) {
            val created = BlackholeRelay(settings.host, settings.port).also { relay = it }
            LOOPBACK to created.port
        } else {
            settings.host to settings.port
        }
        val built = Mqtt5Client.builder()
            .identifier(credentials.deviceId)
            .serverHost(host)
            .serverPort(port)
            .executorConfig(executor)
            .apply { trust?.let { sslConfig(MqttClientSslConfig.builder().trustManagerFactory(it).build()) } }
            .automaticReconnect()
            .initialDelay(RECONNECT_INITIAL_S, TimeUnit.SECONDS)
            .maxDelay(RECONNECT_MAX_S, TimeUnit.SECONDS)
            .applyAutomaticReconnect()
            .addConnectedListener(::onConnected)
            .addDisconnectedListener(::onDisconnected)
            .buildAsync()
        client = built
        built.publishes(MqttGlobalPublishFilter.ALL, ::onPublish)
        val connected = try {
            withTimeoutOrNull(settings.connectTimeoutMs) { built.connect(connectMessage(connId)).await() } != null
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
        if (!connected) {
            stopping = true
            built.disconnect()
            relay?.close()
        }
        return connected
    }

    override suspend fun sendTelemetry(sample: TelemetrySample): Boolean =
        publish(
            DeviceChannel.TELEMETRY, TelemetryDto.serializer(), ProtocolMapper.telemetry(sample), MqttQos.AT_MOST_ONCE,
        )

    override suspend fun sendEvents(events: List<SimEvent>): Boolean = events.all { event ->
        publish(DeviceChannel.EVENTS, EventDto.serializer(), ProtocolMapper.event(event), MqttQos.AT_LEAST_ONCE)
    }

    override suspend fun sendProbe(report: ProbeReport): Boolean =
        publish(DeviceChannel.PROBE, ProbeReportDto.serializer(), ProtocolMapper.probe(report), MqttQos.AT_MOST_ONCE)

    override suspend fun sendReported(reported: ReportedShadow): Boolean = publish(
        DeviceChannel.SHADOW_REPORTED, ShadowReportedDto.serializer(), ProtocolMapper.reported(reported),
        MqttQos.AT_LEAST_ONCE,
    )

    override suspend fun reply(reply: CommandReply): Boolean = publish(
        DeviceChannel.CMD_RES, CommandResultDto.serializer(), ProtocolMapper.commandResult(reply),
        MqttQos.AT_LEAST_ONCE, correlationData = reply.correlationData,
    )

    override suspend fun poll() = null

    override suspend fun close() {
        stopping = true
        val current = client ?: return
        if (current.state.isConnected) {
            publish(
                DeviceChannel.STATUS, StatusDto.serializer(), ProtocolMapper.offline(connId, clock.nowMillis(), STOP),
                MqttQos.AT_LEAST_ONCE, retain = true,
            )
            ignoreFailure {
                withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
                    current.disconnectWith().sessionExpiryInterval(0).send().await()
                }
            }
        } else {
            ignoreFailure { current.disconnect() }
        }
        relay?.close()
        channel.close()
    }

    override suspend fun vanish(mode: LossMode) {
        stopping = true
        val currentRelay = relay ?: error("vanish() is only for victim links (created with a relay)")
        when (mode) {
            LossMode.SILENT -> currentRelay.freeze()
            LossMode.CLOSE -> currentRelay.cut()
        }
        // DISCONNECT уходит в замолкшее реле и до брокера не доходит; не ждём: реле не ответит
        client?.disconnect()
        channel.close()
    }

    override suspend fun release() {
        relay?.close()
    }

    private fun connectMessage(connId: String): Mqtt5Connect {
        val will = ProtocolMapper.offline(connId, clock.nowMillis(), LWT)
        return Mqtt5Connect.builder()
            .keepAlive(settings.keepAliveSec)
            .cleanStart(false)
            .sessionExpiryInterval(settings.sessionExpirySec)
            .simpleAuth()
            .username(credentials.deviceId)
            .password(credentials.password.toByteArray(Charsets.UTF_8))
            .applySimpleAuth()
            .willPublish()
            .topic(Topics.status(id))
            .payload(RfJson.encodeToString(StatusDto.serializer(), will).toByteArray())
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(true)
            .applyWillPublish()
            .build()
    }

    private fun onConnected(context: MqttClientConnectedContext) {
        val current = client ?: return
        val info = status ?: return
        val online = RfJson
            .encodeToString(StatusDto.serializer(), ProtocolMapper.online(info, connId, clock.nowMillis()))
            .toByteArray()
        current.publish(publishMessage(Topics.status(id), online, MqttQos.AT_LEAST_ONCE, retain = true, null))
            .whenComplete { _, error ->
                if (error == null) metrics.messageOut(online.size) else metrics.publishFailed()
            }
        val sessionPresent = (context as? Mqtt5ClientConnectedContext)?.connAck?.isSessionPresent == true
        if (!sessionPresent) {
            val subscribe = Mqtt5Subscribe.builder()
                .addSubscription(subscription(desiredTopic))
                .addSubscription(subscription(commandTopic))
                .build()
            current.subscribe(subscribe).whenComplete { _, error -> if (error != null) metrics.publishFailed() }
        }
    }

    private fun onDisconnected(context: MqttClientDisconnectedContext) {
        val reconnector = (context as? Mqtt5ClientDisconnectedContext)?.reconnector ?: return
        if (stopping) {
            reconnector.reconnect(false)
            return
        }
        if (context.source != MqttDisconnectSource.USER) metrics.connectionLost()
        // Автопереподключение HiveMQ по умолчанию шлёт CONNECT без учётки (реш. 259): повторяем свой CONNECT
        connId = ids.newId()
        reconnector.connect(connectMessage(connId))
    }

    private fun onPublish(publish: Mqtt5Publish) {
        val receivedAt = ticker.nanos()
        val payload = publish.payloadAsBytes
        metrics.messageIn(payload.size)
        val text = payload.decodeToString()
        val incoming = when (publish.topic.toString()) {
            desiredTopic -> InboundMapper.desired(text)?.let { Incoming.Desired(it) }
            commandTopic -> {
                val correlation = publish.correlationData.map { buffer ->
                    ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
                }.orElse(null)
                InboundMapper.command(text, receivedAt, correlation)?.let { Incoming.Command(it) }
            }
            else -> null
        }
        incoming?.let { channel.trySend(it) }
    }

    private suspend fun <T> publish(
        channel: DeviceChannel,
        serializer: KSerializer<T>,
        value: T,
        qos: MqttQos,
        retain: Boolean = false,
        correlationData: ByteArray? = null,
    ): Boolean {
        val current = client
        if (current == null || !current.state.isConnected) {
            metrics.publishFailed()
            return false
        }
        val payload = RfJson.encodeToString(serializer, value).toByteArray()
        val ok = try {
            withTimeoutOrNull(settings.publishTimeoutMs) {
                current.publish(publishMessage(Topics.of(id, channel), payload, qos, retain, correlationData)).await()
            } != null
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
        if (ok) metrics.messageOut(payload.size) else metrics.publishFailed()
        return ok
    }

    private fun subscription(topic: String) =
        Mqtt5Subscription.builder().topicFilter(topic).qos(MqttQos.AT_LEAST_ONCE).build()

    private fun publishMessage(
        topic: String,
        payload: ByteArray,
        qos: MqttQos,
        retain: Boolean,
        correlation: ByteArray?,
    ) =
        Mqtt5Publish.builder()
            .topic(topic)
            .payload(payload)
            .qos(qos)
            .retain(retain)
            .apply { correlation?.let { correlationData(it) } }
            .build()

    private suspend fun ignoreFailure(block: suspend () -> Unit) {
        try {
            block()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val RECONNECT_INITIAL_S = 1L
        const val RECONNECT_MAX_S = 120L
        const val DISCONNECT_TIMEOUT_MS = 5_000L
        const val STOP = "stop"
        const val LWT = "lwt"
    }
}
