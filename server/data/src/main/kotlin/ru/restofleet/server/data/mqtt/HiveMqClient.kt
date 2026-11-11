package ru.restofleet.server.data.mqtt

import com.hivemq.client.mqtt.MqttClientSslConfig
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import com.hivemq.client.mqtt.mqtt5.lifecycle.Mqtt5ClientConnectedContext
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5Subscribe
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5Subscription
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import ru.restofleet.server.data.config.MqttConfig
import ru.restofleet.server.data.ingest.InboundMessage
import java.util.concurrent.TimeUnit
import javax.net.ssl.TrustManagerFactory

/**
 * Одно MQTT 5-соединение бэкенда (HiveMQ 1.4.2) для ingest, публикаций и `$CONTROL` DynSec (реш. 230, 234):
 * - стабильный `clientId`, `Clean Start = false`, Session Expiry 24 ч — брокер копит сообщения, пока бэкенд недоступен;
 * - все входящие — с ручным подтверждением; ответы `$CONTROL` подтверждаются сразу, сообщения устройств — вызывающим;
 * - автопереподключение 1…30 с; если сессии на брокере нет (истекла, брокер без persistence) — подписки заново;
 * - TLS с доверием только к CA стенда ([trust]); `null` — без TLS, только тесты (реш. 232).
 *
 * Обработчики ставятся **до** [connect]: после CONNACK брокер сразу шлёт накопленное.
 */
class HiveMqClient(private val config: MqttConfig, trust: TrustManagerFactory?) {
    private val log = KotlinLogging.logger {}

    @Volatile private var deviceHandler: ((InboundMessage) -> Unit)? = null

    @Volatile private var controlHandler: ((String, ByteArray) -> Unit)? = null

    @Volatile private var subscriptions: List<String> = emptyList()

    private val client: Mqtt5AsyncClient = Mqtt5Client.builder()
        .identifier(config.clientId)
        .serverHost(config.host)
        .serverPort(config.port)
        .apply { if (trust != null) sslConfig(MqttClientSslConfig.builder().trustManagerFactory(trust).build()) }
        .automaticReconnect()
        .initialDelay(RECONNECT_INITIAL_S, TimeUnit.SECONDS)
        .maxDelay(RECONNECT_MAX_S, TimeUnit.SECONDS)
        .applyAutomaticReconnect()
        .addConnectedListener(::onConnected)
        .addDisconnectedListener { ctx ->
            log.warn { "MQTT disconnected (${ctx.source}): ${ctx.cause.javaClass.simpleName}" }
        }
        .buildAsync()

    init {
        client.publishes(MqttGlobalPublishFilter.ALL, ::route, true)
    }

    val isConnected: Boolean get() = client.state.isConnected

    fun onDeviceMessage(handler: (InboundMessage) -> Unit) {
        deviceHandler = handler
    }

    fun onControlMessage(handler: (topic: String, payload: ByteArray) -> Unit) {
        controlHandler = handler
    }

    /** Первое подключение; при ошибке бросает (повтор решает вызывающий), дальше переподключается сам. */
    suspend fun connect() {
        client.connectWith()
            .cleanStart(false)
            .sessionExpiryInterval(config.sessionExpirySec)
            .keepAlive(config.keepAliveSec)
            .simpleAuth()
            .username(config.username)
            .password(config.password.toByteArray(Charsets.UTF_8))
            .applySimpleAuth()
            .restrictions()
            .receiveMaximum(config.receiveMaximum)
            .applyRestrictions()
            .send()
            .await()
    }

    /** QoS 1; отказ брокера в SUBACK (например, нет прав) — [IllegalStateException]. Подписки запоминаются. */
    suspend fun subscribe(filters: List<String>) {
        subscriptions = (subscriptions + filters).distinct()
        val ack = client.subscribe(subscribeRequest(filters)).await()
        val rejected = filters.zip(ack.reasonCodes).filter { (_, code) -> code.isError }
        check(rejected.isEmpty()) { "SUBACK rejected: ${rejected.joinToString { "${it.first}=${it.second}" }}" }
    }

    /** Публикация с ожиданием PUBACK; `null` — успех, иначе причина отказа (не подключён, таймаут, код PUBACK). */
    suspend fun publish(publish: Mqtt5Publish): String? {
        if (!isConnected) return "not_connected"
        return try {
            // Успех и таймаут нельзя сливать в один `null`: у успешной публикации `error` пуст
            val result = withTimeoutOrNull(config.publishTimeoutMs) { client.publish(publish).await() }
            if (result == null) "timeout" else result.error.map { it.javaClass.simpleName }.orElse(null)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            e.javaClass.simpleName
        }
    }

    suspend fun disconnect() {
        if (client.state.isConnectedOrReconnect) {
            try {
                withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) { client.disconnect().await() }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                log.warn { "MQTT disconnect failed: ${e.javaClass.simpleName}" }
            }
        }
    }

    private fun onConnected(context: com.hivemq.client.mqtt.lifecycle.MqttClientConnectedContext) {
        val sessionPresent = (context as? Mqtt5ClientConnectedContext)?.connAck?.isSessionPresent == true
        log.info { "MQTT connected, session present: $sessionPresent" }
        val filters = subscriptions
        if (!sessionPresent && filters.isNotEmpty()) {
            // Сессии на брокере нет: подписки потеряны — оформляем заново (без ожидания: колбэк потока HiveMQ)
            client.subscribe(subscribeRequest(filters)).whenComplete { _, error ->
                if (error != null) log.error { "resubscribe failed: ${error.javaClass.simpleName}" }
            }
        }
    }

    private fun subscribeRequest(filters: List<String>): Mqtt5Subscribe = Mqtt5Subscribe.builder()
        .addSubscriptions(
            filters.map { Mqtt5Subscription.builder().topicFilter(it).qos(MqttQos.AT_LEAST_ONCE).build() },
        )
        .build()

    private fun route(publish: Mqtt5Publish) {
        val topic = publish.topic.toString()
        if (topic.startsWith(CONTROL_PREFIX)) {
            acknowledge(publish)
            controlHandler?.invoke(topic, publish.payloadAsBytes)
            return
        }
        val handler = deviceHandler
        if (handler == null) {
            // Без подтверждения брокер передоставит сообщение при следующем подключении
            log.warn { "no ingest handler: message on $topic left unacknowledged" }
        } else {
            handler(InboundMessage(topic, publish.payloadAsBytes) { acknowledge(publish) })
        }
    }

    private fun acknowledge(publish: Mqtt5Publish) {
        try {
            publish.acknowledge()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Соединение, принявшее сообщение, закрыто: брокер передоставит его в постоянной сессии
            log.debug { "ack skipped: ${e.javaClass.simpleName}" }
        }
    }

    private companion object {
        const val CONTROL_PREFIX = "\$CONTROL/"
        const val RECONNECT_INITIAL_S = 1L
        const val RECONNECT_MAX_S = 30L
        const val DISCONNECT_TIMEOUT_MS = 3_000L
    }
}
