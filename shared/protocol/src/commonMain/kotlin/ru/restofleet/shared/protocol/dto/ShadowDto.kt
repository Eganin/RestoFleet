package ru.restofleet.shared.protocol.dto

import kotlinx.serialization.Serializable

/** `rf/v1/dev/{id}/shadow/desired`, retained, QoS 1. */
@Serializable
data class ShadowDesiredDto(
    val version: Long,
    val telemetry: TelemetryConfigDto,
    val thresholds: ThresholdsDto,
    val watchedApps: List<String> = emptyList(),
    val probes: List<ProbeTargetDto> = emptyList(),
)

/** `rf/v1/dev/{id}/shadow/reported`: та же структура, что desired, плюс отметка применения (ТЗ 7.2). */
@Serializable
data class ShadowReportedDto(
    val version: Long,
    val telemetry: TelemetryConfigDto,
    val thresholds: ThresholdsDto,
    val watchedApps: List<String> = emptyList(),
    val probes: List<ProbeTargetDto> = emptyList(),
    val appliedVersion: Long,
    val appliedAt: Long,
    val errors: List<String> = emptyList(),
)

@Serializable
data class TelemetryConfigDto(val intervalSec: Int)

@Serializable
data class ThresholdsDto(
    val batteryLowPct: Int,
    val tempHighC: Double,
    val fgAbsentMin: Int,
)

@Serializable
data class ProbeTargetDto(
    val id: String,
    /** `http` | `tcp` */
    val type: String,
    val url: String,
    val intervalSec: Int,
    val timeoutMs: Long,
    val failThreshold: Int,
    val reference: Boolean = false,
    /**
     * Ожидаемый HTTP-статус (FR-AG-30, реш. 219): `null` — успех любой код ниже 400; иначе успех только при точном
     * совпадении. Агент возвращает поле в `reported` как есть, иначе `delta` тени не опустеет.
     */
    val expectedStatus: Int? = null,
)
