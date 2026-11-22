package ru.restofleet.console.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import ru.restofleet.shared.protocol.RfJson

/** Фабрика клиентов Ktor. Движок (OkHttp) передаёт composition root: у приложений разное доверие к TLS. */
object HttpClientFactory {
    const val CONNECT_TIMEOUT_MS = 10_000L
    const val REQUEST_TIMEOUT_MS = 30_000L
    const val PING_INTERVAL_MS = 20_000L

    /** Клиент без токенов: вход и refresh. [debugLogger] — только в debug; заголовок Authorization вырезается. */
    fun bare(engine: HttpClientEngine, endpoint: ServerEndpoint, debugLogger: Logger? = null): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) { json(RfJson) }
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
            }
            install(WebSockets) { pingIntervalMillis = PING_INTERVAL_MS }
            defaultRequest {
                endpoint.baseUrl?.let { url(it) }
                contentType(ContentType.Application.Json)
            }
            if (debugLogger != null) {
                install(Logging) {
                    logger = debugLogger
                    level = LogLevel.HEADERS
                    sanitizeHeader { it.equals(HttpHeaders.Authorization, ignoreCase = true) }
                }
            }
        }

    /** Клиент с Bearer: токен подставляется на каждый запрос, на 401 — один `refresh` и один повтор. */
    fun authorized(
        engine: HttpClientEngine,
        endpoint: ServerEndpoint,
        tokens: AccessTokenSource,
        debugLogger: Logger? = null,
    ): HttpClient = bare(engine, endpoint, debugLogger).also { client ->
        client.plugin(HttpSend).intercept { request ->
            val sent = tokens.accessToken()
            sent?.let { request.header(HttpHeaders.Authorization, "Bearer $it") }
            val call = execute(request)
            if (call.response.status != HttpStatusCode.Unauthorized) return@intercept call
            val outcome = tokens.refresh(sent)
            if (outcome is RefreshOutcome.Refreshed) {
                request.headers.remove(HttpHeaders.Authorization)
                request.header(HttpHeaders.Authorization, "Bearer ${outcome.accessToken}")
                execute(request)
            } else {
                call
            }
        }
    }
}
