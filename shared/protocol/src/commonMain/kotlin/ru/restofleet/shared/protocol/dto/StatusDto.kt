package ru.restofleet.shared.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `rf/v1/dev/{id}/status`, retained, QoS 1. Он же LWT (`state=offline`, `reason=lwt`). `connId` — правка P3. */
@Serializable
data class StatusDto(
    val state: DeviceStateDto,
    val ts: Long,
    val connId: String,
    val agentVersion: String? = null,
    /** `normal` | `device_owner` */
    val mode: String? = null,
    /** `kitchen` | `hall` | `assembly` */
    val role: String? = null,
    val bootId: String? = null,
    /** `lwt` для сообщения последней воли */
    val reason: String? = null,
    val device: DeviceInfoDto? = null,
)

@Serializable
enum class DeviceStateDto {
    @SerialName("online") ONLINE,
    @SerialName("offline") OFFLINE,
}

/** Модель и ОС (R6): передаются в `status`, а не в каждой телеметрии. */
@Serializable
data class DeviceInfoDto(
    val model: String,
    val manufacturer: String,
    val osVersion: String,
    val apiLevel: Int,
)
