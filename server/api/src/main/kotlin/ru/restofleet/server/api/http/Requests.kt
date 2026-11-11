package ru.restofleet.server.api.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import ru.restofleet.server.api.error.ApiException
import ru.restofleet.server.api.error.ErrorBody
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.common.TimeRange
import ru.restofleet.shared.kernel.DeviceId
import java.time.Instant
import java.time.format.DateTimeParseException

/** `{id}` устройства из пути: не `DeviceId` — такого устройства нет (404), а не 400. */
fun ApplicationCall.pathDeviceId(): DeviceId = DeviceId.parse(pathParam("id"))
    ?: throw ApiException(HttpStatusCode.NotFound, ErrorBody("not_found", "device not found"))

fun ApplicationCall.pathParam(name: String): String =
    parameters[name] ?: throw ApiException.validation(name, "required")

/** `from`/`to`: epoch ms или ISO-8601 (`2026-11-11T10:00:00Z`); интервал `[from, to)`. */
fun ApplicationCall.timeRange(): TimeRange = TimeRange(from = timeParam("from"), to = timeParam("to"))

/** `limit` (1…1000, по умолчанию 100) и `offset`; нечисло — 400, вне пределов — приводится к ним. */
fun ApplicationCall.page(): PageRequest = PageRequest.of(
    limit = longParam("limit")?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt(),
    offset = longParam("offset"),
)

fun ApplicationCall.queryParam(name: String): String? = request.queryParameters[name]?.takeIf { it.isNotBlank() }

private fun ApplicationCall.longParam(name: String): Long? =
    queryParam(name)?.let { it.toLongOrNull() ?: throw ApiException.validation(name, "not_integer") }

private fun ApplicationCall.timeParam(name: String): Long? = queryParam(name)?.let { raw ->
    raw.toLongOrNull() ?: try {
        Instant.parse(raw).toEpochMilli()
    } catch (_: DateTimeParseException) {
        throw ApiException.validation(name, "invalid_time")
    }
}

/**
 * Ключ rate limit (реш. 249): адрес клиента из `X-Forwarded-For` (nginx стенда **заменяет** заголовок на
 * `$remote_addr`, поэтому берётся последний элемент), иначе адрес соединения.
 */
fun ApplicationCall.clientKey(): String =
    request.headers[HttpHeaders.XForwardedFor]?.substringAfterLast(',')?.trim()?.takeIf { it.isNotEmpty() }
        ?: request.origin.remoteAddress

/** Ответ несёт секрет (пароль устройства, код регистрации, токены): прокси и браузер его не кэшируют. */
fun ApplicationCall.noStore() {
    response.header(HttpHeaders.CacheControl, "no-store")
}
