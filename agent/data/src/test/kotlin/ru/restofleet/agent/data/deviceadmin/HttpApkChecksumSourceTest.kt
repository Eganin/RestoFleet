package ru.restofleet.agent.data.deviceadmin

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Before
import org.junit.Test
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class HttpApkChecksumSourceTest {
    private val held = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val https = MockWebServer()
    private val http = MockWebServer()
    private val hash = "0123456789abcdef".repeat(4)

    private val trusting: OkHttpClient = HandshakeCertificates.Builder()
        .addTrustedCertificate(held.certificate).build()
        .let { OkHttpClient.Builder().sslSocketFactory(it.sslSocketFactory(), it.trustManager).build() }

    @Before
    fun setUp() {
        https.protocols = listOf(Protocol.HTTP_1_1)
        https.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory())
        https.start()
        http.start()
    }

    @After
    fun tearDown() {
        https.close()
        http.close()
    }

    private fun fetch(url: String): Outcome<String, DomainError> =
        runBlocking { HttpApkChecksumSource(trusting).fetch(url) }

    private fun code(outcome: Outcome<String, DomainError>) = (outcome as Outcome.Failure).error.code

    private fun url() = https.url("/waiter-4.13.apk.sha256").toString()

    @Test
    fun sha256sumFormatYieldsTheFirstToken() {
        https.enqueue(MockResponse.Builder().body("$hash  waiter-4.13.apk\n").build())

        assertThat(fetch(url())).isEqualTo(Outcome.Success(hash))
        assertThat(https.takeRequest().url.encodedPath).isEqualTo("/waiter-4.13.apk.sha256")
    }

    @Test
    fun bareHashWithoutNewlineAndUpperCaseIsNormalizedToLowerCase() {
        https.enqueue(MockResponse.Builder().body(hash.uppercase()).build())

        assertThat(fetch(url())).isEqualTo(Outcome.Success(hash))
    }

    @Test
    fun leadingWhitespaceIsSkipped() {
        https.enqueue(MockResponse.Builder().body("\n  $hash\n").build())

        assertThat(fetch(url())).isEqualTo(Outcome.Success(hash))
    }

    @Test
    fun hashLongerOrShorterThan64OrNotHexOrEmptyIsInvalid() {
        listOf(hash.dropLast(1), hash + "a", "g" + hash.drop(1), "", "   \n", "$hash-x  f.apk").forEach { body ->
            https.enqueue(MockResponse.Builder().body(body).build())
            assertThat(code(fetch(url()))).isEqualTo("checksum_invalid")
        }
    }

    @Test
    fun bodyOverOneKibIsRefusedByDeclaredLength() {
        https.enqueue(MockResponse.Builder().body(hash + " ".repeat(1_100)).build())

        assertThat(code(fetch(url()))).isEqualTo("checksum_too_large")
    }

    @Test
    fun streamedBodyOverOneKibWithoutLengthIsRefused() {
        https.enqueue(MockResponse.Builder().chunkedBody(hash + " ".repeat(1_100), 100).build())

        assertThat(code(fetch(url()))).isEqualTo("checksum_too_large")
    }

    @Test
    fun bodyOfExactlyOneKibIsAccepted() {
        https.enqueue(MockResponse.Builder().chunkedBody(hash + " ".repeat(1_024 - 64), 100).build())

        assertThat(fetch(url())).isEqualTo(Outcome.Success(hash))
    }

    @Test
    fun httpErrorStatusIsReportedWithCode() {
        https.enqueue(MockResponse.Builder().code(404).body("nope").build())

        assertThat(code(fetch(url()))).isEqualTo("http_404")
    }

    @Test
    fun plainHttpAndGarbageAreRefusedWithoutAnyRequest() {
        http.enqueue(MockResponse.Builder().body(hash).build())

        assertThat(code(fetch(http.url("/a.apk.sha256").toString()))).isEqualTo("https_required")
        assertThat(code(fetch("not a url"))).isEqualTo("https_required")
        assertThat(http.requestCount).isEqualTo(0)
    }

    @Test
    fun redirectToPlainHttpIsNotFollowed() {
        https.enqueue(
            MockResponse.Builder().code(302).addHeader("Location", http.url("/a.sha256").toString()).build(),
        )
        http.enqueue(MockResponse.Builder().body(hash).build())

        assertThat(code(fetch(url()))).isEqualTo("http_302")
        assertThat(http.requestCount).isEqualTo(0)
    }

    @Test
    fun redirectToHttpsIsFollowed() {
        https.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/other.sha256").build())
        https.enqueue(MockResponse.Builder().body(hash).build())

        assertThat(fetch(url())).isEqualTo(Outcome.Success(hash))
    }

    @Test
    fun connectionFailureIsReportedWithExceptionClassOnly() {
        https.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())

        assertThat(code(fetch(url()))).startsWith("network:")
    }

    @Test
    fun cancellationCancelsTheCallAndIsNotSwallowed() {
        https.enqueue(MockResponse.Builder().body(hash).bodyDelay(30, TimeUnit.SECONDS).build())
        val started = System.nanoTime()

        runBlocking {
            val job = async(start = CoroutineStart.UNDISPATCHED) { HttpApkChecksumSource(trusting).fetch(url()) }
            withTimeout(5_000) { while (https.requestCount == 0) kotlinx.coroutines.yield() }
            job.cancelAndJoin()
            org.junit.Assert.assertThrows(CancellationException::class.java) { job.getCompleted() }
        }

        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).isLessThan(20)
    }
}
