package ru.restofleet.server.api

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TimeRange
import ru.restofleet.server.domain.device.DecommissionResult
import ru.restofleet.server.domain.device.DeviceDetails
import ru.restofleet.server.domain.device.DeviceFilter
import ru.restofleet.server.domain.device.DeviceRole
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.ingest.DeviceEvent
import ru.restofleet.server.domain.ingest.ProbeRecord
import ru.restofleet.server.domain.ingest.ProbeResult
import ru.restofleet.server.domain.presence.DeviceSession
import ru.restofleet.server.domain.restaurant.RestaurantDraft
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.server.domain.restaurant.RestaurantPatch
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome

/** Рестораны и устройства: коды ответов, маппинг тел и параметров запроса в аргументы use cases. */
class RegistryRoutesTest {
    private val kit = ApiTestKit()
    private val token by lazy { kit.token() }
    private val id = Samples.deviceId

    @Test
    fun `restaurants are listed, created with location, read and patched`() = kit.run {
        val draft = slot<RestaurantDraft>()
        coEvery { kit.restaurants.list() } returns listOf(Samples.restaurant)
        coEvery { kit.restaurants.create(capture(draft)) } returns Outcome.Success(Samples.restaurant)
        coEvery { kit.restaurants.get(RestaurantId(Samples.RESTAURANT_ID)) } returns Outcome.Success(Samples.restaurant)
        coEvery { kit.restaurants.update(RestaurantId(Samples.RESTAURANT_ID), RestaurantPatch(name = "Новое")) } returns
            Outcome.Success(Samples.restaurant.copy(name = "Новое"))

        val list = client.get("/api/v1/restaurants") { bearer(token) }
        val created = client.post("/api/v1/restaurants") {
            bearer(token)
            json("""{"code":"MSK-01","name":"Кафе","city":"Москва","timezone":"Europe/Moscow"}""")
        }
        val one = client.get("/api/v1/restaurants/${Samples.RESTAURANT_ID}") { bearer(token) }
        val patched = client.patch("/api/v1/restaurants/${Samples.RESTAURANT_ID}") {
            bearer(token)
            json("""{"name":"Новое"}""")
        }

        assertThat(list.json()["items"]!!.jsonArray.single().jsonObject["code"]!!.jsonPrimitive.content)
            .isEqualTo("MSK-01")
        assertThat(created.status).isEqualTo(HttpStatusCode.Created)
        assertThat(created.headers[HttpHeaders.Location]).isEqualTo("/api/v1/restaurants/${Samples.RESTAURANT_ID}")
        assertThat(draft.captured).isEqualTo(RestaurantDraft("MSK-01", "Кафе", "Москва", "", "Europe/Moscow"))
        assertThat(one.json()["timezone"]!!.jsonPrimitive.content).isEqualTo("Europe/Moscow")
        assertThat(patched.json()["name"]!!.jsonPrimitive.content).isEqualTo("Новое")
    }

