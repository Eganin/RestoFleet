package ru.restofleet.shared.protocol.rest.console

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** `POST /devices/{id}/commands`: типы — `CommandTypes` протокола (`ping`, `reboot`, …), TTL 1…86 400 с. */
@Serializable
data class SendCommandRequestDto(
    val type: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val ttlSec: Int = DEFAULT_TTL_SEC,
) {
    companion object {
        /** Совпадает с умолчанием домена бэкенда (`CommandRequest.DEFAULT_TTL_SEC`, проверяет тест `server:api`). */
        const val DEFAULT_TTL_SEC = 300
    }
}

/**
 * Команда и её статус (FR-BE-41): `queued → delivered → succeeded | failed | unsupported | expired`; `result` — объект
 * со строковыми значениями из `cmd/res` (реш. 221), `reason` — причина отказа (`expired`, `broker_unavailable`, …).
 * История устройства — `GET /devices/{id}/commands`, новые сначала.
 */
@Serializable
data class CommandDto(
    val cmdId: String,
    val deviceId: String,
    val type: String,
    val params: JsonObject,
    val status: String,
    val createdAt: Long,
    val expiresAt: Long,
    val deliveredAt: Long? = null,
    val finishedAt: Long? = null,
    val result: Map<String, String>? = null,
    val reason: String? = null,
)

/** Статусы команды на бэкенде (FR-BE-41): итоговые — все, кроме `queued` и `delivered`. */
object CommandStatuses {
    const val QUEUED = "queued"
    const val DELIVERED = "delivered"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val UNSUPPORTED = "unsupported"
    const val EXPIRED = "expired"
    val FINAL: Set<String> = setOf(SUCCEEDED, FAILED, UNSUPPORTED, EXPIRED)
}
