package ru.restofleet.agent.data.mqtt.mapping

import ru.restofleet.agent.domain.model.ProbeError
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.shared.probes.ProbeErrorClass
import ru.restofleet.shared.protocol.dto.ProbeReportDto
import ru.restofleet.shared.protocol.dto.ProbeResultDto

internal object ProbeMapper {
    fun toDto(ts: Long, results: List<ProbeResult>): ProbeReportDto =
        ProbeReportDto(ts = ts, results = results.map { it.toDto() })

    /** Имя на проводе (`LOCAL_NETWORK_DENIED` -> `local_network_denied`), см. `ProbeErrorClass.code`. */
    fun errorToWire(error: ProbeError): String = error.code

    /** `null` — ошибки нет; неизвестное имя от более новой стороны -> [ProbeError.OTHER]. */
    fun errorFromWire(wire: String?): ProbeError? = ProbeErrorClass.fromCode(wire)

    private fun ProbeResult.toDto() = ProbeResultDto(
        id = targetId,
        ok = ok,
        status = httpStatus,
        error = error?.let(::errorToWire),
        totalMs = totalMs,
        dnsMs = phases.dnsMs,
        connectMs = phases.connectMs,
        tlsMs = phases.tlsMs,
        ttfbMs = phases.ttfbMs,
    )
}
