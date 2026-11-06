package ru.restofleet.agent.data.probes

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.model.ProbeError
import ru.restofleet.agent.domain.model.ProbePhases
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.agent.domain.model.ProbeTarget
import ru.restofleet.agent.domain.port.ProbeExecutor
import ru.restofleet.shared.kernel.Clock

class ProbeChecksFactoryTest {
    private val fullPhases = ProbePhases(dnsMs = 5, connectMs = 10, tlsMs = 30, ttfbMs = 80)
    private val called = mutableListOf<ProbeTarget>()

    /** Идеальный мир: каждая цель даёт ожидаемый класс ошибки; [override] подменяет результат по id цели. */
    private fun executor(override: Map<String, ProbeResult> = emptyMap()) = ProbeExecutor { target ->
        called += target
        override[target.id] ?: when (target.id) {
            "google_204" -> result(target.id, 204, null, fullPhases)
            "nonexistent" -> result(target.id, null, ProbeError.DNS_FAIL)
            "blackhole" -> result(target.id, null, ProbeError.TIMEOUT)
            "refused" -> result(target.id, null, ProbeError.CONN_REFUSED)
            "expired_badssl" -> result(target.id, null, ProbeError.TLS_ERROR)
            "httpbin_503" -> result(target.id, 503, ProbeError.HTTP_5XX, fullPhases)
            else -> error("unexpected target ${target.id}")
        }
    }

    private fun result(
        id: String,
        status: Int?,
        error: ProbeError?,
        phases: ProbePhases = ProbePhases(null, null, null, null),
    ) = ProbeResult(id, error == null, status, error, 120, phases, 1L)

    private fun gatewayResult(error: ProbeError?): ProbeResult {
        val connectMs = if (error == null) 4L else null
        return ProbeResult("gateway", error == null, null, error, 4, ProbePhases(null, connectMs, null, null), 1L)
    }

    private fun checks(exec: ProbeExecutor = executor(), gateway: ProbeResult = gatewayResult(null)) =
        ProbeChecksFactory(Clock { TS }, exec) { gateway }.create()

    private fun run(
        id: CheckId,
        exec: ProbeExecutor = executor(),
        gateway: ProbeResult = gatewayResult(null),
    ): CheckResult = runBlocking { checks(exec, gateway).first { it.id == id }.run() }

    @Test
    fun createsP1P2P3InOrder() {
        assertThat(checks().map { it.id }).containsExactly(CheckId.P1, CheckId.P2, CheckId.P3).inOrder()
    }

    @Test
    fun p1ProbesTheSixSpecifiedTargets() {
        run(CheckId.P1)
        assertThat(called.map { it.url to it.timeoutMs }).containsExactly(
            "https://www.google.com/generate_204" to 5_000L,
            "https://nonexistent.invalid/" to 5_000L,
            "https://10.255.255.1/" to 3_000L,
            "https://10.0.2.2:9/" to 5_000L,
            "https://expired.badssl.com/" to 5_000L,
            "https://httpbin.org/status/503" to 5_000L,
        ).inOrder()
    }

    @Test
    fun p1IsOkWhenEveryClassIsDeterminedCorrectly() {
        val r = run(CheckId.P1)
        assertThat(r.status).isEqualTo(CheckStatus.OK)
        assertThat(r.checkedAt).isEqualTo(TS)
        assertThat(r.raw).containsExactly(
            "google_204", "ok 204 120ms",
            "nonexistent", "dns_fail 120ms",
            "blackhole", "timeout 120ms",
            "refused", "conn_refused 120ms",
            "expired_badssl", "tls_error 120ms",
            "httpbin_503", "http_5xx 503 120ms",
        )
    }

    @Test
    fun p1ReportsMismatchesInNote() {
        val exec = executor(
            mapOf(
                "expired_badssl" to result("expired_badssl", 200, null),
                "refused" to result("refused", null, ProbeError.TIMEOUT),
            ),
        )
        val r = run(CheckId.P1, exec)
        assertThat(r.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(r.note).contains("refused: expected conn_refused, got timeout")
        assertThat(r.note).contains("expired_badssl: expected tls_error, got ok")
    }

    @Test
    fun p1ExpectsOkTargetToSucceed() {
        val exec = executor(mapOf("google_204" to result("google_204", null, ProbeError.DNS_FAIL)))
        val r = run(CheckId.P1, exec)
        assertThat(r.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(r.note).contains("google_204: expected ok, got dns_fail")
    }

    @Test
    fun p2ReportsPhasesOfTheSuccessfulTarget() {
        val r = run(CheckId.P2)
        assertThat(called.map { it.id }).containsExactly("google_204")
        assertThat(r.status).isEqualTo(CheckStatus.OK)
        assertThat(r.raw).containsExactly(
            "dnsMs", "5", "connectMs", "10", "tlsMs", "30", "ttfbMs", "80", "totalMs", "120",
        )
    }

    @Test
    fun p2NamesMissingPhases() {
        val phases = ProbePhases(dnsMs = 5, connectMs = null, tlsMs = null, ttfbMs = 80)
        val partial = result("google_204", 204, null, phases)
        val r = run(CheckId.P2, executor(mapOf("google_204" to partial)))
        assertThat(r.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(r.note).contains("missing phases: connect, tls")
    }

    @Test
    fun p2FailsWhenTargetProbeFailed() {
        val failed = result("google_204", null, ProbeError.NO_NETWORK)
        val r = run(CheckId.P2, executor(mapOf("google_204" to failed)))
        assertThat(r.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(r.note).contains("probe failed: no_network")
    }

    @Test
    fun p3IsOkWhenGatewayAcceptsConnection() {
        val r = run(CheckId.P3, gateway = gatewayResult(null))
        assertThat(r.status).isEqualTo(CheckStatus.OK)
        assertThat(r.raw).containsExactly("result", "connected", "totalMs", "4")
    }

    @Test
    fun p3RefusedStillProvesReachability() {
        val r = run(CheckId.P3, gateway = gatewayResult(ProbeError.CONN_REFUSED))
        assertThat(r.status).isEqualTo(CheckStatus.OK)
        assertThat(r.raw).containsExactly("result", "conn_refused", "totalMs", "4")
    }

    @Test
    fun p3LocalNetworkDeniedIsUnavailable() {
        val r = run(CheckId.P3, gateway = gatewayResult(ProbeError.LOCAL_NETWORK_DENIED))
        assertThat(r.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(r.note).contains("local_network_denied")
    }

    @Test
    fun p3TimeoutIsUnavailable() {
        val r = run(CheckId.P3, gateway = gatewayResult(ProbeError.TIMEOUT))
        assertThat(r.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(r.note).contains("timeout")
    }

    private companion object {
        const val TS = 777L
    }
}
