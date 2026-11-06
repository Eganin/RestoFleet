package ru.restofleet.agent.data.probes

import ru.restofleet.agent.data.collectors.ReadingCheck
import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.model.ProbeError
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.agent.domain.model.ProbeTarget
import ru.restofleet.agent.domain.model.hostReachable
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason
import ru.restofleet.agent.domain.port.ProbeExecutor
import ru.restofleet.shared.kernel.Clock

/** Цель набора P1 и класс ошибки, который она должна давать (`null` — успех). */
internal data class P1Case(val target: ProbeTarget, val expected: ProbeError?)

/**
 * Проверки Lab P1–P3 поверх [ProbeExecutor] и пробы шлюза. Логика здесь, Koin-модуль только передаёт зависимости.
 * Сетевые цели достижимы только на устройстве (в unit-тестах — фейковый [ProbeExecutor]).
 */
class ProbeChecksFactory(
    private val clock: Clock,
    private val executor: ProbeExecutor,
    private val gateway: suspend () -> ProbeResult,
) {
    fun create(): List<CapabilityCheck> = listOf(
        ReadingCheck(CheckId.P1, clock, ::readP1, ::p1Raw),
        ReadingCheck(CheckId.P2, clock, ::readP2, ::p2Raw),
        ReadingCheck(CheckId.P3, clock, ::readP3, ::p3Raw),
    )

    /** P1: каждая цель должна дать свой класс ошибки; расхождения перечисляются в причине. */
    private suspend fun readP1(): Reading<List<ProbeResult>> {
        val results = P1_CASES.map { executor.probe(it.target) }
        val mismatches = P1_CASES.zip(results).filter { (case, r) -> r.error != case.expected }
            .map { (case, r) -> "${case.target.id}: expected ${label(case.expected)}, got ${label(r.error)}" }
        return if (mismatches.isEmpty()) {
            Reading.Available(results)
        } else {
            Reading.Unavailable(UnavailableReason.Failed(mismatches.joinToString("; ")))
        }
    }

    private fun p1Raw(results: List<ProbeResult>): Map<String, String> =
        results.associate { it.targetId to describe(it) }

    /** P2: фазы DNS/TCP/TLS/TTFB успешной цели; незаполненные фазы называются в причине. */
    private suspend fun readP2(): Reading<ProbeResult> {
        val r = executor.probe(P2_TARGET)
        if (!r.ok) {
            return Reading.Unavailable(UnavailableReason.Failed("probe failed: ${label(r.error)}"))
        }
        val missing = listOfNotNull(
            "dns".takeIf { r.phases.dnsMs == null },
            "connect".takeIf { r.phases.connectMs == null },
            "tls".takeIf { r.phases.tlsMs == null },
            "ttfb".takeIf { r.phases.ttfbMs == null },
        )
        return if (missing.isEmpty()) {
            Reading.Available(r)
        } else {
            Reading.Unavailable(UnavailableReason.Failed("missing phases: ${missing.joinToString()}"))
        }
    }

    private fun p2Raw(r: ProbeResult): Map<String, String> = mapOf(
        "dnsMs" to "${r.phases.dnsMs}",
        "connectMs" to "${r.phases.connectMs}",
        "tlsMs" to "${r.phases.tlsMs}",
        "ttfbMs" to "${r.phases.ttfbMs}",
        "totalMs" to "${r.totalMs}",
    )

    /** P3: шлюз достижим, если соединение принято или отвергнуто (RST — значит хост ответил). */
    private suspend fun readP3(): Reading<ProbeResult> {
        val r = gateway()
        return if (r.hostReachable) {
            Reading.Available(r)
        } else {
            Reading.Unavailable(UnavailableReason.Failed("gateway unreachable: ${label(r.error)}"))
        }
    }

    private fun p3Raw(r: ProbeResult): Map<String, String> =
        mapOf("result" to if (r.ok) "connected" else label(r.error), "totalMs" to "${r.totalMs}")

    private fun describe(r: ProbeResult): String =
        listOfNotNull(label(r.error), r.httpStatus?.toString(), "${r.totalMs}ms").joinToString(" ")

    private fun label(error: ProbeError?): String = error?.name?.lowercase() ?: "ok"

    internal companion object {
        private const val P1_TIMEOUT_MS = 5_000L
        private const val P1_BLACKHOLE_TIMEOUT_MS = 3_000L

        val P1_CASES = listOf(
            P1Case(ProbeTarget("google_204", "https://www.google.com/generate_204", P1_TIMEOUT_MS), null),
            P1Case(ProbeTarget("nonexistent", "https://nonexistent.invalid/", P1_TIMEOUT_MS), ProbeError.DNS_FAIL),
            P1Case(ProbeTarget("blackhole", "https://10.255.255.1/", P1_BLACKHOLE_TIMEOUT_MS), ProbeError.TIMEOUT),
            P1Case(ProbeTarget("refused", "https://10.0.2.2:9/", P1_TIMEOUT_MS), ProbeError.CONN_REFUSED),
            P1Case(ProbeTarget("expired_badssl", "https://expired.badssl.com/", P1_TIMEOUT_MS), ProbeError.TLS_ERROR),
            P1Case(ProbeTarget("httpbin_503", "https://httpbin.org/status/503", P1_TIMEOUT_MS), ProbeError.HTTP_5XX),
        )
        val P2_TARGET: ProbeTarget = P1_CASES.first().target
    }
}
