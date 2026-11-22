package ru.restofleet.server.api

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.mockk.coEvery
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Test
import ru.restofleet.server.api.ws.RealtimeSettings
import ru.restofleet.server.domain.alert.Alert
import ru.restofleet.server.domain.alert.AlertState
import ru.restofleet.server.domain.alert.AlertSubject
import ru.restofleet.server.domain.alert.AlertView
import ru.restofleet.server.domain.alert.RuleId
import ru.restofleet.server.domain.alert.Summary
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.realtime.RealtimeEvent
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.rest.console.ws.WsClientMessage
import ru.restofleet.shared.protocol.rest.console.ws.WsCloseCodes
import ru.restofleet.shared.protocol.rest.console.ws.WsDeviceRefDto
import ru.restofleet.shared.protocol.rest.console.ws.WsServerMessage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * WebSocket консоли (план 3.5; реш. 418, 419, 455): аутентификация только заголовком, рассылка и подписки, коды
 * закрытия 4401/1013/1008. Настоящий сервер Netty на свободном порту и клиент OkHttp — реальное время: у
 * `testApplication` виртуальные часы, с ними ping и тайм-ауты WebSocket срабатывают мгновенно.
 */
class RealtimeTest {
    private val kit = ApiTestKit(
        realtimeSettings =
            RealtimeSettings(summaryMinIntervalMillis = 50, expiryCheckMillis = 20, maxSubscriptions = 2),
    )
    private val token = kit.token()
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
        embeddedServer(Netty, port = 0, host = "127.0.0.1") { restofleetApi(kit.useCases, kit.settings) }.start()
    private val port = runBlocking { server.engine.resolvedConnectors().first().port }
    private val client = HttpClient(OkHttp) { install(WebSockets) }

    private val alert = AlertView(
        Alert(
            "a-1", AlertSubject(RuleId.R03, Samples.deviceId), RestaurantId(Samples.RESTAURANT_ID), AlertState.OPEN, 1,
        ),
        "Кухня 1", "MSK-01", "Северная кухня",
    )

    @After
    fun stop() {
        client.close()
        server.stop(0, 0)
    }

    private fun ws(block: suspend DefaultClientWebSocketSession.() -> Unit) = runBlocking {
        withTimeout(TIMEOUT) {
            client.webSocket(
                host = "127.0.0.1", port = port, path = "/api/v1/ws",
                request = { header(HttpHeaders.Authorization, "Bearer $token") }, block = block,
            )
        }
    }

    private suspend fun DefaultClientWebSocketSession.next(): WsServerMessage {
        val frame = incoming.receive() as Frame.Text
        return RfJson.decodeFromString(WsServerMessage.serializer(), frame.readText())
    }

    private suspend fun DefaultClientWebSocketSession.sendClient(message: WsClientMessage) =
        send(Frame.Text(RfJson.encodeToString(WsClientMessage.serializer(), message)))

    private suspend fun DefaultClientWebSocketSession.closedWith(): CloseReason? {
        for (frame in incoming) Unit
        return closeReason.await()
    }

    /** Ждёт, пока сервер обработает подписку (подтверждения в протоколе нет). */
    private suspend fun awaitSubscriptions(count: Int) {
        while (kit.hub.connections().single().subscriptions.size != count) delay(POLL)
    }

