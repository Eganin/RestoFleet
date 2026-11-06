package ru.restofleet.shared.protocol.dto

import kotlinx.serialization.Serializable

/** `rf/v1/dev/{id}/telemetry`, QoS 0 (досылка буфера — QoS 1). Недоступные считывания не передаются. */
@Serializable
data class TelemetryDto(
    val ts: Long,
    val seq: Long,
    val battery: BatteryDto? = null,
    val thermal: ThermalDto? = null,
    val net: NetDto? = null,
    val storage: StorageDto? = null,
    val memory: MemoryDto? = null,
    val uptimeSec: Long? = null,
    val foregroundApp: String? = null,
    val apps: List<AppVersionDto> = emptyList(),
)

@Serializable
data class BatteryDto(
    val level: Int,
    val charging: Boolean,
    /** `ac` | `usb` | `wireless` | `dock` | `none` */
    val plugged: String? = null,
    val tempC: Double? = null,
)

@Serializable
data class ThermalDto(
    /** `NONE` … `SHUTDOWN` (PowerManager.THERMAL_STATUS_*) */
    val status: String? = null,
    val headroom: Double? = null,
)

@Serializable
data class NetDto(
    /** `wifi` | `cellular` | `ethernet` | `none` | `other` */
    val type: String,
    val rssi: Int? = null,
    val validated: Boolean,
    val ssid: String? = null,
)

@Serializable
data class StorageDto(val freeMb: Long)

@Serializable
data class MemoryDto(val availMb: Long)

@Serializable
data class AppVersionDto(
    val pkg: String,
    val versionName: String? = null,
    val versionCode: Long,
)
