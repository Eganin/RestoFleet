package ru.restofleet.console.core.network

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.rest.ApiErrorDto

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409
private const val HTTP_UNPROCESSABLE = 422
private const val HTTP_TOO_MANY = 429

/** Перевод ответа сервера в [ConsoleError] (одно место, одна таблица). */
object ApiErrorMapper {
    fun map(status: Int, error: ApiErrorDto?, retryAfter: String? = null): ConsoleError = when (status) {
        HTTP_UNAUTHORIZED -> ConsoleError.Unauthorized
        HTTP_FORBIDDEN -> ConsoleError.Forbidden
        HTTP_NOT_FOUND -> ConsoleError.NotFound
        HTTP_CONFLICT -> ConsoleError.Conflict(error?.reason ?: error?.code)
        HTTP_BAD_REQUEST, HTTP_UNPROCESSABLE ->
            ConsoleError.Validation(error?.field, error?.message ?: "invalid request")
        HTTP_TOO_MANY -> ConsoleError.RateLimited(retryAfter?.trim()?.toIntOrNull())
        else -> ConsoleError.Server(status, error?.code)
    }

    /** Тело ошибки читается мягко: не-JSON ответ (прокси, 502) не должен ломать маппинг. */
    fun parseBody(text: String): ApiErrorDto? =
        runCatching { RfJson.decodeFromString(ApiErrorDto.serializer(), text) }.getOrNull()
}

/** Ошибка из неуспешного ответа. */
suspend fun HttpResponse.toConsoleError(): ConsoleError =
    ApiErrorMapper.map(status.value, ApiErrorMapper.parseBody(bodyAsText()), headers[HttpHeaders.RetryAfter])

/** Выполняет запрос и разбирает тело в [T]; любые сбои — в [Outcome.Failure], отмена корутины пробрасывается. */
suspend inline fun <reified T> apiCall(crossinline request: suspend () -> HttpResponse): Outcome<T, ConsoleError> =
    guardedCall {
        val response = request()
        if (response.status.isSuccess()) {
            Outcome.Success(response.body<T>())
        } else {
            Outcome.Failure(response.toConsoleError())
        }
    }

/** Запрос без тела в ответе (204). */
suspend inline fun apiCallUnit(crossinline request: suspend () -> HttpResponse): Outcome<Unit, ConsoleError> =
    guardedCall {
        val response = request()
        if (response.status.isSuccess()) Outcome.Success(Unit) else Outcome.Failure(response.toConsoleError())
    }

@PublishedApi
@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal suspend inline fun <T> guardedCall(block: () -> Outcome<T, ConsoleError>): Outcome<T, ConsoleError> = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: ContentConvertException) {
    Outcome.Failure(ConsoleError.Server(0, "bad_response"))
} catch (e: SerializationException) {
    Outcome.Failure(ConsoleError.Server(0, "bad_response"))
} catch (e: Exception) {
    Outcome.Failure(ConsoleError.Network)
}
