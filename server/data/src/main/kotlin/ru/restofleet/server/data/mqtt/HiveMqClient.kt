package ru.restofleet.server.data.mqtt

import com.hivemq.client.mqtt.MqttClientSslConfig
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import com.hivemq.client.mqtt.mqtt5.lifecycle.Mqtt5ClientConnectedContext
import com.hivemq.client.mqtt.mqtt5.lifecycle.Mqtt5ClientDisconnectedContext
import com.hivemq.client.mqtt.mqtt5.message.connect.Mqtt5Connect
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
 * Одно MQTT 5-соединение бэкенда (HiveMQ 1.4.2) для ingest, публикаций и `$CONTROL` DynSec (реш. 230, 234, 242):
 * - стабильный `clientId`, `Clean Start = false`, Session Expiry 24 ч — брокер копит сообщения, пока бэкенд недоступен;
 * - все входящие — с ручным подтверждением; ответы `$CONTROL` подтверждаются сразу, сообщения устройств — вызывающим;
 * - автопереподключение 1…30 с тем же CONNECT (учётка, `Clean Start = false`); подписки оформляются заново на
 *   **каждом** переподключении, даже при `session present: true` (V-1, реш. 301): Mosquitto 2.1.2 при восстановлении
 *   сессии из persistence не восстанавливает shared-подписки `$share/…`, и без этого приём молча стоял. Повторный
 *   SUBSCRIBE того же фильтра в той же сессии по MQTT 5 (3.8.4) заменяет подписку, а не добавляет вторую — дублей нет;
 * - [isSubscribed] — подписки подтверждены SUBACK на текущем соединении (для `/health`);
 * - TLS с доверием только к CA стенда ([trust]); `null` — без TLS, только тесты (реш. 232).
 *
 * Обработчики ставятся **до** [connect]: после CONNACK брокер сразу шлёт накопленное.
 */
class HiveMqClient(private val config: MqttConfig, trust: TrustManagerFactory?) {
    private val log = KotlinLogging.logger {}

    @Volatile private var deviceHandler: ((InboundMessage) -> Unit)? = null

    @Volatile private var controlHandler: ((String, ByteArray) -> Unit)? = null

    @Volatile private var subscriptions: List<String> = emptyList()

    /** SUBACK на все [subscriptions] получен на текущем соединении; сбрасывается при разрыве. */
    @Volatile private var subscriptionsActive = false

    private val connectMessage: Mqtt5Connect = connectMessage(config)

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
            subscriptionsActive = false
            log.warn { "MQTT disconnected (${ctx.source}): ${ctx.cause.javaClass.simpleName}" }
            // Автопереподключение HiveMQ по умолчанию шлёт CONNECT без учётки и с Clean Start = true: брокер отвечал
            // «not authorised» бесконечно (найдено на стенде, КМ2b). Повтор — тем же CONNECT, что и первое подключение
            (ctx as? Mqtt5ClientDisconnectedContext)?.reconnector?.connect(connectMessage)
        }
        .buildAsync()

    init {
        client.publishes(MqttGlobalPublishFilter.ALL, ::route, true)
    }

    val isConnected: Boolean get() = client.state.isConnected

    /** Подключён, и все подписки подтверждены брокером на этом соединении: сообщения устройств доходят. */
    val isSubscribed: Boolean get() = isConnected && subscriptionsActive

    fun onDeviceMessage(handler: (InboundMessage) -> Unit) {
        deviceHandler = handler
    }

    fun onControlMessage(handler: (topic: String, payload: ByteArray) -> Unit) {
        controlHandler = handler
    }

    /** Первое подключение; при ошибке бросает (повтор решает вызывающий), дальше переподключается сам. */
    suspend fun connect() {
        client.connect(connectMessage).await()
    }

    /** QoS 1; отказ брокера в SUBACK (например, нет прав) — [IllegalStateException]. Подписки запоминаются. */
    suspend fun subscribe(filters: List<String>) {
        subscriptions = (subscriptions + filters).distinct()
        val ack = client.subscribe(subscribeRequest(filters)).await()
        val rejected = filters.zip(ack.reasonCodes).filter { (_, code) -> code.isError }
        check(rejected.isEmpty()) { "SUBACK rejected: ${rejected.joinToString { "${it.first}=${it.second}" }}" }
        subscriptionsActive = true
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
        if (filters.isEmpty()) return
        // Независимо от session present (V-1): брокер мог восстановить сессию без shared-подписок. Без ожидания —
        // это колбэк потока HiveMQ; до SUBACK [isSubscribed] = false, и `/health` показывает брокер неготовым
        client.subscribe(subscribeRequest(filters)).whenComplete { ack, error ->
            val rejected = ack?.reasonCodes?.count { it.isError } ?: 0
            when {
                error != null -> log.error { "resubscribe failed: ${error.javaClass.simpleName}" }
                rejected > 0 -> log.error { "resubscribe: SUBACK rejected $rejected of ${filters.size} filters" }
                else -> {
                    subscriptionsActive = true
                    log.info { "MQTT resubscribed ${filters.size} filters" }
                }
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

    companion object {
        private const val CONTROL_PREFIX = "\$CONTROL/"
        private const val RECONNECT_INITIAL_S = 1L
        private const val RECONNECT_MAX_S = 30L
        private const val DISCONNECT_TIMEOUT_MS = 3_000L

        /**
         * Один CONNECT на первое подключение и все переподключения: учётка, постоянная сессия, Receive Maximum (P10),
         * Maximum Packet Size (R-1: пакет больше брокер бэкенду не перешлёт).
         */
        internal fun connectMessage(config: MqttConfig): Mqtt5Connect = Mqtt5Connect.builder()
            .cleanStart(false)
            .sessionExpiryInterval(config.sessionExpirySec)
            .keepAlive(config.keepAliveSec)
            .simpleAuth()
            .username(config.username)
            .password(config.password.toByteArray(Charsets.UTF_8))
            .applySimpleAuth()
            .restrictions()
            .receiveMaximum(config.receiveMaximum)
            .maximumPacketSize(config.maxPacketSize)
            .applyRestrictions()
            .build()
    }
}
