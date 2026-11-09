package ru.restofleet.agent.data.mqtt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.model.IncomingCommand
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.agent.domain.port.BrokerSession
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.agent.domain.port.CredentialStore
import ru.restofleet.agent.domain.port.MessagePublisher
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.IdGenerator
import ru.restofleet.shared.kernel.Outcome
import kotlin.coroutines.cancellation.CancellationException

/** Состояние сессии с причиной: [error] объясняет `DISCONNECTED`/`CONNECTING` (виден в Lab). */
data class SessionStatus(
    val state: SessionState,
    val error: MqttError? = null,
    val endpoint: String? = null,
    val connId: String? = null,
)

/**
 * MQTT-сессия агента: логика над тонкой границей [MqttLink] (HiveMQ), без сети в тестах.
 *
 * Жизненный цикл ([connect] запускает его один раз в [scope], результат виден в [status]):
 * учётка → CA (fail closed: нет учётки `not_configured`, нет CA `ca_missing`, битый `ca_invalid`; системное
 * хранилище не используется) → подключение → на каждое подключение `online` с `connId` его will (retained, QoS 1),
 * подписки QoS 1, и только потом `CONNECTED`. Первое подключение при ретрайных отказах повторяется с паузой 1..120 с;
 * отказ учётки ждёт смены учётки ([CredentialChanges]). Потерянное соединение клиент восстанавливает сам и сообщает
 * `Connected` с новым `connId`. Смена учётки во время работы: `offline`/`reconfigure`, закрытие, новое подключение.
 *
 * Входящие команды и `desired` отдаются холодными потоками [commands]/[desired] для одного потребителя: ack брокеру
 * уходит после возврата потребителя (`emit` не возвращается, пока блок `collect` не завершён). Если процесс умрёт до
 * ack, брокер передоставит сообщение, дубль команды отбросит `ProcessedCommands`.
 *
 * Остановка: [disconnect] публикует `offline` с `reason=stop` и тем же `connId`, затем штатный DISCONNECT (will не
 * срабатывает). Выбран явный `offline`, а не DISCONNECT 0x04: причина `stop` отличима от обрыва связи (`lwt`).
 */
