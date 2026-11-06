package ru.restofleet.agent.data.probes

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException
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
            "https://127.0.0.1:9/" to 5_000L,
            "https://expired.badssl.com/" to 5_000L,
            "https://httpbin.org/status/503" to 5_000L,
        ).inOrder()
    }

    @Test
    fun p1IsOkWhenEveryClassIsDeterminedCorrectly() {
        val r = run(CheckId.P1)
        assertThat(r.status).isEqualTo(CheckStatus.OK)
        assertThat(r.note as Any?).isNull()
        assertThat(r.checkedAt).isEqualTo(TS)
        assertThat(r.raw).containsExactly(
            "google_204", "expected=ok actual=ok httpStatus=204 totalMs=120",
            "nonexistent", "expected=dns_fail actual=dns_fail httpStatus=- totalMs=120",
            "blackhole", "expected=timeout actual=timeout httpStatus=- totalMs=120",
            "refused", "expected=conn_refused actual=conn_refused httpStatus=- totalMs=120",
            "expired_badssl", "expected=tls_error actual=tls_error httpStatus=- totalMs=120",
            "httpbin_503", "expected=http_5xx actual=http_5xx httpStatus=503 totalMs=120",
        )
    }

    @Test
    fun p1MismatchIsLimitedWithFullRawAndNote() {
        val exec = executor(
            mapOf(
                "expired_badssl" to result("expired_badssl", 200, null),
                "refused" to result("refused", null, ProbeError.TIMEOUT),
            ),
        )
        val r = run(CheckId.P1, exec)
        assertThat(r.status).isEqualTo(CheckStatus.LIMITED)
        assertThat(r.note).contains("refused: expected conn_refused, got timeout")
        assertThat(r.note).contains("expired_badssl: expected tls_error, got ok")
        assertThat(r.raw).hasSize(6)
        assertThat(r.raw["refused"]).isEqualTo("expected=conn_refused actual=timeout httpStatus=- totalMs=120")
        assertThat(r.raw["expired_badssl"]).isEqualTo("expected=tls_error actual=ok httpStatus=200 totalMs=120")
        assertThat(r.raw["google_204"]).isEqualTo("expected=ok actual=ok httpStatus=204 totalMs=120")
    }

    @Test
    fun p1ExpectsOkTargetToSucceed() {
        val exec = executor(mapOf("google_204" to result("google_204", null, ProbeError.DNS_FAIL)))
        val r = run(CheckId.P1, exec)
        assertThat(r.status).isEqualTo(CheckStatus.LIMITED)
        assertThat(r.note).contains("google_204: expected ok, got dns_fail")
    }

    @Test
    fun p1ExecutorFailureIsUnavailableNotThrown() {
        val exec = ProbeExecutor { throw IllegalStateException("boom") }
        val r = run(CheckId.P1, exec)
        assertThat(r.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(r.note).contains("boom")
    }

    @Test(expected = CancellationException::class)
    fun p1PropagatesCancellation() {
        run(CheckId.P1, ProbeExecutor { throw CancellationException("cancelled") })
    }

    @Test
    fun p1ProbesTargetsConcurrentlyAndKeepsOrder() = runTest {
        // Чем раньше в списке цель, тем дольше она отвечает: порядок завершения обратный порядку целей.
        val delays = ProbeChecksFactory.P1_CASES.mapIndexed { i, c -> c.target.id to (6 - i) * 1_000L }.toMap()
        val ideal = executor()
        val slow = ProbeExecutor { target ->
            delay(delays.getValue(target.id))
            ideal.probe(target)
        }
        val p1 = ProbeChecksFactory(Clock { TS }, slow) { gatewayResult(null) }.create().first { it.id == CheckId.P1 }
        val r = p1.run()
        assertThat(testScheduler.currentTime).isEqualTo(6_000L)
        assertThat(r.status).isEqualTo(CheckStatus.OK)
        assertThat(r.raw.keys).containsExactly(
            "google_204", "nonexistent", "blackhole", "refused", "expired_badssl", "httpbin_503",
        ).inOrder()
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
    fun p3UnreachableClassesAreNotReachable() {
        listOf(ProbeError.OTHER, ProbeError.NO_NETWORK, ProbeError.DNS_FAIL).forEach {
            val r = run(CheckId.P3, gateway = gatewayResult(it))
            assertThat(r.status).isEqualTo(CheckStatus.UNAVAILABLE)
            assertThat(r.note).contains(it.name.lowercase())
        }
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
