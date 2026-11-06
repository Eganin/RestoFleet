package ru.restofleet.agent.data.report

import kotlinx.serialization.Serializable
import ru.restofleet.agent.domain.capability.CapabilityReport
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.DeviceInfo

/** Форма JSON-отчёта Capability Lab; живёт в data, чтобы domain не знал о сериализации. */
@Serializable
internal data class ReportDto(val deviceInfo: DeviceInfoDto, val ts: Long, val results: List<ResultDto>)

@Serializable
internal data class DeviceInfoDto(
    val apiLevel: Int,
    val model: String,
    val isEmulator: Boolean,
    val mode: String,
    val agentVersion: String,
)

@Serializable
internal data class ResultDto(
    val id: String,
    val status: String,
    val raw: Map<String, String>,
    val note: String?,
    val ts: Long,
)

internal fun CapabilityReport.toDto() = ReportDto(device.toDto(), generatedAt, results.map { it.toDto() })

private fun DeviceInfo.toDto() = DeviceInfoDto(apiLevel, model, isEmulator, mode.name.lowercase(), agentVersion)

private fun CheckResult.toDto() = ResultDto(id.name, status.name.lowercase(), raw, note, checkedAt)
