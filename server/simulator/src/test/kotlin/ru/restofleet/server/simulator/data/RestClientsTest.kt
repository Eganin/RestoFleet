package ru.restofleet.server.simulator.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import ru.restofleet.server.simulator.data.probe.HttpProbeRunner
import ru.restofleet.server.simulator.data.rest.OkHttpControlApi
import ru.restofleet.server.simulator.data.rest.OkHttpProvisioningApi
import ru.restofleet.server.simulator.data.rest.PollingDeviceConnector
import ru.restofleet.server.simulator.domain.model.CommandLoad
import ru.restofleet.server.simulator.domain.model.CommandReply
import ru.restofleet.server.simulator.domain.model.DeviceRole
import ru.restofleet.server.simulator.domain.model.LossMode
import ru.restofleet.server.simulator.domain.model.ProbeReport
import ru.restofleet.server.simulator.domain.model.ProbeResult
import ru.restofleet.server.simulator.domain.model.ProbeTarget
import ru.restofleet.server.simulator.domain.model.ReplyStatus
import ru.restofleet.server.simulator.domain.model.ReportedShadow
import ru.restofleet.server.simulator.domain.model.DeviceConfig
import ru.restofleet.server.simulator.domain.model.SimEvent
import ru.restofleet.server.simulator.domain.model.StatusInfo
import ru.restofleet.server.simulator.domain.model.TelemetrySample
import ru.restofleet.server.simulator.domain.port.ProvisionOutcome
import ru.restofleet.server.simulator.domain.runtime.RunMetrics
import ru.restofleet.server.simulator.support.creds
import ru.restofleet.shared.protocol.RfJson
import java.net.ServerSocket
import java.time.Duration
import kotlin.test.assertFailsWith

