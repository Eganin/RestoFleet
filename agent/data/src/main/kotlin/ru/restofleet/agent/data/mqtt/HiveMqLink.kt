package ru.restofleet.agent.data.mqtt

import com.hivemq.client.mqtt.MqttClientSslConfig
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import com.hivemq.client.mqtt.mqtt5.lifecycle.Mqtt5ClientDisconnectedContext
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5PublishResult
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5Subscribe
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5Subscription
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.shared.kernel.Outcome
import java.util.concurrent.TimeUnit
import javax.net.ssl.TrustManagerFactory
import kotlin.coroutines.cancellation.CancellationException

/**
 * Тонкая обёртка над `Mqtt5AsyncClient` HiveMQ: вся логика сессии выше ([MqttBrokerSession]); здесь только перевод
 * вызовов и событий. Не покрыта юнит-тестами (нужен брокер): проверяется ручным M1 на эмуляторе и 3.7.
 *
 * - TLS: доверие только к переданному [TrustManagerFactory] (CA стенда), имя хоста проверяет [SanHostnameVerifier];
 * - `simpleAuth`: username = deviceId, пароль байтами; нигде не логируется;
 * - автопереподключение HiveMQ 1..120 с; на каждую попытку `connId` и will новые (`disconnectedListener`);
 * - первое подключение, упавшее при `connect()`, клиент сам не повторяет: решает [MqttBrokerSession];
 * - входящие с ручным подтверждением.
 */
