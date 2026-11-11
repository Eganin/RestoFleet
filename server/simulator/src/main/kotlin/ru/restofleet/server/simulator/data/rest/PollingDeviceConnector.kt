package ru.restofleet.server.simulator.data.rest

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.restofleet.server.simulator.data.protocol.InboundMapper
import ru.restofleet.server.simulator.data.protocol.ProtocolMapper
import ru.restofleet.server.simulator.domain.model.CommandReply
import ru.restofleet.server.simulator.domain.model.DeviceCredentials
import ru.restofleet.server.simulator.domain.model.IncomingCommand
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
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.dto.CommandResultDto
import ru.restofleet.shared.protocol.rest.PollTelemetryRequestDto
import ru.restofleet.shared.protocol.rest.RestPaths
import java.util.concurrent.TimeUnit

/**
 * REST-polling транспорт (FR-SM-06, FR-BE-54, P9): HTTP Basic учёткой устройства. У каждого устройства — своё
 * keep-alive соединение (пул на одно соединение поверх общего диспетчера [base]), как у настоящего клиента.
 * [apiBase] — корень API бэкенда (`http://backend:8080` или через Toxiproxy `http://toxiproxy:18080`).
 */
class PollingDeviceConnector(
    private val base: OkHttpClient,
    apiBase: String,
    private val metrics: RunMetrics,
    private val ticker: Ticker,
) : DeviceConnector {
    private val root: HttpUrl = apiBase.trimEnd('/').toHttpUrl()

    override fun link(credentials: DeviceCredentials, victim: Boolean): DeviceLink = PollingDeviceLink(
        base.newBuilder().connectionPool(ConnectionPool(1, KEEP_ALIVE_MIN, TimeUnit.MINUTES)).build(),
        root, credentials, metrics, ticker,
    )

    override suspend fun shutdown() = Unit

    private companion object {
        const val KEEP_ALIVE_MIN = 5L
    }
}

/**
 * Устройство в REST-режиме: телеметрия — `POST /poll/telemetry` (вместе с накопленными событиями и последним отчётом
 * проб), команды — `GET /poll/commands`, итог — `POST /poll/commands/result` (только итог: доставку отмечает сам
 * опрос, реш. 281). Тени в REST-контракте нет — `reported` не отправляется. Неотправленные события копятся (до 100).
 */
@Suppress("TooManyFunctions") // операции порта DeviceLink + два помощника HTTP
class PollingDeviceLink(
    private val client: OkHttpClient,
    private val root: HttpUrl,
    credentials: DeviceCredentials,
    private val metrics: RunMetrics,
    private val ticker: Ticker,
) : DeviceLink {
    override val pushesCommands: Boolean = false
    override val incoming: Flow<Incoming> = emptyFlow()

    private val authorization = Credentials.basic(credentials.deviceId, credentials.password)
    private val pendingEvents = ArrayDeque<SimEvent>()
    private var pendingProbe: ProbeReport? = null

    override suspend fun open(status: StatusInfo): Boolean = true

    override suspend fun sendTelemetry(sample: TelemetrySample): Boolean {
        val (events, probe) = synchronized(pendingEvents) {
            (pendingEvents.toList() to pendingProbe).also {
                pendingEvents.clear()
                pendingProbe = null
            }
        }
        val body = RfJson.encodeToString(
            PollTelemetryRequestDto.serializer(), ProtocolMapper.pollTelemetry(sample, probe, events),
        )
        val ok = post(RestPaths.POLL_TELEMETRY, body)
        if (!ok) {
            synchronized(pendingEvents) {
                events.asReversed().forEach { pendingEvents.addFirst(it) }
                while (pendingEvents.size > MAX_PENDING_EVENTS) pendingEvents.removeFirst()
                if (pendingProbe == null) pendingProbe = probe
            }
        }
        return ok
    }

    override suspend fun sendEvents(events: List<SimEvent>): Boolean {
        synchronized(pendingEvents) {
            pendingEvents.addAll(events)
            while (pendingEvents.size > MAX_PENDING_EVENTS) pendingEvents.removeFirst()
        }
        return true
    }

    override suspend fun sendProbe(report: ProbeReport): Boolean {
        synchronized(pendingEvents) { pendingProbe = report }
        return true
    }

    override suspend fun sendReported(reported: ReportedShadow): Boolean = true

    override suspend fun reply(reply: CommandReply): Boolean = post(
        RestPaths.POLL_COMMAND_RESULT,
        RfJson.encodeToString(CommandResultDto.serializer(), ProtocolMapper.commandResult(reply)),
    )

    override suspend fun poll(): List<IncomingCommand>? {
        val request = Request.Builder().url(url(RestPaths.POLL_COMMANDS)).header(AUTH, authorization).get().build()
        val response = client.sendOrNull(request)
        val receivedAt = ticker.nanos()
        if (response == null || response.code != OK) {
            metrics.requestFailed()
            return null
        }
        metrics.request(0, response.body.length)
        return InboundMapper.pollCommands(response.body, receivedAt)
    }

    override suspend fun close() {
        client.connectionPool.evictAll()
    }

    /** Без постоянного соединения пропажа — просто прекращение опросов (их останавливает раннер). */
    override suspend fun vanish(mode: LossMode) = Unit

    override suspend fun release() {
        client.connectionPool.evictAll()
    }

    private suspend fun post(path: String, body: String): Boolean {
        val request = Request.Builder().url(url(path)).header(AUTH, authorization).post(body.jsonBody()).build()
        val response = client.sendOrNull(request)
        val ok = response != null && response.code == NO_CONTENT
        if (ok) metrics.request(body.length, response.body.length) else metrics.requestFailed()
        return ok
    }

    private fun url(path: String): HttpUrl = root.newBuilder().encodedPath(path).build()

    private companion object {
        const val AUTH = "Authorization"
        const val OK = 200
        const val NO_CONTENT = 204
        const val MAX_PENDING_EVENTS = 100
    }
}
