package ru.restofleet.console.core.testing

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import ru.restofleet.shared.protocol.RfJson

/** Клиент на `MockEngine` с теми же настройками JSON, что и боевой. Адрес — заглушка https://test.local. */
object MockHttp {
    const val BASE_URL = "https://test.local"

    fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) { json(RfJson) }
            defaultRequest {
                url(BASE_URL)
                contentType(ContentType.Application.Json)
            }
        }

    fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    fun MockRequestHandleScope.noContent(): HttpResponseData = respond("", HttpStatusCode.NoContent)
}
