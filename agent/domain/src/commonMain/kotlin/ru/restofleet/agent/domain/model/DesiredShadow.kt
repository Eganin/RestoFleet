package ru.restofleet.agent.domain.model

/**
 * Желаемая конфигурация агента из `shadow/desired`. [version] растёт на бэкенде; агент применяет только новее
 * [ru.restofleet.agent.domain.port.ShadowStore.appliedVersion]. [thresholds] агент проверяет сам на каждом тике
 * телеметрии (`EvaluateThresholds`, FR-AG-40/41, реш. 202) и возвращает в `shadow/reported` как эхо.
 */
data class DesiredShadow(
    val version: Long,
    val telemetryIntervalSec: Int,
    val watchedApps: List<String>,
    val probes: List<ProbeTarget>,
    val thresholds: Thresholds = Thresholds(),
)

/**
 * Пороги из `shadow/desired`; значения по умолчанию — из примера ТЗ 7.2 (действуют и до первой тени). Порог теплового
 * статуса в тени не задаётся: это константа агента `SEVERE` (реш. 218).
 */
data class Thresholds(
    val batteryLowPct: Int = 20,
    val tempHighC: Double = 45.0,
    val fgAbsentMin: Int = 10,
)
