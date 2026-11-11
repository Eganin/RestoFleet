package ru.restofleet.agent.data.provisioning

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Before
import org.junit.Test
import ru.restofleet.agent.domain.error.ProvisionError
import ru.restofleet.agent.domain.model.DeviceRole
import ru.restofleet.agent.domain.model.ProvisionedDevice
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.dto.DeviceInfoDto
import ru.restofleet.shared.protocol.rest.ProvisionRequestDto
import java.time.Duration

/** Контракт K2 «Провижининг агента»: 201 → учётка; 401/409/410/429/503 → классы ошибок; TLS — пиннинг CA стенда. */
class OkHttpProvisioningApiTest {
    private val server = MockWebServer()
    private val device = DeviceInfoDto("sdk_gphone64_arm64", "Google", "16", 36)
    private val client = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(2))
        .readTimeout(Duration.ofSeconds(1))
        .retryOnConnectionFailure(false)
        .build()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun api(trust: ProvisioningTrust = ProvisioningTrust.System, base: OkHttpClient = client) =
        OkHttpProvisioningApi(base, trust, device, "0.2.0", Dispatchers.IO)

    private fun baseUrl() = server.url("/").toString().removeSuffix("/")

    private fun provision(api: OkHttpProvisioningApi = api(), url: String = baseUrl()) =
        runBlocking { api.provision(url, "ABCD-EFGH") }

    private fun respond(code: Int, body: String, vararg headers: Pair<String, String>) {
        val builder = MockResponse.Builder().code(code).body(body)
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        server.enqueue(builder.build())
    }

    private val okBody = """{"deviceId":"d-1f2e","password":"s3cr3t","restaurantId":"r-1","role":"kitchen",""" +
        """"mqtt":{"host":"10.0.2.2","port":8883},"extra":1}"""

    @Test
    fun createdResponseBecomesProvisionedDeviceAndRequestFollowsTheContract() {
        respond(201, okBody)

        val outcome = provision()

        assertThat(outcome).isEqualTo(
            Outcome.Success(
                ProvisionedDevice(DeviceId.parse("d-1f2e")!!, "s3cr3t", "r-1", DeviceRole.KITCHEN, "10.0.2.2", 8883),
            ),
        )
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/api/v1/provision")
        assertThat(request.headers["Content-Type"]).startsWith("application/json")
        val sent = RfJson.decodeFromString(ProvisionRequestDto.serializer(), request.body!!.utf8())
        assertThat(sent).isEqualTo(ProvisionRequestDto("ABCD-EFGH", device, "0.2.0"))
    }

    @Test
    fun serverErrorCodesMapToProvisionErrors() {
        val cases = listOf(
            Triple(401, """{"code":"token_invalid","message":"x"}""", ProvisionError.CodeInvalid),
            Triple(409, """{"code":"token_used","message":"x"}""", ProvisionError.CodeUsed),
            Triple(410, """{"code":"token_expired","message":"x"}""", ProvisionError.CodeExpired),
            Triple(503, """{"code":"broker_unavailable","message":"x"}""", ProvisionError.ServerUnavailable),
            Triple(502, "<html>Bad Gateway</html>", ProvisionError.ServerUnavailable),
            Triple(400, """{"code":"validation","field":"code"}""", ProvisionError.Rejected("validation")),
            Triple(404, "nope", ProvisionError.Rejected("http_404")),
        )
        cases.forEach { (status, body, expected) ->
            respond(status, body)
            assertWithMessage("$status $body").that(provision()).isEqualTo(Outcome.Failure(expected))
        }
    }

    @Test
    fun rateLimitCarriesRetryAfter() {
        respond(429, """{"code":"rate_limited","message":"x"}""", "Retry-After" to "37")
        respond(429, "slow down")

        assertThat(provision()).isEqualTo(Outcome.Failure(ProvisionError.RateLimited(37)))
        assertThat(provision()).isEqualTo(Outcome.Failure(ProvisionError.RateLimited(null)))
    }

    @Test
    fun malformedSuccessIsInvalidResponse() {
        val bodies = listOf(
            "not json",
            okBody.replace("d-1f2e", "a/b"),
            okBody.replace("kitchen", "barista"),
            okBody.replace("8883", "0"),
            okBody.replace("10.0.2.2", "tcp://x"),
            okBody.replace("s3cr3t", ""),
            """{"deviceId":"d-1"}""",
        )
        bodies.forEach { body ->
            respond(201, body)
            assertWithMessage(body).that(provision()).isEqualTo(Outcome.Failure(ProvisionError.InvalidResponse))
        }
    }

    @Test
    fun closedPortIsNoNetwork() {
        val url = baseUrl()
        server.close()

        assertThat(provision(url = url)).isEqualTo(Outcome.Failure(ProvisionError.NoNetwork))
    }

    @Test
    fun stalledServerIsTimeout() {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())

        assertThat(provision()).isEqualTo(Outcome.Failure(ProvisionError.Timeout))
    }

    @Test
    fun pinnedStandCaTrustsOnlyItsOwnCa() {
        val standCa = HeldCertificate.Builder().certificateAuthority(0).commonName("stand-ca").build()
        val otherCa = HeldCertificate.Builder().certificateAuthority(0).commonName("other-ca").build()
        val serverCert = HeldCertificate.Builder().addSubjectAlternativeName(server.hostName).signedBy(standCa).build()
        server.useHttps(
            HandshakeCertificates.Builder().heldCertificate(serverCert, standCa.certificate).build().sslSocketFactory(),
        )
        respond(201, okBody)

        val pinned = api(ProvisioningTrust.PinnedStandCa { standCa.certificatePem().toByteArray() })
        val foreign = api(ProvisioningTrust.PinnedStandCa { otherCa.certificatePem().toByteArray() })

        assertThat(provision(pinned)).isInstanceOf(Outcome.Success::class.java)
        assertThat(provision(foreign)).isEqualTo(Outcome.Failure(ProvisionError.Tls))
    }

    @Test
    fun pinnedTrustWithoutCaDoesNotSendTheRequest() {
        val outcome = provision(api(ProvisioningTrust.PinnedStandCa { null }))

        assertThat(outcome).isEqualTo(Outcome.Failure(ProvisionError.CaMissing))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun brokenCaPemIsCaMissing() {
        assertThat(provision(api(ProvisioningTrust.PinnedStandCa { "garbage".toByteArray() })))
            .isEqualTo(Outcome.Failure(ProvisionError.CaMissing))
    }

    @Test
    fun unparsableBaseUrlIsRejected() {
        assertThat(provision(url = "not a url")).isEqualTo(Outcome.Failure(ProvisionError.InvalidBaseUrl))
    }
}
