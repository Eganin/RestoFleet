package ru.restofleet.agent.domain.model

data class TelemetrySnapshot(
    val ts: Long,
    val seq: Long,
    val battery: BatteryState?,
    val thermal: ThermalState?,
    val network: NetworkState?,
    val resources: DeviceResources?,
    val foregroundApp: ForegroundApp?,
    val apps: List<AppVersion>,
    val unavailable: Map<String, UnavailableReason>,
)
