package ru.restofleet.agent.domain.fake

import ru.restofleet.agent.domain.command.CommandOutcome
import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.agent.domain.model.TelemetrySnapshot
import ru.restofleet.agent.domain.port.PayloadEncoder

/** Запоминает события и отчёты проб, которые просили закодировать; остальное тестам этих use case не нужно. */
class CapturingPayloadEncoder : PayloadEncoder {
    val events = mutableListOf<AgentEvent>()
    val probeReports = mutableListOf<List<ProbeResult>>()

    override fun telemetry(s: TelemetrySnapshot): ByteArray = ByteArray(0)

    override fun probes(r: List<ProbeResult>): ByteArray {
        probeReports += r
        return "probes:${r.size}".toByteArray()
    }

    override fun commandResult(cmdId: String, status: String, outcome: CommandOutcome?, ts: Long): ByteArray =
        ByteArray(0)

    override fun reported(s: DesiredShadow, appliedAt: Long, errors: List<String>): ByteArray = ByteArray(0)

    override fun event(e: AgentEvent): ByteArray {
        events += e
        return "event:${e.ts}".toByteArray()
    }
}