internal class MqttBrokerSession(
    private val credentials: CredentialStore,
    private val caSource: CaSource,
    private val changes: CredentialChanges,
    private val linkFactory: MqttLinkFactory,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val statusPayloads: StatusPayloads,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) : BrokerSession, MessagePublisher {
    private val _status = MutableStateFlow(SessionStatus(SessionState.DISCONNECTED))
    private val _state = MutableStateFlow(SessionState.DISCONNECTED)
    private val _epoch = MutableStateFlow(0)
    private val incomingRouter = IncomingRouter(log)
    private val prerequisites = SessionPrerequisites(credentials, caSource, log)

    @Volatile private var active: Active? = null
    private var job: Job? = null

    val status: StateFlow<SessionStatus> = _status
    override val state: StateFlow<SessionState> = _state

    /** Растёт при каждом `online`+подписках; по нему запускается досылка (состояние может не успеть смениться). */
    val connectedEpoch: StateFlow<Int> = _epoch

    override val commands: Flow<IncomingCommand> = incomingRouter.commands
    override val desired: Flow<DesiredShadow> = incomingRouter.desired

    private class Active(val link: MqttLink, val router: TopicRouter)

    /** Запускает цикл сессии (повторный вызов ничего не делает); сбои подключения видны в [status], не в результате. */
    override suspend fun connect(): Outcome<Unit, DomainError> {
        if (job?.isActive != true) job = scope.launch { runLoop() }
        return Outcome.Success(Unit)
    }

    override suspend fun disconnect() {
        job?.cancelAndJoin()
        job = null
        publishStatus(SessionState.DISCONNECTED)
    }

    override suspend fun publish(message: OutgoingMessage): Outcome<Unit, DomainError> {
        val current = active
        if (current == null || _state.value != SessionState.CONNECTED) return Outcome.Failure(MqttError.NotConnected)
        return current.link.publish(current.router.outbound(message))
    }

    private suspend fun runLoop() {
        val backoff = ReconnectBackoff()
        while (currentCoroutineContext().isActive) {
            val seen = changes.current()
            val settings = prerequisites.settings()
            val trust = settings?.let { prerequisites.trust() }
            when {
                settings == null -> failAndWait(MqttError.NotConfigured, null, seen)
                trust is Outcome.Failure -> failAndWait(trust.error, settings, seen)
                trust is Outcome.Success -> attempt(settings, trust.value, seen, backoff)
            }
        }
    }

    private suspend fun attempt(
        settings: BrokerSettings,
        trust: javax.net.ssl.TrustManagerFactory,
        seen: Long,
        backoff: ReconnectBackoff,
    ) {
        publishStatus(SessionState.CONNECTING, endpoint = endpointOf(settings))
        val link = linkFactory.create(settings, trust, ConnectionConfigs(settings, ids, clock))
        when (val first = link.connect()) {
            is Outcome.Failure -> {
                link.closeQuietly(log)
                log("connect failed: ${first.error.code}")
                publishStatus(SessionState.DISCONNECTED, first.error, endpointOf(settings))
                changes.pauseAfter(first.error, seen, backoff)
            }
            is Outcome.Success -> {
                backoff.reset()
                val end = serve(link, settings, first.value, seen)
                if (end is Ended.WithError) {
                    publishStatus(SessionState.DISCONNECTED, end.error, endpointOf(settings))
                    changes.pauseAfter(end.error, seen, backoff)
                }
            }
        }
    }

    private sealed interface Ended {
        data object Reconfigure : Ended
        data class WithError(val error: MqttError) : Ended
    }

    /** Одно подключение: события клиента, входящие, наблюдение за сменой учётки. */
    private suspend fun serve(link: MqttLink, settings: BrokerSettings, firstConnId: String, seen: Long): Ended {
        val router = TopicRouter(settings.deviceId)
        val endpoint = endpointOf(settings)
        var connId = firstConnId
        var goodbyeReason: String? = STOP_REASON
        active = Active(link, router)
        return try {
            val ended = CompletableDeferred<Ended>()
            coroutineScope {
                val readers = launch { for (m in link.incoming) incomingRouter.route(m, router) }
                val watcher = launch {
                    changes.awaitChangeFrom(seen)
                    ended.complete(Ended.Reconfigure)
                }
                val events = launch {
                    connId = firstConnId
                    onConnected(link, router, connId, endpoint)
                    for (event in link.events) {
                        when (event) {
                            is LinkEvent.Connected -> {
                                connId = event.connId
                                onConnected(link, router, connId, endpoint)
                            }
                            is LinkEvent.Lost -> {
                                log("connection lost: ${event.error.code}")
                                publishStatus(SessionState.CONNECTING, event.error, endpoint)
                            }
                            is LinkEvent.Closed -> {
                                goodbyeReason = null
                                ended.complete(Ended.WithError(event.error))
                            }
                        }
                    }
                    ended.complete(Ended.WithError(MqttError.Failed("link_closed")))
                }
                val result = ended.await()
                if (result is Ended.Reconfigure) goodbyeReason = RECONFIGURE_REASON
                readers.cancel()
                watcher.cancel()
                events.cancel()
                result
            }
        } finally {
            active = null
            withContext(NonCancellable) { goodbye(link, router, connId, goodbyeReason) }
            publishStatus(SessionState.DISCONNECTED)
        }
    }

    private suspend fun onConnected(link: MqttLink, router: TopicRouter, connId: String, endpoint: String) {
        val online = OutboundMessage(
            topic = router.statusTopic,
            payload = statusPayloads.online(connId, clock.nowMillis()),
            qos = STATUS_QOS,
            retain = true,
            correlationData = null,
        )
        val published = link.publish(online)
        val subscribed = link.subscribe(listOf(router.desiredTopic, router.cmdReqTopic))
        val error = (published as? Outcome.Failure)?.error ?: (subscribed as? Outcome.Failure)?.error
        if (error != null) log("online/subscribe failed: ${error.code}")
        _status.value = SessionStatus(SessionState.CONNECTED, error, endpoint, connId)
        _state.value = SessionState.CONNECTED
        _epoch.value += 1
    }

    private suspend fun goodbye(link: MqttLink, router: TopicRouter, connId: String, reason: String?) {
        if (reason != null) {
            val offline = OutboundMessage(
                topic = router.statusTopic,
                payload = statusPayloads.offline(connId, clock.nowMillis(), reason),
                qos = STATUS_QOS,
                retain = true,
                correlationData = null,
            )
            withTimeoutOrNull(GOODBYE_TIMEOUT_MS) { link.publish(offline) }
        }
        link.closeQuietly(log)
    }

    private suspend fun failAndWait(error: MqttError, settings: BrokerSettings?, seen: Long) {
        publishStatus(SessionState.DISCONNECTED, error, settings?.let(::endpointOf))
        changes.awaitChangeFrom(seen)
    }

    private fun publishStatus(state: SessionState, error: MqttError? = null, endpoint: String? = null) {
        _status.value = SessionStatus(state, error, endpoint ?: _status.value.endpoint.takeIf { error != null }, null)
        _state.value = state
    }

    private companion object {
        const val STATUS_QOS = 1
        const val STOP_REASON = "stop"
        const val RECONFIGURE_REASON = "reconfigure"
        const val GOODBYE_TIMEOUT_MS = 3_000L
    }
}

private fun endpointOf(s: BrokerSettings) = "${s.host}:${s.port}"

/** Повтор только для ретрайных отказов, с нарастающей паузой; любую паузу прерывает смена учётки. */
private suspend fun CredentialChanges.pauseAfter(error: MqttError, seen: Long, backoff: ReconnectBackoff) {
    if (error.retryable) awaitChangeOrTimeout(seen, backoff.next()) else awaitChangeFrom(seen)
}

@Suppress("TooGenericExceptionCaught", "SwallowedException") // закрытие клиента не должно ронять цикл
private suspend fun MqttLink.closeQuietly(log: (String) -> Unit) {
    try {
        close()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
    } catch (e: Exception) {
        log("close failed: ${e::class.java.simpleName}")
    }
}
