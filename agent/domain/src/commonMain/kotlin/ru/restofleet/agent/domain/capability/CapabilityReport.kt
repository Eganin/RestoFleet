package ru.restofleet.agent.domain.capability

enum class AgentMode { NORMAL, DEVICE_OWNER }

data class DeviceInfo(
    val apiLevel: Int,
    val model: String,
    val isEmulator: Boolean,
    val mode: AgentMode,
    val agentVersion: String,
)

/** Отчёт о прогоне проверок; [generatedAt] — epoch millis. */
data class CapabilityReport(
    val device: DeviceInfo,
    val generatedAt: Long,
    val results: List<CheckResult>,
)
