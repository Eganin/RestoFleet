package ru.restofleet.server.api

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import ru.restofleet.server.domain.command.CommandRequest
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.device.DeviceInfo
import ru.restofleet.server.domain.device.DeviceRole
import ru.restofleet.server.domain.provisioning.BrokerEndpoint
import ru.restofleet.server.domain.provisioning.IssueTokensRequest
import ru.restofleet.server.domain.provisioning.IssuedToken
import ru.restofleet.server.domain.provisioning.ProvisionRequest
import ru.restofleet.server.domain.provisioning.ProvisionedDevice
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.server.domain.shadow.UpdatedShadow
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.rest.ProvisionResponseDto
import ru.restofleet.shared.shadow.ShadowDocument
import ru.restofleet.shared.shadow.ShadowValue
import java.io.File

/** Тень, команды и провижининг: тела, коды ошибок домена, секреты в ответах. */
class ShadowCommandProvisioningRoutesTest {
    private val kit = ApiTestKit()
    private val token by lazy { kit.token() }
    private val id = Samples.deviceId
    private val restaurantId = RestaurantId(Samples.RESTAURANT_ID)

    private fun fixture(name: String) = File(System.getProperty("fixturesDir"), "$name.json").readText()

    @Test
    fun `shadow shows desired reported and the delta with integers without fraction`() = kit.run {
        coEvery { kit.shadows.get(id) } returns Outcome.Success(Samples.shadow)

        val body = client.get("/api/v1/devices/d-1/shadow") { bearer(token) }.json()

        assertThat(body["desiredVersion"]!!.jsonPrimitive.content).isEqualTo("2")
        assertThat(body["desired"]!!.jsonObject["telemetry"].toString()).isEqualTo("""{"intervalSec":30}""")
        assertThat(body["delta"]!!.jsonObject.keys).containsExactly("thresholds", "watchedApps", "probes")
        assertThat(body["pending"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(body).doesNotContainKey("published")
    }

    @Test
    fun `put sends the body as a merge patch and reports publication`() = kit.run {
        val patch = slot<ShadowDocument>()
        coEvery { kit.shadows.update(id, capture(patch)) } returns
            Outcome.Success(UpdatedShadow(Samples.shadow.copy(desiredVersion = 3), published = false))

        val response = client.put("/api/v1/devices/d-1/shadow") {
            bearer(token)
            json("""{"telemetry":{"intervalSec":60}}""")
        }

        assertThat(patch.captured).isEqualTo(
            ShadowDocument(mapOf("telemetry" to ShadowValue.Obj(mapOf("intervalSec" to ShadowValue.Num(60))))),
        )
        assertThat(response.json()["desiredVersion"]!!.jsonPrimitive.content).isEqualTo("3")
        assertThat(response.json()["published"]!!.jsonPrimitive.content).isEqualTo("false")
    }

    @Test
    fun `shadow errors are 400 with the path of the field, 410 for a decommissioned device`() = kit.run {
        coEvery { kit.shadows.update(id, any()) } returns
            Outcome.Failure(ServerError.Validation("telemetry.intervalSec", "out_of_range")) andThen
            Outcome.Failure(ServerError.DeviceDecommissioned("d-1"))

        val invalid = client.put("/api/v1/devices/d-1/shadow") {
            bearer(token)
            json("""{"telemetry":{"intervalSec":1}}""")
        }
        val gone = client.put("/api/v1/devices/d-1/shadow") { bearer(token); json("""{}""") }
        val notObject = client.put("/api/v1/devices/d-1/shadow") { bearer(token); json("[1]") }
        val nul = client.put("/api/v1/devices/d-1/shadow") { bearer(token); json("""{"x":"\u0000"}""") }

        assertThat(invalid.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(invalid.json()["field"]!!.jsonPrimitive.content).isEqualTo("telemetry.intervalSec")
        assertThat(gone.status).isEqualTo(HttpStatusCode.Gone)
        assertThat(gone.errorCode()).isEqualTo("device_decommissioned")
        assertThat(notObject.json()["reason"]!!.jsonPrimitive.content).isEqualTo("not_an_object")
        assertThat(nul.json()["reason"]!!.jsonPrimitive.content).isEqualTo("nul_char")
        coVerify(exactly = 2) { kit.shadows.update(id, any()) }
    }

    @Test
    fun `command is created with defaults and a location, then read by id`() = kit.run {
        val request = slot<CommandRequest>()
        coEvery { kit.commands.send(capture(request)) } returns Outcome.Success(Samples.command)
        coEvery { kit.commands.get("c-1") } returns Outcome.Success(Samples.command)
        coEvery { kit.commands.get("c-404") } returns Outcome.Failure(ServerError.NotFound("command", "c-404"))

        val created = client.post("/api/v1/devices/d-1/commands") { bearer(token); json("""{"type":"ping"}""") }
        val read = client.get("/api/v1/commands/c-1") { bearer(token) }
        val missing = client.get("/api/v1/commands/c-404") { bearer(token) }

        assertThat(created.status).isEqualTo(HttpStatusCode.Created)
        assertThat(created.headers[HttpHeaders.Location]).isEqualTo("/api/v1/commands/c-1")
        assertThat(request.captured).isEqualTo(CommandRequest(id, "ping", ShadowDocument.EMPTY, 300))
        assertThat(read.json()["status"]!!.jsonPrimitive.content).isEqualTo("queued")
        assertThat(missing.status).isEqualTo(HttpStatusCode.NotFound)
    }

    @Test
    fun `command params and ttl reach the use case, broker failure is 503, bad body 400`() = kit.run {
        val request = slot<CommandRequest>()
        coEvery { kit.commands.send(capture(request)) } returns
            Outcome.Failure(ServerError.BrokerUnavailable("publish"))

        val down = client.post("/api/v1/devices/d-1/commands") {
            bearer(token)
            json("""{"type":"launch_app","params":{"pkg":"com.android.chrome"},"ttlSec":60}""")
        }
        val malformed = client.post("/api/v1/devices/d-1/commands") { bearer(token); json("""{"params":{}}""") }

        assertThat(request.captured.params["pkg"]).isEqualTo(ShadowValue.Str("com.android.chrome"))
        assertThat(request.captured.ttlSec).isEqualTo(60)
        assertThat(down.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
        assertThat(malformed.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(malformed.errorCode()).isEqualTo("bad_request")
    }

    @Test
    fun `codes are issued one or many, never cached, roles are checked`() = kit.run {
        val token1 = IssuedToken("t-1", "CODE-ONE", restaurantId, DeviceRole.HALL, "Зал-1", 99_000)
        coEvery { kit.provisioning.issue(IssueTokensRequest(restaurantId, DeviceRole.HALL, "Зал-1", 1)) } returns
            Outcome.Success(listOf(token1))
        coEvery { kit.provisioning.issue(IssueTokensRequest(restaurantId, DeviceRole.KITCHEN, null, 2)) } returns
            Outcome.Success(listOf(token1, token1.copy(id = "t-2")))
        coEvery { kit.provisioning.issue(match { it.count == 0 }) } returns
            Outcome.Failure(ServerError.Validation("count", "out_of_range"))

        val one = client.post("/api/v1/provisioning/tokens") {
            bearer(token)
            json("""{"restaurantId":"${Samples.RESTAURANT_ID}","role":"hall","name":"Зал-1"}""")
        }
        val batch = client.post("/api/v1/provisioning/tokens:batch") {
            bearer(token)
            json("""{"restaurantId":"${Samples.RESTAURANT_ID}","role":"kitchen","count":2}""")
        }
        val zero = client.post("/api/v1/provisioning/tokens:batch") {
            bearer(token)
            json("""{"restaurantId":"${Samples.RESTAURANT_ID}","role":"kitchen","count":0}""")
        }
        val badRole = client.post("/api/v1/provisioning/tokens") {
            bearer(token)
            json("""{"restaurantId":"${Samples.RESTAURANT_ID}","role":"chef"}""")
        }

        assertThat(one.status).isEqualTo(HttpStatusCode.Created)
        assertThat(one.headers[HttpHeaders.CacheControl]).isEqualTo("no-store")
        assertThat(one.json()["code"]!!.jsonPrimitive.content).isEqualTo("CODE-ONE")
        assertThat(batch.json()["items"]!!.jsonArray).hasSize(2)
        assertThat(batch.headers[HttpHeaders.CacheControl]).isEqualTo("no-store")
        assertThat(zero.json()["field"]!!.jsonPrimitive.content).isEqualTo("count")
        assertThat(badRole.json()["field"]!!.jsonPrimitive.content).isEqualTo("role")
    }

    @Test
    fun `provision exchanges the agent fixture for the protocol response without caching`() = kit.run {
        val request = slot<ProvisionRequest>()
        coEvery { kit.provisioning.provision(capture(request)) } returns Outcome.Success(
            ProvisionedDevice(
                id,
                "device-password-xyz",
                restaurantId,
                DeviceRole.KITCHEN,
                BrokerEndpoint("10.0.2.2", 8883),
            ),
        )

        val response = client.post("/api/v1/provision") { json(fixture("rest-provision-request")) }

        assertThat(response.status).isEqualTo(HttpStatusCode.Created)
        assertThat(response.headers[HttpHeaders.CacheControl]).isEqualTo("no-store")
        val dto = RfJson.decodeFromString(ProvisionResponseDto.serializer(), response.json().toString())
        assertThat(dto.deviceId).isEqualTo("d-1")
        assertThat(dto.password).isEqualTo("device-password-xyz")
        assertThat(dto.role).isEqualTo("kitchen")
        assertThat(dto.mqtt.port).isEqualTo(8883)
        assertThat(request.captured.code).isEqualTo("RFP-7K3M-9QXA-2WBD")
        assertThat(request.captured.deviceInfo).isEqualTo(DeviceInfo("sdk_gphone64_arm64", "Google", "16", 36))
    }

    @Test
    fun `provision code errors are 401 409 410 and bad device info 400`() = kit.run {
        coEvery { kit.provisioning.provision(match { it.code == "invalid" }) } returns
            Outcome.Failure(ServerError.TokenInvalid)
        coEvery { kit.provisioning.provision(match { it.code == "used" }) } returns
            Outcome.Failure(ServerError.TokenUsed)
        coEvery { kit.provisioning.provision(match { it.code == "expired" }) } returns
            Outcome.Failure(ServerError.TokenExpired)
        val body = { code: String, api: Int ->
            """{"code":"$code","deviceInfo":{"model":"m","manufacturer":"g","osVersion":"16","apiLevel":$api},""" +
                """"agentVersion":"1.0.0"}"""
        }

        val statuses = listOf("invalid", "used", "expired").map { code ->
            client.post("/api/v1/provision") { json(body(code, 36)) }.let { it.status to it.errorCode() }
        }
        val badApi = client.post("/api/v1/provision") { json(body("x", 0)) }

        assertThat(statuses).containsExactly(
            HttpStatusCode.Unauthorized to "token_invalid",
            HttpStatusCode.Conflict to "token_used",
            HttpStatusCode.Gone to "token_expired",
        ).inOrder()
        assertThat(badApi.json()["field"]!!.jsonPrimitive.content).isEqualTo("deviceInfo.apiLevel")
    }

    @Test
    fun `provision is rate limited`() = ApiTestKit(RateLimitSettings(provisionPerPeriod = 1)).let { limited ->
        limited.run {
            coEvery { limited.provisioning.provision(any()) } returns Outcome.Failure(ServerError.TokenInvalid)

            val first = client.post("/api/v1/provision") { json(fixture("rest-provision-request")) }
            val second = client.post("/api/v1/provision") { json(fixture("rest-provision-request")) }

            assertThat(first.status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(second.status).isEqualTo(HttpStatusCode.TooManyRequests)
            assertThat(second.errorCode()).isEqualTo("rate_limited")
        }
    }
}
