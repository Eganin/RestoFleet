package ru.restofleet.agent.domain.model

/**
 * Желаемая конфигурация агента из `shadow/desired`. [version] растёт на бэкенде; агент применяет только новее
 * [ru.restofleet.agent.domain.port.ShadowStore.appliedVersion].
 */
data class DesiredShadow(
    val version: Long,
    val telemetryIntervalSec: Int,
    val watchedApps: List<String>,
    val probes: List<ProbeTarget>,
)