    @Test
    fun `restaurant domain errors map to 400 409 404 with field and reason`() = kit.run {
        coEvery { kit.restaurants.create(match { it.code == "bad code" }) } returns
            Outcome.Failure(ServerError.Validation("code", "invalid_format"))
        coEvery { kit.restaurants.create(match { it.code == "TAKEN" }) } returns
            Outcome.Failure(ServerError.Conflict("restaurant_code_taken"))
        coEvery { kit.restaurants.get(RestaurantId("missing")) } returns
            Outcome.Failure(ServerError.NotFound("restaurant", "missing"))
        val body = { code: String -> """{"code":"$code","name":"x","city":"y","timezone":"Europe/Moscow"}""" }

        val invalid = client.post("/api/v1/restaurants") { bearer(token); json(body("bad code")) }
        val taken = client.post("/api/v1/restaurants") { bearer(token); json(body("TAKEN")) }
        val missing = client.get("/api/v1/restaurants/missing") { bearer(token) }

        assertThat(invalid.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(invalid.json()["field"]!!.jsonPrimitive.content).isEqualTo("code")
        assertThat(invalid.json()["reason"]!!.jsonPrimitive.content).isEqualTo("invalid_format")
        assertThat(taken.status).isEqualTo(HttpStatusCode.Conflict)
        assertThat(taken.json()["reason"]!!.jsonPrimitive.content).isEqualTo("restaurant_code_taken")
        assertThat(missing.status).isEqualTo(HttpStatusCode.NotFound)
        assertThat(missing.errorCode()).isEqualTo("not_found")
    }

    @Test
    fun `restaurant text with NUL or a missing required field never reaches the use case`() = kit.run {
        val nul = client.post("/api/v1/restaurants") {
            bearer(token)
            json("""{"code":"A","name":"x\u0000","city":"y","timezone":"Europe/Moscow"}""")
        }
        val missing = client.post("/api/v1/restaurants") { bearer(token); json("""{"code":"A"}""") }

        assertThat(nul.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(nul.json()["reason"]!!.jsonPrimitive.content).isEqualTo("nul_char")
        assertThat(missing.status).isEqualTo(HttpStatusCode.BadRequest)
        coVerify(exactly = 0) { kit.restaurants.create(any()) }
    }

    @Test
    fun `device list passes filters and paging`() = kit.run {
        val filter = DeviceFilter(RestaurantId(Samples.RESTAURANT_ID), DeviceRole.KITCHEN, DeviceStatus.ONLINE)
        coEvery { kit.devices.list(filter, PageRequest.of(10, 20)) } returns listOf(Samples.device)

        val response = client.get(
            "/api/v1/devices?restaurant=${Samples.RESTAURANT_ID}&role=kitchen&status=online&limit=10&offset=20",
        ) { bearer(token) }

        val item = response.json()["items"]!!.jsonArray.single().jsonObject
        assertThat(item["id"]!!.jsonPrimitive.content).isEqualTo("d-1")
        assertThat(item["status"]!!.jsonPrimitive.content).isEqualTo("online")
        assertThat(item["role"]!!.jsonPrimitive.content).isEqualTo("kitchen")
        assertThat(item["deviceInfo"]!!.jsonObject["apiLevel"]!!.jsonPrimitive.content).isEqualTo("36")
    }

    @Test
    fun `unknown role or status and non numeric paging are 400`() = kit.run {
        listOf("role=chef" to "role", "status=asleep" to "status", "limit=ten" to "limit").forEach { (query, field) ->
            val response = client.get("/api/v1/devices?$query") { bearer(token) }
            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.json()["field"]!!.jsonPrimitive.content).isEqualTo(field)
        }
    }

    @Test
    fun `device card carries last telemetry and session, a malformed id is simply not found`() = kit.run {
        val session = DeviceSession(id, "k-1", 4_000, null, null)
        coEvery { kit.devices.get(id) } returns
            Outcome.Success(DeviceDetails(Samples.device, Samples.telemetry, session))

        val card = client.get("/api/v1/devices/d-1") { bearer(token) }.json()
        val malformed = client.get("/api/v1/devices/bad+id") { bearer(token) }

        assertThat(card["device"]!!.jsonObject["status"]!!.jsonPrimitive.content).isEqualTo("online")
        assertThat(card["lastTelemetry"]!!.jsonObject["batteryLevel"]!!.jsonPrimitive.content).isEqualTo("71")
        assertThat(card["lastSession"]!!.jsonObject["connId"]!!.jsonPrimitive.content).isEqualTo("k-1")
        assertThat(malformed.status).isEqualTo(HttpStatusCode.NotFound)
    }

    @Test
    fun `decommission returns the device and whether retained messages were cleared`() = kit.run {
        val gone = Samples.device.copy(status = DeviceStatus.DECOMMISSIONED, decommissionedAt = 9_000)
        coEvery { kit.devices.decommission(id) } returns
            Outcome.Success(DecommissionResult(gone, retainedCleared = false))
        coEvery { kit.devices.decommission(DeviceId.parse("d-2")!!) } returns
            Outcome.Failure(ServerError.BrokerUnavailable("disable_client"))

        val ok = client.delete("/api/v1/devices/d-1") { bearer(token) }
        val brokerDown = client.delete("/api/v1/devices/d-2") { bearer(token) }

        assertThat(ok.status).isEqualTo(HttpStatusCode.OK)
        assertThat(ok.json()["device"]!!.jsonObject["status"]!!.jsonPrimitive.content).isEqualTo("decommissioned")
        assertThat(ok.json()["retainedCleared"]!!.jsonPrimitive.content).isEqualTo("false")
        assertThat(brokerDown.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
        assertThat(brokerDown.errorCode()).isEqualTo("broker_unavailable")
    }

    @Test
    fun `telemetry history accepts epoch and iso times and projects metrics`() = kit.run {
        val range = TimeRange(from = 1_793_865_600_000, to = 1_793_865_700_000)
        coEvery { kit.devices.telemetry(id, range, PageRequest.of()) } returns
            Outcome.Success(listOf(Samples.telemetry))

        val response = client.get(
            "/api/v1/devices/d-1/telemetry?from=2026-11-05T08:00:00Z&to=1793865700000&metric=batteryLevel,tempC",
        ) { bearer(token) }

        val point = response.json()["items"]!!.jsonArray.single().jsonObject
        assertThat(point.keys).containsExactly("ts", "seq", "batteryLevel", "tempC")
        assertThat(point["tempC"]!!.jsonPrimitive.content).isEqualTo("33.5")
    }

    @Test
    fun `bad time and unknown metric are 400, unknown device is 404`() = kit.run {
        coEvery { kit.devices.telemetry(id, any(), any()) } returns
            Outcome.Failure(ServerError.NotFound("device", "d-1"))

        val badTime = client.get("/api/v1/devices/d-1/telemetry?from=yesterday") { bearer(token) }
        val badMetric = client.get("/api/v1/devices/d-1/telemetry?metric=mood") { bearer(token) }
        val missing = client.get("/api/v1/devices/d-1/telemetry") { bearer(token) }

        assertThat(badTime.json()["reason"]!!.jsonPrimitive.content).isEqualTo("invalid_time")
        assertThat(badMetric.json()["field"]!!.jsonPrimitive.content).isEqualTo("metric")
        assertThat(missing.status).isEqualTo(HttpStatusCode.NotFound)
    }

    @Test
    fun `probe and event histories pass their filters and keep event payload as json`() = kit.run {
        val probe = ProbeRecord(id, 7_000, ProbeResult("api", false, null, "timeout", 5_000, null, null, null, null))
        val event = DeviceEvent(id, 8_000, "probe_state", """{"ts":8000,"type":"probe_state","state":"down"}""")
        coEvery { kit.devices.probes(id, TimeRange(), "api", PageRequest.of()) } returns Outcome.Success(listOf(probe))
        coEvery { kit.devices.events(id, TimeRange(), "probe_state", PageRequest.of()) } returns
            Outcome.Success(listOf(event))

        val probes = client.get("/api/v1/devices/d-1/probes?target=api") { bearer(token) }.json()
        val events = client.get("/api/v1/devices/d-1/events?type=probe_state") { bearer(token) }.json()

        val p = probes["items"]!!.jsonArray.single().jsonObject
        assertThat(p["error"]!!.jsonPrimitive.content).isEqualTo("timeout")
        assertThat(p["ok"]!!.jsonPrimitive.content).isEqualTo("false")
        val e = events["items"]!!.jsonArray.single().jsonObject
        assertThat(e["payload"]!!.jsonObject["state"]!!.jsonPrimitive.content).isEqualTo("down")
    }
}
