package ru.restofleet.agent.data.deviceadmin

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.restofleet.shared.kernel.Outcome
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

class ApkDownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val held = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val https = MockWebServer()
    private val http = MockWebServer()
    private val body = "APK-BODY-0123456789"

    private val trusting: OkHttpClient = HandshakeCertificates.Builder()
        .addTrustedCertificate(held.certificate).build()
        .let { OkHttpClient.Builder().sslSocketFactory(it.sslSocketFactory(), it.trustManager).build() }

    @Before
    fun setUp() {
        https.protocols = listOf(Protocol.HTTP_1_1) // chunked-кодирование есть только в HTTP/1.1
        https.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory())
        https.start()
        http.start()
    }

    @After
    fun tearDown() {
        https.close()
        http.close()
    }

    private fun downloader(
        maxBytes: Long = ApkDownloader.MAX_APK_BYTES,
        names: () -> String = { "n${counter.incrementAndGet()}" },
    ) =
        ApkDownloader(trusting, tmp.root, maxBytes, Dispatchers.IO, names)

    private val counter = AtomicInteger()

    private fun apkDir() = File(tmp.root, "apk")

    private fun filesLeft(): List<String> = apkDir().list()?.toList().orEmpty()

    private fun download(url: String, d: ApkDownloader = downloader()) = runBlocking { d.download(url) }

    private fun failureReason(outcome: Outcome<File, DeviceAdminError>): String? =
        ((outcome as? Outcome.Failure)?.error as? DeviceAdminError.DownloadFailed)?.reason

    @Test
    fun plainHttpIsRefusedWithoutAnyRequest() {
        http.enqueue(MockResponse.Builder().body(body).build())
        val result = download(http.url("/a.apk").toString())
        assertThat(failureReason(result)).isEqualTo("https_required")
        assertThat(http.requestCount).isEqualTo(0)
        assertThat(filesLeft()).isEmpty()
    }

    @Test
    fun unparsableUrlIsRefused() {
        assertThat(failureReason(download("not a url"))).isEqualTo("invalid_url")
    }

    @Test
    fun notFoundIsReportedWithStatusCode() {
        https.enqueue(MockResponse.Builder().code(404).body("nope").build())
        assertThat(failureReason(download(https.url("/a.apk").toString()))).isEqualTo("http_404")
        assertThat(filesLeft()).isEmpty()
    }

    @Test
    fun okResponseIsSavedUnderApkDirWithRandomName() {
        https.enqueue(MockResponse.Builder().body(body).build())
        val result = download(https.url("/secret/evil.apk").toString())
        val file = (result as Outcome.Success).value
        assertThat(file.readText()).isEqualTo(body)
        assertThat(file.parentFile).isEqualTo(apkDir())
        assertThat(file.name).isEqualTo("n1.apk")
        assertThat(filesLeft()).containsExactly("n1.apk")
    }

    @Test
    fun twoDownloadsGetDifferentFilesWithDefaultNames() {
        https.enqueue(MockResponse.Builder().body("one").build())
        https.enqueue(MockResponse.Builder().body("two").build())
        val d = ApkDownloader(trusting, tmp.root)
        val a = (runBlocking { d.download(https.url("/a").toString()) } as Outcome.Success).value
        val b = (runBlocking { d.download(https.url("/a").toString()) } as Outcome.Success).value
        assertThat(a).isNotEqualTo(b)
        assertThat(a.readText()).isEqualTo("one")
        assertThat(b.readText()).isEqualTo("two")
    }

    @Test
    fun declaredLengthOverLimitIsRefusedAndNothingIsKept() {
        https.enqueue(MockResponse.Builder().body("x".repeat(20)).build())
        val result = download(https.url("/a").toString(), downloader(maxBytes = 10))
        assertThat(failureReason(result)).isEqualTo("too_large")
        assertThat(filesLeft()).isEmpty()
    }

    @Test
    fun streamingOverLimitWithoutContentLengthIsRefusedAndNothingIsKept() {
        https.enqueue(MockResponse.Builder().chunkedBody("x".repeat(40), 5).build())
        val result = download(https.url("/a").toString(), downloader(maxBytes = 10))
        assertThat(failureReason(result)).isEqualTo("too_large")
        assertThat(filesLeft()).isEmpty()
    }

    @Test
    fun bodyExactlyAtLimitIsAccepted() {
        https.enqueue(MockResponse.Builder().chunkedBody("x".repeat(10), 3).build())
        val result = download(https.url("/a").toString(), downloader(maxBytes = 10))
        assertThat(result).isInstanceOf(Outcome.Success::class.java)
        assertThat((result as Outcome.Success).value.length()).isEqualTo(10)
    }

    @Test
    fun redirectToPlainHttpIsNotFollowedEvenWithPermissiveClient() {
        http.enqueue(MockResponse.Builder().body(body).build())
        https.enqueue(MockResponse.Builder().code(302).addHeader("Location", http.url("/a.apk").toString()).build())
        val permissive = trusting.newBuilder().followSslRedirects(true).followRedirects(true).build()
        val result = runBlocking { ApkDownloader(permissive, tmp.root).download(https.url("/a").toString()) }
        assertThat(failureReason(result)).isEqualTo("http_302")
        assertThat(http.requestCount).isEqualTo(0)
        assertThat(filesLeft()).isEmpty()
    }

    @Test
    fun redirectToHttpsIsFollowed() {
        https.enqueue(MockResponse.Builder().code(302).addHeader("Location", https.url("/real.apk").toString()).build())
        https.enqueue(MockResponse.Builder().body(body).build())
        val result = download(https.url("/a").toString())
        assertThat((result as Outcome.Success).value.readText()).isEqualTo(body)
    }

    @Test
    fun untrustedCertificateIsANetworkFailure() {
        https.enqueue(MockResponse.Builder().body(body).build())
        val result = runBlocking { ApkDownloader(OkHttpClient(), tmp.root).download(https.url("/a").toString()) }
        assertThat(failureReason(result)).startsWith("network:")
        assertThat(https.requestCount).isEqualTo(0)
        assertThat(filesLeft()).isEmpty()
    }

    @Test
    fun connectionDroppedMidBodyIsANetworkFailureAndLeavesNoFile() {
        https.enqueue(
            MockResponse.Builder().body("y".repeat(1000)).onResponseBody(SocketEffect.CloseSocket()).build(),
        )
        val reason = failureReason(download(https.url("/a").toString()))
        assertThat(reason).startsWith("network:")
        assertThat(filesLeft()).isEmpty()
    }

    @Test
    fun cancellationStopsTheCallDeletesTheFileAndIsNotSwallowed() = runBlocking {
        https.enqueue(MockResponse.Builder().body("z".repeat(1000)).onResponseBody(SocketEffect.Stall).build())
        var returned: Any? = null
        var thrown: Throwable? = null
        val job = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try {
                returned = downloader().download(https.url("/a").toString())
            } catch (e: Throwable) {
                thrown = e
                throw e
            }
        }
        withContext(Dispatchers.IO) { https.takeRequest(5, TimeUnit.SECONDS) }
        withContext(Dispatchers.IO) { Thread.sleep(200) } // тело начало записываться в .part
        withTimeout(5_000) { job.cancelAndJoin() }
        assertThat(job.isCancelled).isTrue()
        assertThat(returned).isNull() // отмена не превращена в Failure
        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(filesLeft()).isEmpty()
    }
}
