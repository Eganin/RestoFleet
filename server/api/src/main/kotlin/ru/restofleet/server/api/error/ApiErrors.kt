package ru.restofleet.server.api.error

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.UnauthorizedResponse
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.shared.kernel.Outcome
import java.sql.SQLException
import java.sql.SQLTransientException

/** Тело любой ошибки API (план 2.3): `code` — машиночитаемый, `field`/`reason` — у ошибок валидации. */
@Serializable
data class ErrorBody(
    val code: String,
    val message: String,
    val field: String? = null,
    val reason: String? = null,
)

/** Ошибка, которую маршрут отдаёт как HTTP-ответ: [StatusPages] превращает её в [ErrorBody]. */
class ApiException(val status: HttpStatusCode, val body: ErrorBody) : RuntimeException(body.code, null, false, false) {
    companion object {
        fun validation(field: String, reason: String) = ApiException(
            HttpStatusCode.BadRequest,
            ErrorBody(ServerError.Validation(field, reason).code, "invalid value of '$field': $reason", field, reason),
        )
    }
}

/** Доменная ошибка → HTTP (реш. 247). */
fun ServerError.toApiException(): ApiException = when (this) {
    is ServerError.NotFound -> ApiException(HttpStatusCode.NotFound, ErrorBody(code, "$entity not found"))
    is ServerError.Conflict -> ApiException(HttpStatusCode.Conflict, ErrorBody(code, reason, reason = reason))
    is ServerError.Validation -> ApiException.validation(field, reason)
    ServerError.Unauthorized -> ApiException(HttpStatusCode.Unauthorized, ErrorBody(code, "invalid credentials"))
    ServerError.TokenInvalid ->
        ApiException(HttpStatusCode.Unauthorized, ErrorBody(code, "provisioning code is not valid"))
    ServerError.TokenExpired -> ApiException(HttpStatusCode.Gone, ErrorBody(code, "provisioning code has expired"))
    ServerError.TokenUsed -> ApiException(HttpStatusCode.Conflict, ErrorBody(code, "provisioning code is already used"))
    is ServerError.DeviceDecommissioned ->
        ApiException(HttpStatusCode.Gone, ErrorBody(code, "device is decommissioned"))
    is ServerError.BrokerUnavailable ->
        ApiException(HttpStatusCode.ServiceUnavailable, ErrorBody(code, "broker unavailable: $operation"))
}

/** Успех — значение, отказ — [ApiException] (его обрабатывает [StatusPages]). */
fun <T> Outcome<T, ServerError>.orThrow(): T = when (this) {
    is Outcome.Success -> value
    is Outcome.Failure -> throw error.toApiException()
}

internal object ErrorCodes {
    const val BAD_REQUEST = "bad_request"
    const val UNAUTHORIZED = "unauthorized"
    const val NOT_FOUND = "not_found"
    const val RATE_LIMITED = "rate_limited"
    const val PAYLOAD_TOO_LARGE = "payload_too_large"
    const val UNAVAILABLE = "unavailable"
    const val INTERNAL = "internal"
}

/**
 * Единый формат ошибок. В тело и в лог не попадают входные данные: сообщения сериализации (в них бывает кусок JSON
 * с паролем) заменяются общими. Необработанное исключение логируется классом и маршрутом, без сообщения (в SQL-ошибках
 * Exposed бывают значения параметров); стек — на уровне DEBUG.
 */
fun Application.installErrorHandling() {
    val log = KotlinLogging.logger("ru.restofleet.server.api.Errors")
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, e.body) }
        exception<ContentTransformationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody(ErrorCodes.BAD_REQUEST, "malformed request body"))
        }
        exception<PayloadTooLargeException> { call, _ ->
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                ErrorBody(ErrorCodes.PAYLOAD_TOO_LARGE, "request body is too large"),
            )
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody(ErrorCodes.BAD_REQUEST, "malformed request"))
        }
        exception<NotFoundException> { call, _ ->
            call.respond(HttpStatusCode.NotFound, ErrorBody(ErrorCodes.NOT_FOUND, "not found"))
        }
        exception<Throwable> { call, e ->
            val unavailable = e.isDatabaseUnavailable()
            log.error { "unhandled ${e.javaClass.name} on ${call.request.httpMethod.value} ${call.request.path()}" }
            log.debug(e) { "stack of unhandled ${e.javaClass.name}" }
            if (unavailable) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorBody(ErrorCodes.UNAVAILABLE, "database unavailable"),
                )
            } else {
                call.respond(HttpStatusCode.InternalServerError, ErrorBody(ErrorCodes.INTERNAL, "internal error"))
            }
        }
        // Отказы плагинов приходят без тела: Basic-провайдер (UnauthorizedResponse) и RateLimit (429)
        status(HttpStatusCode.Unauthorized) { status ->
            val challenge = content as? UnauthorizedResponse ?: return@status
            challenge.headers[HttpHeaders.WWWAuthenticate]
                ?.let { call.response.header(HttpHeaders.WWWAuthenticate, it) }
            call.respond(status, ErrorBody(ErrorCodes.UNAUTHORIZED, "authentication required"))
        }
        status(HttpStatusCode.TooManyRequests) { status ->
            call.respond(status, ErrorBody(ErrorCodes.RATE_LIMITED, "too many requests, retry later"))
        }
        // Нет маршрута — пустой 404 Ktor; ответы маршрутов с телом (ApiException) не трогаем
        status(HttpStatusCode.NotFound) { status ->
            if (content !is OutgoingContent.ByteArrayContent) {
                call.respond(status, ErrorBody(ErrorCodes.NOT_FOUND, "not found"))
            }
        }
    }
}

/** Пул не выдал соединение или связь с БД потеряна (SQLState класса 08) — 503, иначе 500. */
private fun Throwable.isDatabaseUnavailable(): Boolean = generateSequence(this) { it.cause }.take(MAX_CAUSES).any {
    it is SQLTransientException || (it is SQLException && it.sqlState?.startsWith("08") == true)
}

private const val MAX_CAUSES = 10
