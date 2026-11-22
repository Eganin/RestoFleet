package ru.restofleet.agent.data.mqtt.mapping

import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.shared.protocol.dto.AgentLifecycleEventDto
import ru.restofleet.shared.protocol.dto.CapabilityCheckEventDto
import ru.restofleet.shared.protocol.dto.DebugSeqEventDto
import ru.restofleet.shared.protocol.dto.EventDto
import ru.restofleet.shared.protocol.dto.FgAbsentEventDto
import ru.restofleet.shared.protocol.dto.FgChangedEventDto
import ru.restofleet.shared.protocol.dto.NetChangedEventDto
import ru.restofleet.shared.protocol.dto.PackageChangedEventDto
import ru.restofleet.shared.protocol.dto.ProbeStateEventDto
import ru.restofleet.shared.protocol.dto.ThresholdEventDto

internal object EventMapper {
    fun toDto(event: AgentEvent): EventDto = when (event) {
        is AgentEvent.ProbeStateChanged -> ProbeStateEventDto(
            ts = event.ts,
            target = event.target,
            state = if (event.up) "up" else "down",
            error = event.error?.let(ProbeMapper::errorToWire),
            failures = event.failures,
        )
        is AgentEvent.NetworkChanged -> NetChangedEventDto(event.ts, TelemetryMapper.netToDto(event.state))
        is AgentEvent.Lifecycle -> AgentLifecycleEventDto(event.ts, event.phase.name.lowercase())
        is AgentEvent.PackageChanged -> PackageChangedEventDto(
            ts = event.ts,
            pkg = event.pkg,
            change = event.change.name.lowercase(),
            versionCode = event.versionCode,
        )
        // raw передаётся как есть: серийный номер (R13) маскируется у источника, тут PII не добавляется
        is AgentEvent.CapabilityChecked -> CapabilityCheckEventDto(
            ts = event.ts,
            checkId = event.result.id.name,
            status = event.result.status.name,
            raw = event.result.raw,
            note = event.result.note,
        )
        is AgentEvent.DebugSeq -> DebugSeqEventDto(event.ts, event.seq, event.batchId)
        is AgentEvent.ThresholdCrossed -> ThresholdEventDto(
            ts = event.ts,
            metric = event.metric.name.lowercase(),
            state = if (event.entered) "enter" else "exit",
            value = event.value,
            limit = event.limit,
        )
        is AgentEvent.ForegroundChanged -> FgChangedEventDto(event.ts, event.from, event.to)
        is AgentEvent.ForegroundAbsent -> FgAbsentEventDto(
            ts = event.ts,
            pkg = event.pkg,
            sinceTs = event.sinceTs,
            limitMin = event.limitMin,
            foregroundApp = event.foregroundApp,
        )
    }
}
