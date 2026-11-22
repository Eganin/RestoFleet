package ru.restofleet.server.api.ws

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.restofleet.server.api.alert.toDto
import ru.restofleet.server.api.device.TelemetryMetrics
import ru.restofleet.server.api.device.toDto
import ru.restofleet.server.api.incident.toDto
import ru.restofleet.server.domain.alert.usecase.GetSummary
import ru.restofleet.server.domain.realtime.EventBus
import ru.restofleet.server.domain.realtime.RealtimeEvent
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.protocol.rest.console.ws.WsDeviceStatusDto
import ru.restofleet.shared.protocol.rest.console.ws.WsResyncDto
import ru.restofleet.shared.protocol.rest.console.ws.WsServerMessage
import ru.restofleet.shared.protocol.rest.console.ws.WsTelemetryDto
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Рассылка событий шины по соединениям WebSocket (FR-BE-33, 51; реш. 419). Один подписчик шины раскладывает события
 * по очередям соединений (не больше [RealtimeSettings.queueCapacity]): переполнение — соединение закрывается 1013,
 * медленный клиент не тормозит остальных. Телеметрия — только подписанным; статусы, тревоги, инциденты — всем.
 * `summary_changed` — не чаще раза в [RealtimeSettings.summaryMinIntervalMillis] и только после изменений. Рост
 * счётчика потерь шины [droppedEvents] — `resync` всем.
 */
class RealtimeHub(
    private val bus: EventBus,
    private val summary: GetSummary,
    private val clock: Clock,
    private val settings: RealtimeSettings = RealtimeSettings(),
    private val droppedEvents: () -> Long = { 0 },
) {
    private val log = KotlinLogging.logger {}
    private val connections = ConcurrentHashMap.newKeySet<RealtimeConnection>()
    private val perUser = ConcurrentHashMap<String, AtomicInteger>()
    private val summaryDirty = AtomicBoolean(false)
    private val lastDropped = AtomicLong(0)
    private val sequence = AtomicLong(0)

    val limits: RealtimeSettings get() = settings

    /** Подписка на шину и таймер сводки в [scope] приложения. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        launch { bus.events().collect { onEvent(it) } }
        launch {
            while (isActive) {
                delay(settings.summaryMinIntervalMillis)
                tick()
            }
        }
    }

    /** Новое соединение пользователя; `null` — у него уже [RealtimeSettings.maxConnectionsPerUser] соединений. */
    fun register(login: String): RealtimeConnection? {
        val counter = perUser.computeIfAbsent(login) { AtomicInteger() }
        if (counter.incrementAndGet() > settings.maxConnectionsPerUser) {
            counter.decrementAndGet()
            return null
        }
        val connection = RealtimeConnection("ws-${sequence.incrementAndGet()}", login, settings.queueCapacity)
        connections += connection
        return connection
    }

    fun unregister(connection: RealtimeConnection) {
        if (connections.remove(connection)) perUser[connection.login]?.decrementAndGet()
        connection.queue.close()
    }

    /** Открытые соединения (для тестов и диагностики). */
    fun connections(): Set<RealtimeConnection> = connections.toSet()

    private fun onEvent(event: RealtimeEvent) {
        val ts = clock.nowMillis()
        when (event) {
            is RealtimeEvent.TelemetryReceived -> {
                val id = event.snapshot.deviceId.value
                val message = WsServerMessage.Telemetry(
                    ts, WsTelemetryDto(id, event.snapshot.toDto(TelemetryMetrics.ALL)),
                )
                connections.filter { id in it.subscriptions }.forEach { it.offer(message) }
            }
            is RealtimeEvent.DeviceStatusChanged -> broadcast(
                WsServerMessage.DeviceStatus(
                    ts,
                    WsDeviceStatusDto(
                        event.deviceId.value, event.restaurantId.value, event.status.code, event.lastSeenAt,
                        event.reason,
                    ),
                ),
            )
            is RealtimeEvent.AlertChanged -> broadcast(WsServerMessage.Alert(ts, event.view.toDto()))
            is RealtimeEvent.IncidentChanged -> broadcast(WsServerMessage.Incident(ts, event.view.toDto()))
        }
    }

    private fun broadcast(message: WsServerMessage) {
        summaryDirty.set(true)
        connections.forEach { it.offer(message) }
    }

    /** Потери шины → `resync`; изменения → `summary_changed` (сбой сводки не останавливает таймер). */
    internal suspend fun tick() {
        val dropped = droppedEvents()
        if (dropped > lastDropped.getAndSet(dropped)) {
            val resync = WsServerMessage.Resync(clock.nowMillis(), WsResyncDto(WsResyncDto.EVENTS_DROPPED))
            connections.forEach { it.offer(resync) }
        }
        if (connections.isEmpty() || !summaryDirty.getAndSet(false)) return
        try {
            val message = WsServerMessage.SummaryChanged(clock.nowMillis(), summary().toDto())
            connections.forEach { it.offer(message) }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            summaryDirty.set(true)
            log.warn { "summary for websocket failed: ${e.javaClass.name}" }
        }
    }
}

/** Соединение: очередь исходящих, подписки на телеметрию; переполнение очереди — признак для закрытия 1013. */
class RealtimeConnection(val id: String, val login: String, capacity: Int) {
    val queue = Channel<WsServerMessage>(capacity)
    val subscriptions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    var overflowed: Boolean = false
        private set

    fun offer(message: WsServerMessage) {
        if (!queue.trySend(message).isSuccess && !queue.isClosedForSend) overflow()
    }

    /** Очередь переполнена: дальше — закрытие соединения кодом 1013 и `resync` клиента через REST. */
    fun overflow() {
        overflowed = true
        queue.close()
    }
}
