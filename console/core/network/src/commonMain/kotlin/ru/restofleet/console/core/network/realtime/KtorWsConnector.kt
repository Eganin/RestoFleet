package ru.restofleet.console.core.network.realtime

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.DefaultWebSocketSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/** [WsConnector] на Ktor WebSockets. Тонкий адаптер без логики: проверяется интеграционно в валидации (реш. 471). */
class KtorWsConnector(private val client: HttpClient) : WsConnector {
    @Suppress("TooGenericExceptionCaught")
    override suspend fun connect(url: String, accessToken: String): WsSession = try {
        KtorWsSession(client.webSocketSession(url) { header(HttpHeaders.Authorization, "Bearer $accessToken") })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw WsHandshakeException(status = (e as? ResponseException)?.response?.status?.value, cause = e)
    }
}

private class KtorWsSession(private val session: DefaultWebSocketSession) : WsSession {
    override val events: Flow<SocketEvent> = flow {
        try {
            for (frame in session.incoming) {
                if (frame is Frame.Text) emit(SocketEvent.Text(frame.readText()))
            }
        } catch (@Suppress("SwallowedException") e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            // обычное закрытие канала
        }
        val reason = withTimeoutOrNull(CLOSE_REASON_WAIT_MS) { session.closeReason.await() }
        emit(SocketEvent.Closed(reason?.code?.toInt()))
    }

    override suspend fun send(text: String) = session.send(Frame.Text(text))

    override suspend fun close() = session.close()

    private companion object {
        const val CLOSE_REASON_WAIT_MS = 1_000L
    }
}
