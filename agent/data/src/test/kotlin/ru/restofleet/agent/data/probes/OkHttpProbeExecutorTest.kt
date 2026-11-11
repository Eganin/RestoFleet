package ru.restofleet.agent.data.probes

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Before
import org.junit.Test
import ru.restofleet.agent.domain.model.ProbeError
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.agent.domain.model.ProbeTarget
import ru.restofleet.shared.kernel.Clock
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class OkHttpProbeExecutorTest {
    private val server = MockWebServer()
    private var network = true

    /** Имя `probe.test` резолвится в loopback через Dns клиента — так DNS-фаза измеряется и для MockWebServer. */
    private val loopbackDns = Dns { host ->
        if (host == HOST) listOf(InetAddress.getLoopbackAddress()) else throw UnknownHostException(host)
    }
    private val baseClient = OkHttpClient.Builder().dns(loopbackDns).build()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        httpsServer.close()
    }

    private fun executor(client: OkHttpClient = baseClient, nano: () -> Long = System::nanoTime) =
        OkHttpProbeExecutor(client, Clock { TS }, { network }, nano)

    private fun probe(url: String, timeoutMs: Long = 2_000, client: OkHttpClient = baseClient): ProbeResult =
        runBlocking { executor(client).probe(ProbeTarget("t", url, timeoutMs)) }

    private fun httpUrl() = "http://$HOST:${server.port}/"

    @Test
    fun successfulPlainHttpHasAllPhasesExceptTls() {
        server.enqueue(MockResponse.Builder().code(200).build())
        val r = probe(httpUrl())
        assertThat(r.targetId).isEqualTo("t")
        assertThat(r.ok).isTrue()
        assertThat(r.httpStatus).isEqualTo(200)
        assertThat(r.error as Any?).isNull()
        assertThat(r.ts).isEqualTo(TS)
        assertThat(r.phases.dnsMs as Any?).isNotNull()
        assertThat(r.phases.connectMs as Any?).isNotNull()
        assertThat(r.phases.ttfbMs as Any?).isNotNull()
        assertThat(r.phases.tlsMs as Any?).isNull()
        assertThat(r.totalMs).isAtLeast(r.phases.ttfbMs!!)
    }

    @Test
    fun statusBelow400IsOkAndRedirectsAreNotFollowed() {
        server.enqueue(MockResponse.Builder().code(204).build())
        val r204 = probe(httpUrl())
        assertThat(r204.ok).isTrue()
        assertThat(r204.httpStatus).isEqualTo(204)

        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://$HOST:1/never").build())
        val r302 = probe(httpUrl())
        assertThat(r302.ok).isTrue()
        assertThat(r302.httpStatus).isEqualTo(302)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun everyProbeOpensAFreshConnection() {
        repeat(2) { server.enqueue(MockResponse.Builder().code(200).build()) }
        val pooledClient = baseClient.newBuilder().connectionPool(okhttp3.ConnectionPool()).build()
        val second = run {
            probe(httpUrl(), client = pooledClient)
            probe(httpUrl(), client = pooledClient)
        }
        assertThat(second.phases.connectMs as Any?).isNotNull()
        assertThat(second.phases.dnsMs as Any?).isNotNull()
    }

    @Test
    fun http503IsHttp5xx() {
        server.enqueue(MockResponse.Builder().code(503).build())
        val r = probe(httpUrl())
        assertThat(r.ok).isFalse()
        assertThat(r.httpStatus).isEqualTo(503)
        assertThat(r.error).isEqualTo(ProbeError.HTTP_5XX)
    }

    @Test
    fun http404IsHttp4xx() {
        server.enqueue(MockResponse.Builder().code(404).build())
        val r = probe(httpUrl())
        assertThat(r.ok).isFalse()
        assertThat(r.error).isEqualTo(ProbeError.HTTP_4XX)
    }

    @Test
    fun expectedStatusRequiresExactMatch() {
        // FR-AG-30, реш. 219: ожидается 204, а пришёл 200 — неуспех класса `other`; совпадение — успех
        server.enqueue(MockResponse.Builder().code(200).build())
        server.enqueue(MockResponse.Builder().code(204).build())
        val target = ProbeTarget("t", httpUrl(), 2_000, expectedStatus = 204)

        val (mismatch, match) = runBlocking { listOf(executor().probe(target), executor().probe(target)) }

        assertThat(mismatch.ok).isFalse()
        assertThat(mismatch.httpStatus).isEqualTo(200)
        assertThat(mismatch.error).isEqualTo(ProbeError.OTHER)
        assertThat(match.ok).isTrue()
        assertThat(match.error).isNull()
    }

    @Test
    fun serverErrorsAreNotRetried() {
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().code(200).build())
        probe(httpUrl())
        assertThat(server.requestCount).isEqualTo(1)
    }

    private val held: HeldCertificate = HeldCertificate.Builder().addSubjectAlternativeName(HOST).build()

    private val httpsServer = MockWebServer()

    private fun startHttps() {
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(held).build()
        httpsServer.useHttps(serverCerts.sslSocketFactory())
        httpsServer.start()
        httpsServer.enqueue(MockResponse.Builder().code(200).build())
    }

    @Test
    fun trustedHttpsHasTlsPhase() {
        startHttps()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        val client = baseClient.newBuilder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            .build()
        val r = probe("https://$HOST:${httpsServer.port}/", client = client)
        assertThat(r.ok).isTrue()
        assertThat(r.phases.tlsMs as Any?).isNotNull()
        assertThat(r.phases.connectMs as Any?).isNotNull()
    }

    @Test
    fun untrustedHttpsIsTlsError() {
        startHttps()
        val r = probe("https://$HOST:${httpsServer.port}/")
        assertThat(r.ok).isFalse()
        assertThat(r.httpStatus as Any?).isNull()
        assertThat(r.error).isEqualTo(ProbeError.TLS_ERROR)
        // TCP-соединение прошло, рукопожатие нет: connect есть, tls нет.
        assertThat(r.phases.connectMs as Any?).isNotNull()
        assertThat(r.phases.tlsMs as Any?).isNull()
    }

    @Test
    fun silentServerIsTimeoutWithinBounds() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val r = probe(httpUrl(), timeoutMs = 300)
        assertThat(r.ok).isFalse()
        assertThat(r.error).isEqualTo(ProbeError.TIMEOUT)
        assertThat(r.totalMs).isAtLeast(300L)
        assertThat(r.totalMs).isAtMost(1_500L)
    }

    @Test
    fun closedPortIsConnRefused() {
        val url = httpUrl()
        server.close()
        val r = probe(url)
        assertThat(r.ok).isFalse()
        assertThat(r.error).isEqualTo(ProbeError.CONN_REFUSED)
        assertThat(r.phases.dnsMs as Any?).isNotNull()
        assertThat(r.phases.connectMs as Any?).isNull()
    }

    @Test
    fun failingDnsIsDnsFail() {
        val r = probe("http://nonexistent.invalid/")
        assertThat(r.ok).isFalse()
        assertThat(r.error).isEqualTo(ProbeError.DNS_FAIL)
        assertThat(r.phases.dnsMs as Any?).isNull()
    }

    @Test
    fun failureWithoutNetworkIsNoNetwork() {
        network = false
        val r = probe("http://nonexistent.invalid/")
        assertThat(r.error).isEqualTo(ProbeError.NO_NETWORK)
    }

    @Test
    fun throwingNetworkAvailabilityDoesNotBreakTheProbe() {
        val executor = OkHttpProbeExecutor(baseClient, Clock { TS }, { throw SecurityException("no permission") })
        val r = runBlocking { executor.probe(ProbeTarget("t", "http://nonexistent.invalid/", 2_000)) }
        assertThat(r.ok).isFalse()
        assertThat(r.error).isEqualTo(ProbeError.DNS_FAIL)
    }

    @Test
    fun productionClientForbidsCleartext() {
        server.enqueue(MockResponse.Builder().code(200).build())
        val client = productionProbeClient().newBuilder().dns(loopbackDns).build()
        assertThat(client.connectionSpecs).containsExactly(okhttp3.ConnectionSpec.MODERN_TLS)
        val r = probe(httpUrl(), client = client)
        assertThat(r.ok).isFalse()
        assertThat(r.error).isEqualTo(ProbeError.OTHER)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun malformedUrlIsOtherAndNeverThrows() {
        val r = probe("not a url")
        assertThat(r.ok).isFalse()
        assertThat(r.error).isEqualTo(ProbeError.OTHER)
    }

    @Test
    fun totalMsComesFromMonotonicSource() {
        server.enqueue(MockResponse.Builder().code(200).build())
        val ticks = AtomicLong()
        val exec = executor(nano = { ticks.addAndGet(250_000_000L) })
        val r = runBlocking { exec.probe(ProbeTarget("t", httpUrl(), 2_000)) }
        assertThat(r.totalMs % 250).isEqualTo(0L)
        assertThat(r.totalMs).isGreaterThan(0L)
    }

    @Test
    fun cancellingTheCoroutineCancelsTheCall() = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val job = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            executor().probe(ProbeTarget("t", httpUrl(), 30_000))
        }
        withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
        job.cancel()
        runCatching { job.await() }
        val deadline = System.nanoTime() + 3_000_000_000L
        while (baseClient.dispatcher.runningCallsCount() > 0 && System.nanoTime() < deadline) {
            withContext(Dispatchers.IO) { Thread.sleep(20) }
        }
        assertThat(baseClient.dispatcher.runningCallsCount()).isEqualTo(0)
    }

    private companion object {
        const val HOST = "probe.test"
        const val TS = 1_700_000_000_000L
    }
}
