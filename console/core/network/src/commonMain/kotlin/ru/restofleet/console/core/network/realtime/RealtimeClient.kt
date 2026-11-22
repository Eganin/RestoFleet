package ru.restofleet.console.core.network.realtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import ru.restofleet.console.core.network.AccessTokenSource
import ru.restofleet.console.core.network.RefreshOutcome
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.rest.console.ws.WsClientMessage
import ru.restofleet.shared.protocol.rest.console.ws.WsCloseCodes
import ru.restofleet.shared.protocol.rest.console.ws.WsDeviceRefDto
import ru.restofleet.shared.protocol.rest.console.ws.WsServerMessage

/** События для потребителей. После каждого [Connected] нужно перечитать состояние через REST. */
sealed interface RealtimeEvent {
    data object Connected : RealtimeEvent

    data class Message(val message: WsServerMessage) : RealtimeEvent

    /** Сессия кончилась (refresh отвергнут): переподключаться бессмысленно. */
    data object SessionLost : RealtimeEvent
}

/**
 * Клиент реального времени (реш. 418, 419). Одно соединение на приложение; переподключение с [Backoff]:
 * 4401 — refresh и сразу заново; 1013 — заново после паузы; 1008 — не раньше [policyDelayMs]; любой обрыв — backoff.
 * Подписки на телеметрию устройств переотправляются после каждого подключения.
 */
class RealtimeClient(
    private val connector: WsConnector,
    private val tokens: AccessTokenSource,
    private val url: () -> String?,
    private val scope: CoroutineScope,
    private val backoff: Backoff = Backoff(),
    private val policyDelayMs: Long = POLICY_DELAY_MS,
) {
    private val mutableEvents = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<RealtimeEvent> = mutableEvents.asSharedFlow()

    private val subscriptions = MutableStateFlow<Set<String>>(emptySet())
    private var job: Job? = null

    @Volatile
    private var active: WsSession? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    /** Останавливает цикл и закрывает открытый сокет: он живёт в scope `HttpClient`, а не цикла (RV-7). */
    fun stop() {
        val session = active
        active = null
        job?.cancel()
        job = null
        if (session != null) scope.launch(NonCancellable) { runCatching { session.close() } }
    }

    fun subscribeDevice(deviceId: String) {
        subscriptions.update { it + deviceId }
        sendIfConnected(WsClientMessage.SubscribeDevice(WsDeviceRefDto(deviceId)))
    }

    fun unsubscribeDevice(deviceId: String) {
        subscriptions.update { it - deviceId }
        sendIfConnected(WsClientMessage.UnsubscribeDevice(WsDeviceRefDto(deviceId)))
    }

    /**
     * Сокет мог умереть между проверкой и отправкой: ошибка отправки не роняет приложение (RV-3), а подписка уже
     * в [subscriptions] и уйдёт при следующем подключении.
     */
    private fun sendIfConnected(message: WsClientMessage) {
        val session = active ?: return
        scope.launch {
            try {
                session.send(encode(message))
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                // соединение закрыто: подписки переотправит pump после переподключения
            }
        }
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (true) {
            val wait = when (val close = connectOnce { attempt = 0 }) {
                is Cycle.Stop -> return
                is Cycle.Closed -> waitFor(close.code, attempt++)
            }
            if (wait > 0) delay(wait)
        }
    }

    private suspend fun waitFor(code: Int?, attempt: Int): Long = when (code) {
        WsCloseCodes.POLICY_VIOLATION -> policyDelayMs
        WsCloseCodes.TOKEN_EXPIRED -> 0L
        else -> backoff.delayMs(attempt)
    }

    private sealed interface Cycle {
        data object Stop : Cycle

        data class Closed(val code: Int?) : Cycle
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun connectOnce(onHello: () -> Unit): Cycle {
        val endpoint = url()
        val token = tokens.accessToken()
        return when {
            endpoint == null -> Cycle.Closed(null)
            token == null -> refreshThen(null) { connectOnce(onHello) }
            else -> try {
                val session = connector.connect(endpoint, token)
                pump(session, token, onHello)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WsHandshakeException) {
                if (e.status == HTTP_UNAUTHORIZED) refreshThen(token) { Cycle.Closed(null) } else Cycle.Closed(null)
            } catch (e: Exception) {
                Cycle.Closed(null)
            }
        }
    }

    private suspend fun refreshThen(failed: String?, next: suspend () -> Cycle): Cycle =
        when (tokens.refresh(failed)) {
            is RefreshOutcome.Refreshed -> next()
            RefreshOutcome.SessionEnded -> {
                mutableEvents.emit(RealtimeEvent.SessionLost)
                Cycle.Stop
            }
            RefreshOutcome.Unavailable -> Cycle.Closed(null)
        }

    /**
     * [token] — с ним открыт сокет: после 4401 refresh называет именно его, и если HTTP уже обновил access, лишней
     * ротации нет (RV-13). [active] обнуляется в `finally` — и при обрыве с исключением (RV-3).
     */
    private suspend fun pump(session: WsSession, token: String, onHello: () -> Unit): Cycle {
        var code: Int? = null
        active = session
        try {
            mutableEvents.emit(RealtimeEvent.Connected)
            subscriptions.value.forEach { session.send(encode(WsClientMessage.SubscribeDevice(WsDeviceRefDto(it)))) }
            session.events.transformWhile { emit(it); it !is SocketEvent.Closed }.collect { event ->
                when (event) {
                    is SocketEvent.Text -> decode(event.text)?.let {
                        if (it is WsServerMessage.Hello) onHello()
                        mutableEvents.emit(RealtimeEvent.Message(it))
                    }
                    is SocketEvent.Closed -> code = event.code
                }
            }
        } finally {
            if (active === session) active = null
        }
        return if (code == WsCloseCodes.TOKEN_EXPIRED) {
            refreshThen(token) { Cycle.Closed(WsCloseCodes.TOKEN_EXPIRED) }
        } else {
            Cycle.Closed(code)
        }
    }

    @Suppress("SwallowedException")
    private fun decode(text: String): WsServerMessage? = try {
        RfJson.decodeFromString(WsServerMessage.serializer(), text).takeUnless { it is WsServerMessage.Unknown }
    } catch (e: SerializationException) {
        null
    }

    private fun encode(message: WsClientMessage): String = RfJson.encodeToString(WsClientMessage.serializer(), message)

    private companion object {
        const val EVENT_BUFFER = 64
        const val POLICY_DELAY_MS = 60_000L
        const val HTTP_UNAUTHORIZED = 401
    }
}
