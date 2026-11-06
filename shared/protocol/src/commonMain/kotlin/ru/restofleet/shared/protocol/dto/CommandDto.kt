package ru.restofleet.shared.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `rf/v1/dev/{id}/cmd/req`, QoS 1. MQTT 5: Response Topic, Correlation Data = cmdId, Message Expiry Interval.
 * `expiresAt` — правка P2: агент не исполняет истёкшую команду.
 */
@Serializable
data class CommandRequestDto(
    val cmdId: String,
    val type: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val expiresAt: Long? = null,
)

/** `rf/v1/dev/{id}/cmd/res`, QoS 1. Сначала `received` (правка P1), затем итоговый статус. */
@Serializable
data class CommandResultDto(
    val cmdId: String,
    val status: CommandStatusDto,
    val ts: Long,
    val result: JsonObject? = null,
    /** причина для `failed` / `unsupported`, например `requires_device_owner`, `expired` */
    val reason: String? = null,
)

@Serializable
enum class CommandStatusDto {
    @SerialName("received") RECEIVED,
    @SerialName("succeeded") SUCCEEDED,
    @SerialName("failed") FAILED,
    @SerialName("unsupported") UNSUPPORTED,
}
