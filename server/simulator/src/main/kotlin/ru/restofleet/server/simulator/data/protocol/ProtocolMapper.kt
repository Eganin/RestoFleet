package ru.restofleet.server.simulator.data.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.restofleet.server.simulator.domain.model.CommandReply
import ru.restofleet.server.simulator.domain.model.DesiredUpdate
import ru.restofleet.server.simulator.domain.model.IncomingCommand
import ru.restofleet.server.simulator.domain.model.ProbeReport
import ru.restofleet.server.simulator.domain.model.ProbeTarget
import ru.restofleet.server.simulator.domain.model.ReplyStatus
import ru.restofleet.server.simulator.domain.model.ReportedShadow
import ru.restofleet.server.simulator.domain.model.SimEvent
import ru.restofleet.server.simulator.domain.model.StatusInfo
import ru.restofleet.server.simulator.domain.model.TelemetrySample
import ru.restofleet.server.simulator.domain.model.Thresholds
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.dto.AppVersionDto
import ru.restofleet.shared.protocol.dto.BatteryDto
import ru.restofleet.shared.protocol.dto.CommandRequestDto
import ru.restofleet.shared.protocol.dto.CommandResultDto
import ru.restofleet.shared.protocol.dto.CommandStatusDto
import ru.restofleet.shared.protocol.dto.DeviceInfoDto
import ru.restofleet.shared.protocol.dto.DeviceStateDto
import ru.restofleet.shared.protocol.dto.EventDto
import ru.restofleet.shared.protocol.dto.MemoryDto
import ru.restofleet.shared.protocol.dto.NetDto
import ru.restofleet.shared.protocol.dto.ProbeReportDto
import ru.restofleet.shared.protocol.dto.ProbeResultDto
import ru.restofleet.shared.protocol.dto.ProbeStateEventDto
import ru.restofleet.shared.protocol.dto.ProbeTargetDto
import ru.restofleet.shared.protocol.dto.ShadowDesiredDto
import ru.restofleet.shared.protocol.dto.ShadowReportedDto
import ru.restofleet.shared.protocol.dto.StatusDto
import ru.restofleet.shared.protocol.dto.StorageDto
import ru.restofleet.shared.protocol.dto.TelemetryConfigDto
import ru.restofleet.shared.protocol.dto.TelemetryDto
import ru.restofleet.shared.protocol.dto.ThermalDto
import ru.restofleet.shared.protocol.dto.ThresholdEventDto
import ru.restofleet.shared.protocol.dto.ThresholdsDto
import ru.restofleet.shared.protocol.rest.PollCommandsResponseDto
import ru.restofleet.shared.protocol.rest.PollTelemetryRequestDto

/**
 * Модели симулятора ↔ DTO `shared:protocol` (FR-SM-02: те же форматы, что у агента; JSON — только `RfJson` и DTO
 * протокола, без самодельных строк).
 */
object ProtocolMapper {
    /** Сведения о «железе» виртуального устройства в `status online`. */
    val DEVICE_INFO = DeviceInfoDto(
        model = "RestoFleet Simulator", manufacturer = "RestoFleet", osVersion = "sim", apiLevel = 36,
    )

    private const val APP_VERSION_NAME = "4.13"
    private const val APP_VERSION_CODE = 13L
    private const val NET_TYPE = "wifi"

    fun telemetry(sample: TelemetrySample) = TelemetryDto(
        ts = sample.ts,
        seq = sample.seq,
        battery = BatteryDto(sample.batteryLevel, sample.charging, sample.plugged, sample.batteryTempC),
        thermal = ThermalDto(status = sample.thermalStatus),
        net = NetDto(type = NET_TYPE, rssi = sample.rssi, validated = true),
        storage = StorageDto(sample.freeMb),
        memory = MemoryDto(sample.availMb),
        uptimeSec = sample.uptimeSec,
        foregroundApp = sample.foregroundApp,
        apps = listOfNotNull(sample.foregroundApp?.let { AppVersionDto(it, APP_VERSION_NAME, APP_VERSION_CODE) }),
    )

    fun event(event: SimEvent): EventDto = when (event) {
        is SimEvent.Threshold -> ThresholdEventDto(event.ts, event.metric, event.state, event.value, event.limit)
        is SimEvent.ProbeState -> ProbeStateEventDto(event.ts, event.target, event.state, event.error, event.failures)
    }

    fun probe(report: ProbeReport) = ProbeReportDto(
        ts = report.ts,
        results = report.results.map { ProbeResultDto(it.id, it.ok, it.status, it.error, it.totalMs) },
    )

    fun reported(reported: ReportedShadow): ShadowReportedDto {
        val config = reported.config
        return ShadowReportedDto(
            version = reported.appliedVersion,
            telemetry = TelemetryConfigDto(config.telemetryIntervalSec),
            thresholds = ThresholdsDto(
                config.thresholds.batteryLowPct, config.thresholds.tempHighC, config.thresholds.fgAbsentMin,
            ),
            watchedApps = config.watchedApps,
            probes = config.probes.map {
                ProbeTargetDto(
                    it.id, it.type, it.url, it.intervalSec, it.timeoutMs, it.failThreshold, it.reference,
                    it.expectedStatus,
                )
            },
            appliedVersion = reported.appliedVersion,
            appliedAt = reported.appliedAt,
            errors = reported.errors,
        )
    }

    fun commandResult(reply: CommandReply) = CommandResultDto(
        cmdId = reply.cmdId,
        status = when (reply.status) {
            ReplyStatus.RECEIVED -> CommandStatusDto.RECEIVED
            ReplyStatus.SUCCEEDED -> CommandStatusDto.SUCCEEDED
            ReplyStatus.FAILED -> CommandStatusDto.FAILED
            ReplyStatus.UNSUPPORTED -> CommandStatusDto.UNSUPPORTED
        },
        ts = reply.ts,
        result = reply.result?.let { fields -> JsonObject(fields.mapValues { JsonPrimitive(it.value) }) },
        reason = reply.reason,
    )

    fun online(status: StatusInfo, connId: String, ts: Long) = StatusDto(
        state = DeviceStateDto.ONLINE,
        ts = ts,
        connId = connId,
        agentVersion = status.agentVersion,
        mode = "normal",
        role = status.role.code,
        bootId = status.bootId,
        device = DEVICE_INFO,
    )

    fun offline(connId: String, ts: Long, reason: String) =
        StatusDto(state = DeviceStateDto.OFFLINE, ts = ts, connId = connId, reason = reason)

    fun pollTelemetry(sample: TelemetrySample, probe: ProbeReport?, events: List<SimEvent>) =
        PollTelemetryRequestDto(telemetry(sample), probe?.let(::probe), events.map(::event))
}