/** REST-клиенты симулятора по контракту K2: оператор (JWT), `/provision`, REST-polling устройства, пробы. */
class RestClientsTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(5)).build()
    private val metrics = RunMetrics()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun base() = server.url("/").toString().removeSuffix("/")

    private fun impatientClient() = client.newBuilder().callTimeout(Duration.ofMillis(300)).build()

    private fun respond(code: Int, body: String = "") =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun body(text: String) = RfJson.parseToJsonElement(text).jsonObject

    private fun loginOk(token: String = "t-1") = respond(200, """{"accessToken":"$token","refreshToken":"r"}""")

    @Test
    fun controlApiLogsInOnceAndCallsTheOperatorEndpoints() = runBlocking<Unit> {
        var passwordReads = 0
        val api = OkHttpControlApi(client, base(), "admin") { passwordReads++; "s3cr3t" }
        loginOk()
        respond(200, """{"items":[{"id":"r-1"},{"id":"r-2"},{"code":"x"}]}""")
        respond(201, """{"items":[{"id":"t","code":"AAAA-BBBB"},{"id":"u","code":"CCCC-DDDD"}]}""")
        respond(201, """{"cmdId":"c-7","status":"queued"}""")
        respond(200, """{"cmdId":"c-7","status":"succeeded"}""")
        respond(200, """{"device":{"id":"d-1","status":"offline"}}""")

        assertThat(api.restaurants()).containsExactly("r-1", "r-2")
        assertThat(api.issueCodes("r-1", DeviceRole.HALL, 2)).containsExactly("AAAA-BBBB", "CCCC-DDDD")
        assertThat(api.sendCommand("d-1", CommandLoad(60, ttlSec = 120))).isEqualTo("c-7")
        assertThat(api.commandStatus("c-7")).isEqualTo("succeeded")
        assertThat(api.deviceStatus("d-1")).isEqualTo("offline")
        assertThat(passwordReads).isEqualTo(1)

        val login = server.takeRequest()
        assertThat(login.url.encodedPath).isEqualTo("/api/v1/auth/login")
        assertThat(body(login.body!!.utf8())["login"]!!.jsonPrimitive.content).isEqualTo("admin")
        val restaurants = server.takeRequest()
        assertThat(restaurants.headers["Authorization"]).isEqualTo("Bearer t-1")
        assertThat(restaurants.url.queryParameter("limit")).isEqualTo("1000")
        val batch = body(server.takeRequest().body!!.utf8())
        assertThat(batch["count"]!!.jsonPrimitive.content).isEqualTo("2")
        assertThat(batch["role"]!!.jsonPrimitive.content).isEqualTo("hall")
        val command = server.takeRequest()
        assertThat(command.url.encodedPath).isEqualTo("/api/v1/devices/d-1/commands")
        assertThat(body(command.body!!.utf8())["ttlSec"]!!.jsonPrimitive.content).isEqualTo("120")
    }

    @Test
    fun expiredTokenLeadsToOneNewLoginAndFailuresAreNull() = runBlocking<Unit> {
        val api = OkHttpControlApi(client, base(), "admin") { "pw" }
        loginOk("old")
        respond(401, """{"code":"unauthorized"}""")
        loginOk("new")
        respond(404, """{"code":"not_found"}""")
        assertThat(api.deviceStatus("d-x")).isNull()
        server.takeRequest()
        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer old")
        server.takeRequest()
        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer new")

        respond(503)
        assertThat(api.sendCommand("d-1", CommandLoad(1))).isNull()
        respond(500)
        assertFailsWith<IllegalStateException> { api.restaurants() }
        val impatient = OkHttpControlApi(impatientClient(), base(), "admin") { "pw" }
        loginOk()
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        assertThat(impatient.commandStatus("c-1")).isNull()

        val noLogin = OkHttpControlApi(client, base(), "admin") { "pw" }
        respond(401)
        assertFailsWith<IllegalStateException> { noLogin.restaurants() }
    }

    @Test
    fun provisioningMapsOutcomesLikeTheAgent() = runBlocking<Unit> {
        val api = OkHttpProvisioningApi(client, base(), "simulator-1.0")
        respond(
            201,
            """{"deviceId":"d-1","password":"p","restaurantId":"r-1","role":"assembly",""" +
                """"mqtt":{"host":"10.0.2.2","port":8883}}""",
        )
        val registered = api.provision("ABCD") as ProvisionOutcome.Registered
        assertThat(registered.credentials.role).isEqualTo(DeviceRole.ASSEMBLY)
        val sent = body(server.takeRequest().body!!.utf8())
        assertThat(sent["code"]!!.jsonPrimitive.content).isEqualTo("ABCD")
        assertThat(sent["agentVersion"]!!.jsonPrimitive.content).isEqualTo("simulator-1.0")

        respond(429, """{"code":"rate_limited"}""")
        assertThat(api.provision("A")).isEqualTo(ProvisionOutcome.Retry("http_429"))
        respond(503, """{"code":"broker_unavailable"}""")
        assertThat(api.provision("A")).isEqualTo(ProvisionOutcome.Retry("http_503"))
        respond(409, """{"code":"token_used"}""")
        assertThat(api.provision("A")).isEqualTo(ProvisionOutcome.Rejected("token_used"))
        respond(410)
        assertThat(api.provision("A")).isEqualTo(ProvisionOutcome.Rejected("http_410"))
        respond(
            201, """{"deviceId":"d","password":"p","restaurantId":"r","role":"chef","mqtt":{"host":"h","port":1}}""",
        )
        assertThat(api.provision("A")).isEqualTo(ProvisionOutcome.Rejected("invalid_role"))
        respond(201, "{}")
        assertThat(api.provision("A")).isEqualTo(ProvisionOutcome.Rejected("invalid_response"))
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val impatient = OkHttpProvisioningApi(impatientClient(), base(), "s")
        assertThat(impatient.provision("A")).isInstanceOf(ProvisionOutcome.Retry::class.java)
    }

    @Test
    fun pollingDeviceBatchesEventsIntoTelemetryAndPollsCommands() = runBlocking<Unit> {
        val link = PollingDeviceConnector(client, base(), metrics) { 77 }.link(creds(1), victim = false)
        assertThat(link.pushesCommands).isFalse()
        assertThat(link.open(StatusInfo(DeviceRole.HALL, "s", "b"))).isTrue()
        assertThat(link.sendReported(ReportedShadow(DeviceConfig(), 1, 1, emptyList()))).isTrue()

        val event = SimEvent.Threshold(5, "battery_low", "enter", 19.0, 20.0)
        link.sendEvents(listOf(event))
        link.sendProbe(ProbeReport(5, listOf(ProbeResult("api", true, 200, null, 3))))
        respond(500)
        assertThat(link.sendTelemetry(sample(1))).isFalse()
        respond(204)
        assertThat(link.sendTelemetry(sample(2))).isTrue()
        server.takeRequest()
        val retried = server.takeRequest()
        assertThat(retried.url.encodedPath).isEqualTo("/api/v1/poll/telemetry")
        assertThat(retried.headers["Authorization"]).startsWith("Basic ")
        val payload = body(retried.body!!.utf8())
        assertThat(payload["events"].toString()).contains("battery_low")
        assertThat(payload["probe"].toString()).contains("api")

        respond(200, """{"commands":[{"cmdId":"c-1","type":"ping","params":{},"expiresAt":9}]}""")
        val commands = link.poll()!!
        assertThat(commands.single().cmdId).isEqualTo("c-1")
        assertThat(commands.single().receivedAtNanos).isEqualTo(77)
        assertThat(server.takeRequest().method).isEqualTo("GET")
        respond(401)
        assertThat(link.poll()).isNull()
        server.takeRequest()

        respond(204)
        assertThat(link.reply(CommandReply("c-1", ReplyStatus.SUCCEEDED, 9, mapOf("deviceTime" to "9")))).isTrue()
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/api/v1/poll/commands/result")
        link.vanish(LossMode.SILENT)
        link.release()
        link.close()
        val counters = metrics.snapshot()
        assertThat(counters.requests).isEqualTo(3)
        assertThat(counters.requestErrors).isEqualTo(2)
        assertThat(counters.payloadBytesOut).isGreaterThan(0)
    }

    @Test
    fun httpAndTcpProbesClassifyOutcomes() = runBlocking<Unit> {
        val probes = HttpProbeRunner(client) { System.nanoTime() }
        fun http(expected: Int? = null) =
            ProbeTarget("api", "http", server.url("/health").toString(), 30, 2_000, 3, expectedStatus = expected)

        respond(200)
        assertThat(probes.run(http()).let { it.ok to it.status }).isEqualTo(true to 200)
        respond(503)
        assertThat(probes.run(http()).let { it.ok to it.error }).isEqualTo(false to "http_5xx")
        respond(200)
        assertThat(probes.run(http(expected = 204)).error).isEqualTo("other")

        val closed = ServerSocket(0).also { it.close() }.localPort
        assertThat(probes.run(http().copy(url = "http://127.0.0.1:$closed/")).error).isEqualTo("conn_refused")
        assertThat(probes.run(http().copy(url = "not a url")).error).isEqualTo("other")

        val open = ServerSocket(0)
        val tcp = ProbeTarget("tcp", "tcp", "tcp://127.0.0.1:${open.localPort}", 30, 1_000, 3)
        assertThat(probes.run(tcp).ok).isTrue()
        open.close()
        assertThat(probes.run(tcp).error).isEqualTo("conn_refused")
        assertThat(probes.run(tcp.copy(url = "tcp://127.0.0.1")).error).isEqualTo("other")
    }

    private fun sample(seq: Long) = TelemetrySample(
        ts = seq, seq = seq, batteryLevel = 50, charging = false, plugged = "none", batteryTempC = 30.0,
        thermalStatus = "NONE", rssi = -60, freeMb = 1, availMb = 1, uptimeSec = 1, foregroundApp = null,
    )
}