internal class HiveMqLink(
    private val settings: BrokerSettings,
    trust: TrustManagerFactory,
    private val configs: ConnectionConfigs,
    private val log: (String) -> Unit,
) : MqttLink {
    private val _events = Channel<LinkEvent>(Channel.UNLIMITED)
    private val _incoming = Channel<IncomingMessage>(Channel.UNLIMITED)

    @Volatile private var current: MqttSessionConfig = configs.next()

    @Volatile private var connectedOnce = false

    @Volatile private var closing = false

    override val events: ReceiveChannel<LinkEvent> = _events
    override val incoming: ReceiveChannel<IncomingMessage> = _incoming

    private val client: Mqtt5AsyncClient = Mqtt5Client.builder()
        .identifier(current.clientId)
        .serverHost(current.host)
        .serverPort(current.port)
        .sslConfig(
            MqttClientSslConfig.builder()
                .trustManagerFactory(trust)
                .hostnameVerifier(SanHostnameVerifier)
                .build(),
        )
        .automaticReconnect()
        .initialDelay(RECONNECT_INITIAL_S, TimeUnit.SECONDS)
        .maxDelay(RECONNECT_MAX_S, TimeUnit.SECONDS)
        .applyAutomaticReconnect()
        .addConnectedListener { if (connectedOnce) _events.trySend(LinkEvent.Connected(current.connId)) }
        .addDisconnectedListener(::onDisconnected)
        .buildAsync()

    init {
        client.publishes(MqttGlobalPublishFilter.ALL, ::onPublish, true)
    }

    override suspend fun connect(): Outcome<String, MqttError> =
        try {
            val config = current
            client.connectWith()
                .keepAlive(config.keepAliveSec)
                .cleanStart(config.cleanStart)
                .sessionExpiryInterval(config.sessionExpirySec)
                .simpleAuth()
                .username(settings.deviceId.value)
                .password(settings.password.toByteArray(Charsets.UTF_8))
                .applySimpleAuth()
                .willPublish()
                .topic(config.willTopic)
                .payload(config.willPayload)
                .qos(MqttQos.fromCode(config.willQos) ?: MqttQos.AT_LEAST_ONCE)
                .retain(config.willRetain)
                .applyWillPublish()
                .send()
                .await()
            connectedOnce = true
            Outcome.Success(config.connId)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            Outcome.Failure(MqttErrorMapper.fromConnect(e))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Outcome.Failure(MqttErrorMapper.fromConnect(e))
        }

    override suspend fun subscribe(topics: List<String>): Outcome<Unit, MqttError> =
        try {
            val subscriptions = topics.map { topic ->
                Mqtt5Subscription.builder().topicFilter(topic).qos(MqttQos.AT_LEAST_ONCE).build()
            }
            val ack = client.subscribe(Mqtt5Subscribe.builder().addSubscriptions(subscriptions).build()).await()
            val failed = ack.reasonCodes.firstOrNull { it.isError }
            if (failed == null) {
                Outcome.Success(Unit)
            } else {
                Outcome.Failure(
                    if (failed.code == NOT_AUTHORIZED_CODE) {
                        MqttError.NotAuthorized
                    } else {
                        MqttError.Failed("suback_${failed.name.lowercase()}")
                    },
                )
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            Outcome.Failure(MqttErrorMapper.fromConnect(e))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Outcome.Failure(MqttErrorMapper.fromConnect(e))
        }

    override suspend fun publish(message: OutboundMessage): Outcome<Unit, MqttError> {
        if (!client.state.isConnected) return Outcome.Failure(MqttError.NotConnected)
        return try {
            val publish = Mqtt5Publish.builder()
                .topic(message.topic)
                .payload(message.payload)
                .qos(MqttQos.fromCode(message.qos) ?: MqttQos.AT_LEAST_ONCE)
                .retain(message.retain)
                .apply { message.correlationData?.let { correlationData(it) } }
                .build()
            val result = withTimeoutOrNull(PUBLISH_TIMEOUT_MS) { client.publish(publish).await() }
            if (result == null) Outcome.Failure(MqttError.Failed("publish_timeout")) else resultOf(result)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            Outcome.Failure(MqttErrorMapper.fromConnect(e))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Outcome.Failure(MqttErrorMapper.fromConnect(e))
        }
    }

    override suspend fun close() {
        closing = true
        try {
            // Отказ первого подключения: отключать нечего, disconnect() дал бы MqttClientStateException
            if (client.state.isConnectedOrReconnect) {
                withTimeoutOrNull(CLOSE_TIMEOUT_MS) { client.disconnect().await() }
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            log("disconnect failed: ${e::class.java.simpleName}")
        }
        _events.close()
        _incoming.close()
    }

    private fun resultOf(result: Mqtt5PublishResult): Outcome<Unit, MqttError> {
        val ack = (result as? Mqtt5PublishResult.Mqtt5Qos1Result)?.pubAck
        val mapped = ack?.reasonCode?.let { MqttErrorMapper.fromPublishAck(it.name, it.code, it.isError) }
        val thrown = result.error.orElse(null)
        return when {
            mapped != null -> Outcome.Failure(mapped)
            thrown != null -> Outcome.Failure(MqttErrorMapper.fromConnect(thrown))
            else -> Outcome.Success(Unit)
        }
    }

    private fun onPublish(p: Mqtt5Publish) {
        val delivered = _incoming.trySend(
            IncomingMessage(
                topic = p.topic.toString(),
                payload = p.payloadAsBytes,
                responseTopic = p.responseTopic.map { it.toString() }.orElse(null),
                correlationData = p.correlationData.map { buffer ->
                    ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
                }.orElse(null),
                ack = { acknowledge(p) },
            ),
        )
        if (delivered.isFailure) log("incoming dropped: link closed")
    }

    private fun acknowledge(p: Mqtt5Publish) {
        try {
            p.acknowledge()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Соединение, получившее сообщение, уже закрыто: брокер передоставит его (сессия постоянная)
            log("ack skipped: ${e::class.java.simpleName}")
        }
    }

    /** Каждая попытка переподключения получает свежий `connId` и will с ним; отказ учётки её прекращает. */
    private fun onDisconnected(context: MqttClientDisconnectedContext) {
        val reconnector = (context as Mqtt5ClientDisconnectedContext).reconnector
        val error = MqttErrorMapper.fromConnect(context.cause)
        when {
            closing || !connectedOnce -> reconnector.reconnect(false)
            !error.retryable -> {
                reconnector.reconnect(false)
                _events.trySend(LinkEvent.Closed(error))
            }
            else -> {
                if (reconnector.attempts == 0) _events.trySend(LinkEvent.Lost(error))
                val next = configs.next().also { current = it }
                reconnector.connectWith()
                    .keepAlive(next.keepAliveSec)
                    .cleanStart(next.cleanStart)
                    .sessionExpiryInterval(next.sessionExpirySec)
                    .simpleAuth()
                    .username(settings.deviceId.value)
                    .password(settings.password.toByteArray(Charsets.UTF_8))
                    .applySimpleAuth()
                    .willPublish()
                    .topic(next.willTopic)
                    .payload(next.willPayload)
                    .qos(MqttQos.fromCode(next.willQos) ?: MqttQos.AT_LEAST_ONCE)
                    .retain(next.willRetain)
                    .applyWillPublish()
                    .applyConnect()
            }
        }
    }

    private companion object {
        const val RECONNECT_INITIAL_S = 1L
        const val RECONNECT_MAX_S = 120L
        const val PUBLISH_TIMEOUT_MS = 10_000L
        const val CLOSE_TIMEOUT_MS = 3_000L
        const val NOT_AUTHORIZED_CODE = 0x87
    }
}
