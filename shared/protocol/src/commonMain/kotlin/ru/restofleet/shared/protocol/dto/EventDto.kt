package ru.restofleet.shared.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `rf/v1/dev/{id}/events`, QoS 1. Подтип определяется полем `type` (RfJson.classDiscriminator). */
@Serializable
sealed interface EventDto {
    val ts: Long
}

@Serializable
@SerialName("probe_state")
data class ProbeStateEventDto(
    override val ts: Long,
    val target: String,
    /** `up` | `down` */
    val state: String,
    val error: String? = null,
    val failures: Int,
) : EventDto

@Serializable
@SerialName("net_changed")
data class NetChangedEventDto(
    override val ts: Long,
    val net: NetDto,
) : EventDto

@Serializable
@SerialName("agent_lifecycle")
data class AgentLifecycleEventDto(
    override val ts: Long,
    /** `boot` | `start` | `stop` */
    val phase: String,
) : EventDto

@Serializable
@SerialName("package_changed")
data class PackageChangedEventDto(
    override val ts: Long,
    val pkg: String,
    /** `added` | `replaced` | `removed` */
    val change: String,
    val versionCode: Long? = null,
) : EventDto

/** Результат проверки Capability Lab, отправленный кнопкой «В MQTT» (MVP). */
@Serializable
@SerialName("capability_check")
data class CapabilityCheckEventDto(
    override val ts: Long,
    val checkId: String,
    /** `OK` | `LIMITED` | `UNAVAILABLE` | `NOT_APPLICABLE` | `NOT_CHECKED` */
    val status: String,
    val raw: Map<String, String> = emptyMap(),
    val note: String? = null,
) : EventDto

/**
 * Только debug (проверка M7: порядок и полнота досылки outbox): [seq] 1..N в пачке [batchId]. Агент в release такие
 * события не создаёт, сервер их игнорирует.
 */
@Serializable
@SerialName("debug_seq")
data class DebugSeqEventDto(
    override val ts: Long,
    val seq: Int,
    val batchId: String,
) : EventDto

/**
 * Пересечение порога (FR-AG-40, реш. 202, 218): шлётся только при входе в зону (`enter`) и выходе из неё (`exit`,
 * с гистерезисом), а не на каждом тике. [metric]: `battery_low` (заряд, %, [limit] = `thresholds.batteryLowPct`),
 * `temp_high` (температура батареи, °C, [limit] = `thresholds.tempHighC`), `thermal` (код `THERMAL_STATUS_*` 0..6,
 * [limit] — порог `SEVERE` = 3).
 */
@Serializable
@SerialName("threshold")
data class ThresholdEventDto(
    override val ts: Long,
    /** `battery_low` | `temp_high` | `thermal` */
    val metric: String,
    /** `enter` | `exit` */
    val state: String,
    val value: Double,
    val limit: Double,
) : EventDto

/** Смена приложения на переднем плане (FR-AG-41, реш. 220); `null` — приложение неизвестно (нет usage access). */
@Serializable
@SerialName("fg_changed")
data class FgChangedEventDto(
    override val ts: Long,
    val from: String? = null,
    val to: String? = null,
) : EventDto

/**
 * Отслеживаемое приложение [pkg] не на переднем плане дольше [limitMin] минут (`thresholds.fgAbsentMin`, FR-AG-41,
 * реш. 220). Шлётся один раз на эпизод отсутствия; возвращение видно по `fg_changed`. [sinceTs] — когда приложение
 * ушло с экрана, [foregroundApp] — что на экране сейчас (если известно).
 */
@Serializable
@SerialName("fg_absent")
data class FgAbsentEventDto(
    override val ts: Long,
    val pkg: String,
    val sinceTs: Long,
    val limitMin: Int,
    val foregroundApp: String? = null,
) : EventDto
