package ru.restofleet.console.core.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import ru.restofleet.shared.protocol.rest.console.ConsolePaths
import ru.restofleet.shared.protocol.rest.console.RefreshRequestDto
import ru.restofleet.shared.protocol.rest.console.TokenResponseDto

/** [RefreshApi] поверх клиента без токенов: 400/401/403 — сессия кончилась, всё остальное — повторить позже. */
class KtorRefreshApi(private val client: HttpClient) : RefreshApi {
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun refresh(refreshToken: String): RefreshResult = try {
        val response = client.post(ConsolePaths.REFRESH) { setBody(RefreshRequestDto(refreshToken)) }
        when {
            response.status.isSuccess() -> RefreshResult.Ok(response.body<TokenResponseDto>())
            response.status in REJECTED -> RefreshResult.Rejected
            else -> RefreshResult.Unavailable
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RefreshResult.Unavailable
    }

    private companion object {
        val REJECTED = setOf(HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)
    }
}
