package ru.restofleet.shared.protocol.rest

import kotlinx.serialization.Serializable
import ru.restofleet.shared.protocol.dto.DeviceInfoDto

/** `POST /api/v1/provision` (без JWT, rate limit): погашение одноразового кода (FR-AG-10, реш. 210, 222). */
@Serializable
data class ProvisionRequestDto(
    val code: String,
    val deviceInfo: DeviceInfoDto,
    val agentVersion: String,
)

/**
 * Ответ на успешный провижининг. [password] отдаётся один раз (на бэкенде хранится bcrypt-хэш, P9); [role] —
 * `kitchen` | `hall` | `assembly`, задана при выпуске кода (реш. 210). [toString] пароль не раскрывает.
 */
@Serializable
data class ProvisionResponseDto(
    val deviceId: String,
    val password: String,
    val restaurantId: String,
    val role: String,
    val mqtt: MqttEndpointDto,
) {
    override fun toString(): String =
        "ProvisionResponseDto(deviceId=$deviceId, password=***, restaurantId=$restaurantId, role=$role, mqtt=$mqtt)"
}

/** Адрес брокера для агента (TLS 8883). */
@Serializable
data class MqttEndpointDto(
    val host: String,
    val port: Int,
)
