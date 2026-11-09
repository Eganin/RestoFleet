package ru.restofleet.agent.domain.model

/**
 * Желаемая конфигурация агента из `shadow/desired`. [version] растёт на бэкенде; агент применяет только новее
 * [ru.restofleet.agent.domain.port.ShadowStore.appliedVersion]. [thresholds] агент сам не применяет (их проверяет
 * бэкенд), но возвращает в `shadow/reported` как эхо (ТЗ 7.2).
 */
data class DesiredShadow(
    val version: Long,
    val telemetryIntervalSec: Int,
    val watchedApps: List<String>,
    val probes: List<ProbeTarget>,
    val thresholds: Thresholds = Thresholds(),
)

/** Пороги из `shadow/desired`; значения по умолчанию — из примера ТЗ 7.2. */
data class Thresholds(
    val batteryLowPct: Int = 20,
    val tempHighC: Double = 45.0,
    val fgAbsentMin: Int = 10,
)
