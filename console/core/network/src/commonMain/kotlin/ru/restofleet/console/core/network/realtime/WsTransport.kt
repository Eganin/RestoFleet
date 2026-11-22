package ru.restofleet.console.core.network.realtime

import kotlinx.coroutines.flow.Flow

/** Событие сокета: текстовый кадр или закрытие (код может отсутствовать при обрыве). */
sealed interface SocketEvent {
    data class Text(val text: String) : SocketEvent

    data class Closed(val code: Int?) : SocketEvent
}

/** Одно подключение. [events] заканчивается событием [SocketEvent.Closed]. */
interface WsSession {
    val events: Flow<SocketEvent>

    suspend fun send(text: String)

    suspend fun close()
}

/** Отказ при рукопожатии (например, 401 на upgrade-запрос). */
class WsHandshakeException(val status: Int?, cause: Throwable? = null) :
    Exception("ws handshake failed: $status", cause)

/** Порт подключения; токен уходит только в заголовке `Authorization` upgrade-запроса (реш. 418). */
fun interface WsConnector {
    suspend fun connect(url: String, accessToken: String): WsSession
}
