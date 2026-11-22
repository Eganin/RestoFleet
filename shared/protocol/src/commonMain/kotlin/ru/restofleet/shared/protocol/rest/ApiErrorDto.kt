package ru.restofleet.shared.protocol.rest

import kotlinx.serialization.Serializable

/**
 * Тело любой ошибки REST API: [code] — машиночитаемый (`validation`, `not_found`, `conflict`, `unauthorized`,
 * `token_invalid`, …), [field] и [reason] — у ошибок валидации и конфликтов. В теле нет входных данных запроса.
 */
@Serializable
data class ApiErrorDto(
    val code: String,
    val message: String,
    val field: String? = null,
    val reason: String? = null,
)