    /** HTTP-статус отказа upgrade-запроса (OkHttp сам ставит заголовки Upgrade). */
    private fun handshakeStatus(path: String, authorization: String? = null): Int {
        val result = CompletableFuture<Int>()
        val request = Request.Builder().url("ws://127.0.0.1:$port$path")
            .apply { authorization?.let { header(HttpHeaders.Authorization, it) } }.build()
        val okHttp = OkHttpClient()
        okHttp.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    result.complete(response.code)
                    webSocket.close(NORMAL, null)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    result.complete(response?.code ?: -1)
                }
            },
        )
        return result.get(TIMEOUT, TimeUnit.MILLISECONDS).also { okHttp.dispatcher.executorService.shutdown() }
    }

    @Test
    fun `upgrade without the authorization header or with a token in query is 401`() {
        assertThat(handshakeStatus("/api/v1/ws")).isEqualTo(UNAUTHORIZED)
        assertThat(handshakeStatus("/api/v1/ws?token=$token")).isEqualTo(UNAUTHORIZED)
        assertThat(handshakeStatus("/api/v1/ws?access_token=$token")).isEqualTo(UNAUTHORIZED)
        assertThat(handshakeStatus("/api/v1/ws", "Bearer not-a-jwt")).isEqualTo(UNAUTHORIZED)
        assertThat(handshakeStatus("/api/v1/ws", "Bearer $token")).isEqualTo(SWITCHING_PROTOCOLS)
    }

    @Test
    fun `hello first, then broadcast events and telemetry only for the subscriber`() {
        coEvery { kit.alerts.summary() } returns Summary(1, 2, 1, 0, 1, 1, 0, 1, 1, emptyList())
        ws {
            val hello = next() as WsServerMessage.Hello
            assertThat(hello.data.user).isEqualTo("admin")
            assertThat(hello.data.maxSubscriptions).isEqualTo(2)
            assertThat(hello.data.accessExpiresAt).isGreaterThan(kit.clock.now)

            kit.bus.publish(RealtimeEvent.TelemetryReceived(Samples.telemetry))
            kit.bus.publish(
                RealtimeEvent.DeviceStatusChanged(
                    Samples.deviceId, RestaurantId(Samples.RESTAURANT_ID), DeviceStatus.OFFLINE, 5, "lwt",
                ),
            )
            val status = next() as WsServerMessage.DeviceStatus
            assertThat(status.data.status).isEqualTo("offline")
            assertThat(status.data.reason).isEqualTo("lwt")

            sendClient(WsClientMessage.SubscribeDevice(WsDeviceRefDto(Samples.deviceId.value)))
            awaitSubscriptions(1)
            kit.bus.publish(RealtimeEvent.TelemetryReceived(Samples.telemetry))
            kit.bus.publish(RealtimeEvent.AlertChanged(alert))
            val received = mutableListOf<WsServerMessage>()
            while (received.none { it is WsServerMessage.Alert }) received += next()
            assertThat(received.filterIsInstance<WsServerMessage.Telemetry>().single().data.deviceId)
                .isEqualTo(Samples.deviceId.value)
            while (received.none { it is WsServerMessage.SummaryChanged }) received += next()

            sendClient(WsClientMessage.UnsubscribeDevice(WsDeviceRefDto(Samples.deviceId.value)))
            awaitSubscriptions(0)
        }
    }

    @Test
    fun `expired access token closes the connection with 4401`() = ws {
        next()
        kit.clock.now += 16 * 60_000L
        assertThat(closedWith()?.code).isEqualTo(WsCloseCodes.TOKEN_EXPIRED.toShort())
    }

    @Test
    fun `queue overflow closes the connection with 1013`() = ws {
        next()
        kit.hub.connections().single().overflow()
        assertThat(closedWith()?.code).isEqualTo(WsCloseCodes.TRY_AGAIN_LATER.toShort())
    }

    @Test
    fun `fifth connection of the user is closed with 1008`() = runBlocking {
        val sessions = (1..4).map {
            client.webSocketSession(host = "127.0.0.1", port = port, path = "/api/v1/ws") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }.also { it.next() }
        }
        ws { assertThat(closedWith()?.code).isEqualTo(WsCloseCodes.POLICY_VIOLATION.toShort()) }
        sessions.forEach { it.close() }
    }

    @Test
    fun `subscription over the limit is closed with 1008`() = ws {
        next()
        listOf("d-1", "d-2", "d-1", "d-3").forEach {
            sendClient(WsClientMessage.SubscribeDevice(WsDeviceRefDto(it)))
        }
        val reason = closedWith()
        assertThat(reason?.code).isEqualTo(WsCloseCodes.POLICY_VIOLATION.toShort())
        assertThat(reason?.message).isEqualTo("subscription_limit")
    }

    @Test
    fun `garbage and binary frames are closed with 1008`() {
        val garbage = listOf(
            Frame.Text("not json"),
            Frame.Text("""{"v":1,"type":"subscribe_restaurant","data":{}}"""),
            Frame.Text("""{"v":1,"type":"subscribe_device","data":{"deviceId":"bad id"}}"""),
            Frame.Binary(true, byteArrayOf(1)),
        )
        garbage.forEach { frame ->
            ws {
                next()
                send(frame)
                val reason = closedWith()
                assertThat(reason?.code).isEqualTo(WsCloseCodes.POLICY_VIOLATION.toShort())
                assertThat(reason?.message).isEqualTo("bad_message")
            }
        }
    }

    @Test
    fun `lost bus events trigger resync and a failing summary does not break the stream`() {
        coEvery { kit.alerts.summary() } throws IllegalStateException("db down")
        ws {
            next()
            kit.bus.publish(RealtimeEvent.AlertChanged(alert))
            assertThat(next()).isInstanceOf(WsServerMessage.Alert::class.java)
            kit.bus.dropped = 3
            val resync = next() as WsServerMessage.Resync
            assertThat(resync.data.reason).isEqualTo("events_dropped")
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val POLL = 5L
        const val NORMAL = 1000
        const val UNAUTHORIZED = 401
        const val SWITCHING_PROTOCOLS = 101
    }
}
