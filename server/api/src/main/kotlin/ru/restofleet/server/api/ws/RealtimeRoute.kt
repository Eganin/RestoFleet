package ru.restofleet.server.api.ws

import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.restofleet.server.api.auth.JwtClaims
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.rest.console.ws.WsClientMessage
import ru.restofleet.shared.protocol.rest.console.ws.WsCloseCodes
import ru.restofleet.shared.protocol.rest.console.ws.WsHelloDto
import ru.restofleet.shared.protocol.rest.console.ws.WsServerMessage

/**
 * `GET /api/v1/ws` (реш. 418, 419): маршрут под `authenticate(ADMIN)` — токен только из заголовка `Authorization`
 * upgrade-запроса (провайдер JWT query не читает). Первым уходит `hello`; по `exp` access-токена — закрытие 4401;
 * пятое соединение пользователя, шестая подписка, неразборчивый фрейм — 1008; переполнение очереди — 1013.
 */
fun Route.realtimeRoute(hub: RealtimeHub, clock: Clock) {
    webSocket("/ws") {
        val principal = checkNotNull(call.principal<JWTPrincipal>()) { "route is not under user authentication" }
        val login = principal.payload.getClaim(JwtClaims.LOGIN).asString()
        val expiresAt = principal.expiresAt?.time ?: 0
        val connection = hub.register(login)
        if (connection == null) {
            close(CloseReason(WsCloseCodes.POLICY_VIOLATION.toShort(), "connection_limit"))
            return@webSocket
        }
        try {
            serve(connection, hub.limits, clock, expiresAt)
        } finally {
            hub.unregister(connection)
        }
    }
}

private suspend fun DefaultWebSocketServerSession.serve(
    connection: RealtimeConnection,
    limits: RealtimeSettings,
    clock: Clock,
    expiresAt: Long,
) {
    val hello = WsHelloDto(connection.id, connection.login, expiresAt, limits.maxSubscriptions)
    send(WsServerMessage.Hello(clock.nowMillis(), hello))
    val writer = launch {
        for (message in connection.queue) send(message)
        if (connection.overflowed) close(CloseReason(WsCloseCodes.TRY_AGAIN_LATER.toShort(), "queue_overflow"))
    }
    val expiry = launch {
        while (true) {
            val left = expiresAt - clock.nowMillis()
            if (left <= 0) {
                close(CloseReason(WsCloseCodes.TOKEN_EXPIRED.toShort(), "token_expired"))
                break
            }
            delay(minOf(left, limits.expiryCheckMillis))
        }
    }
    try {
        for (frame in incoming) {
            val violation = handle(frame, connection, limits)
            if (violation != null) {
                close(CloseReason(WsCloseCodes.POLICY_VIOLATION.toShort(), violation))
                break
            }
        }
    } finally {
        writer.cancel()
        expiry.cancel()
    }
}

/** Сообщение клиента; не `null` — причина закрытия 1008. */
private fun handle(frame: Frame, connection: RealtimeConnection, limits: RealtimeSettings): String? =
    when (val message = (frame as? Frame.Text)?.let { decodeOrNull(it.readText()) }) {
        null, is WsClientMessage.Unknown -> BAD_MESSAGE
        is WsClientMessage.SubscribeDevice -> subscribe(connection, message.data.deviceId, limits)
        is WsClientMessage.UnsubscribeDevice -> {
            connection.subscriptions -= message.data.deviceId
            null
        }
    }

private fun subscribe(connection: RealtimeConnection, deviceId: String, limits: RealtimeSettings): String? {
    val id = DeviceId.parse(deviceId)?.value
    val subscriptions = connection.subscriptions
    return when {
        id == null -> BAD_MESSAGE
        id !in subscriptions && subscriptions.size >= limits.maxSubscriptions -> "subscription_limit"
        else -> {
            subscriptions += id
            null
        }
    }
}

private fun decodeOrNull(text: String): WsClientMessage? = try {
    RfJson.decodeFromString(WsClientMessage.serializer(), text)
} catch (_: IllegalArgumentException) {
    // SerializationException — наследник IllegalArgumentException
    null
}

private suspend fun DefaultWebSocketServerSession.send(message: WsServerMessage) {
    outgoing.send(Frame.Text(RfJson.encodeToString(WsServerMessage.serializer(), message)))
}

private const val BAD_MESSAGE = "bad_message"
