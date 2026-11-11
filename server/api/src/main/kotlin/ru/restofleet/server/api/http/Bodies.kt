package ru.restofleet.server.api.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import ru.restofleet.server.api.error.ApiException
import ru.restofleet.server.api.error.ErrorBody
import ru.restofleet.shared.protocol.RfJson

/** Строка от клиента, которую запишут в БД: без NUL (PostgreSQL отвергнет) и не длиннее [max] символов. */
fun requireText(field: String, value: String, max: Int): String = when {
    value.contains('\u0000') -> throw ApiException.validation(field, "nul_char")
    value.length > max -> throw ApiException.validation(field, "too_long")
    else -> value
}

/**
 * Тело — JSON-объект (`PUT /shadow`, параметры команды). NUL — 400 до разбора: `jsonb` не принимает `\u0000`, иначе
 * запрос дошёл бы до БД и стал 500.
 */
fun parseJsonObject(text: String): JsonObject {
    val error = when {
        text.contains('\u0000') || text.contains("\\u0000") -> ApiException.validation("body", "nul_char")
        else -> when (val element = parseOrNull(text)) {
            null -> ApiException(HttpStatusCode.BadRequest, ErrorBody("bad_request", "malformed request body"))
            is JsonObject -> return element
            else -> ApiException.validation("body", "not_an_object")
        }
    }
    throw error
}

private fun parseOrNull(text: String) = try {
    RfJson.parseToJsonElement(text)
} catch (_: IllegalArgumentException) {
    null
}
