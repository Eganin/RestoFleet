package ru.restofleet.agent.data.mqtt

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.restofleet.agent.data.mqtt.mapping.EventMapper
import ru.restofleet.agent.data.mqtt.mapping.ProbeMapper
import ru.restofleet.agent.data.mqtt.mapping.TelemetryMapper
import ru.restofleet.agent.domain.command.CommandOutcome
import ru.restofleet.agent.domain.command.CommandStatus
import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.agent.domain.model.TelemetrySnapshot
import ru.restofleet.agent.domain.port.PayloadEncoder
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.dto.CommandResultDto
import ru.restofleet.shared.protocol.dto.CommandStatusDto
import ru.restofleet.shared.protocol.dto.EventDto
import ru.restofleet.shared.protocol.dto.ProbeReportDto
import ru.restofleet.shared.protocol.dto.ProbeTargetDto
import ru.restofleet.shared.protocol.dto.ShadowReportedDto
import ru.restofleet.shared.protocol.dto.TelemetryConfigDto
import ru.restofleet.shared.protocol.dto.TelemetryDto
import ru.restofleet.shared.protocol.dto.ThresholdsDto

/**
 * [PayloadEncoder] протокола: JSON UTF-8 по DTO из `shared:protocol` (решение 3.2 о `result`: значения-строки,
 * пустой `Succeeded` даёт `{}`, у `received`/`failed`/`unsupported` поля `result` нет). Без состояния.
 */
class JsonPayloadEncoder : PayloadEncoder {
    override fun telemetry(s: TelemetrySnapshot): ByteArray =
        RfJson.encodeToString(TelemetryDto.serializer(), TelemetryMapper.toDto(s)).encodeToByteArray()

    /** `ts` отчёта — момент завершения последней пробы. */
    override fun probes(r: List<ProbeResult>): ByteArray {
        val dto: ProbeReportDto = ProbeMapper.toDto(r.maxOfOrNull { it.ts } ?: 0L, r)
        return RfJson.encodeToString(ProbeReportDto.serializer(), dto).encodeToByteArray()
    }

    override fun commandResult(cmdId: String, status: String, outcome: CommandOutcome?, ts: Long): ByteArray {
        val dto = CommandResultDto(
            cmdId = cmdId,
            status = statusDto(status),
            ts = ts,
            result = (outcome as? CommandOutcome.Succeeded)?.let { succeeded ->
                JsonObject(succeeded.result.mapValues { JsonPrimitive(it.value) })
            },
            reason = when (outcome) {
                is CommandOutcome.Failed -> outcome.reason
                is CommandOutcome.Unsupported -> outcome.reason
                else -> null
            },
        )
        return RfJson.encodeToString(CommandResultDto.serializer(), dto).encodeToByteArray()
    }

    override fun reported(s: DesiredShadow, appliedAt: Long, errors: List<String>): ByteArray {
        val dto = ShadowReportedDto(
            version = s.version,
            telemetry = TelemetryConfigDto(s.telemetryIntervalSec),
            thresholds = ThresholdsDto(s.thresholds.batteryLowPct, s.thresholds.tempHighC, s.thresholds.fgAbsentMin),
            watchedApps = s.watchedApps,
            probes = s.probes.map {
                ProbeTargetDto(
                    id = it.id,
                    type = "http",
                    url = it.url,
                    intervalSec = it.intervalSec,
                    timeoutMs = it.timeoutMs,
                    failThreshold = it.failThreshold,
                    reference = it.reference,
                )
            },
            appliedVersion = s.version,
            appliedAt = appliedAt,
            errors = errors,
        )
        return RfJson.encodeToString(ShadowReportedDto.serializer(), dto).encodeToByteArray()
    }

    override fun event(e: AgentEvent): ByteArray =
        RfJson.encodeToString(EventDto.serializer(), EventMapper.toDto(e)).encodeToByteArray()

    private fun statusDto(status: String): CommandStatusDto = when (status) {
        CommandStatus.RECEIVED -> CommandStatusDto.RECEIVED
        CommandStatus.SUCCEEDED -> CommandStatusDto.SUCCEEDED
        CommandStatus.FAILED -> CommandStatusDto.FAILED
        CommandStatus.UNSUPPORTED -> CommandStatusDto.UNSUPPORTED
        else -> throw IllegalArgumentException("unknown command status: $status")
    }
}
