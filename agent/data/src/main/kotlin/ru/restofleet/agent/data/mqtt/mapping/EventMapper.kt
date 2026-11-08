package ru.restofleet.agent.data.mqtt.mapping

import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.shared.protocol.dto.AgentLifecycleEventDto
import ru.restofleet.shared.protocol.dto.CapabilityCheckEventDto
import ru.restofleet.shared.protocol.dto.EventDto
import ru.restofleet.shared.protocol.dto.NetChangedEventDto
import ru.restofleet.shared.protocol.dto.PackageChangedEventDto
import ru.restofleet.shared.protocol.dto.ProbeStateEventDto

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
    }
}
