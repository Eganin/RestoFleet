package ru.restofleet.agent.data.mqtt.mapping

import ru.restofleet.agent.domain.model.ProbeError
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.shared.protocol.dto.ProbeReportDto
import ru.restofleet.shared.protocol.dto.ProbeResultDto

internal object ProbeMapper {
    fun toDto(ts: Long, results: List<ProbeResult>): ProbeReportDto =
        ProbeReportDto(ts = ts, results = results.map { it.toDto() })

    /** Имя на проводе: константа в нижнем регистре (`LOCAL_NETWORK_DENIED` -> `local_network_denied`). */
    fun errorToWire(error: ProbeError): String = error.name.lowercase()

    /** `null` — ошибки нет; неизвестное имя от более новой стороны -> [ProbeError.OTHER]. */
    fun errorFromWire(wire: String?): ProbeError? =
        wire?.let { name -> ProbeError.entries.firstOrNull { errorToWire(it) == name } ?: ProbeError.OTHER }

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
