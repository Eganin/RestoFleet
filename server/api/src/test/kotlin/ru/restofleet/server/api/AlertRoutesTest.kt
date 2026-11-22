package ru.restofleet.server.api

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import ru.restofleet.server.domain.alert.Alert
import ru.restofleet.server.domain.alert.AlertFilter
import ru.restofleet.server.domain.alert.AlertState
import ru.restofleet.server.domain.alert.AlertSubject
import ru.restofleet.server.domain.alert.AlertView
import ru.restofleet.server.domain.alert.ProblemRestaurant
import ru.restofleet.server.domain.alert.RuleId
import ru.restofleet.server.domain.alert.Summary
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.device.DeviceDetails
import ru.restofleet.server.domain.device.DeviceFilter
import ru.restofleet.server.domain.device.DeviceOverview
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.rest.console.AlertDto
import ru.restofleet.shared.protocol.rest.console.CommandDto
import ru.restofleet.shared.protocol.rest.console.DeviceDetailsDto
import ru.restofleet.shared.protocol.rest.console.DeviceDto
import ru.restofleet.shared.protocol.rest.console.ItemsDto
import ru.restofleet.shared.protocol.rest.console.SummaryDto

/** Тревоги, сводка, история команд и поиск устройств (ПМ2 2.4): 401, 400, 404, 409 и маппинг в DTO консоли. */
class AlertRoutesTest {
    private val kit = ApiTestKit()
    private val token = kit.token()

    private val alert = Alert(
        id = "a-1", subject = AlertSubject(RuleId.R05, Samples.deviceId, "api"),
        restaurantId = RestaurantId(Samples.RESTAURANT_ID), state = AlertState.OPEN, openedAt = 10,
        details = mapOf("error" to "timeout"),
    )
    private val view = AlertView(alert, "Кухня 1", "MSK-01", "Северная кухня")

    private suspend fun ApplicationTestBuilder.act(id: String, body: String) =
        client.patch("/api/v1/alerts/$id") {
            bearer(token)
            json(body)
        }

    @Test
    fun `everything requires a token`() = kit.run {
        listOf("/api/v1/alerts", "/api/v1/summary", "/api/v1/devices/d-1/commands").forEach { path ->
            assertThat(client.get(path).status).isEqualTo(HttpStatusCode.Unauthorized)
        }
        assertThat(client.patch("/api/v1/alerts/a-1") { json("""{"action":"acknowledge"}""") }.status)
            .isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun `list passes filters and maps to the console dto`() = kit.run {
        val filter = AlertFilter(
            state = AlertState.OPEN, restaurantId = RestaurantId(Samples.RESTAURANT_ID), deviceId = Samples.deviceId,
            rule = RuleId.R05, includeSuppressed = true,
        )
        coEvery { kit.alerts.list(filter, PageRequest.of(5, 0)) } returns listOf(view)

        val response = client.get(
            "/api/v1/alerts?state=open&restaurant=${Samples.RESTAURANT_ID}&device=d-1&rule=R-05" +
                "&includeSuppressed=true&limit=5",
        ) { bearer(token) }

        val items = RfJson.decodeFromString(ItemsDto.serializer(AlertDto.serializer()), response.bodyAsText()).items
        assertThat(items.single()).isEqualTo(
            AlertDto(
                id = "a-1", ruleId = "R-05", severity = "critical", state = "open", deviceId = "d-1",
                deviceName = "Кухня 1", restaurantId = Samples.RESTAURANT_ID, restaurantCode = "MSK-01",
                restaurantName = "Северная кухня", target = "api", openedAt = 10, details = mapOf("error" to "timeout"),
            ),
        )
    }

    @Test
    fun `unknown filter values are 400`() = kit.run {
        listOf(
            "state=closed" to "state", "rule=R-07" to "rule", "rule=R05" to "rule", "includeSuppressed=yes" to
                "includeSuppressed", "device=bad%20id" to "device",
        ).forEach { (query, field) ->
            val response = client.get("/api/v1/alerts?$query") { bearer(token) }
            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.json()["field"]!!.jsonPrimitive.content).isEqualTo(field)
        }
    }

    @Test
    fun `acknowledge and resolve take the author from the token`() = kit.run {
        coEvery { kit.alerts.acknowledge("a-1", "admin", "смотрим") } returns
            Outcome.Success(view.copy(alert = alert.copy(state = AlertState.ACKNOWLEDGED, ackBy = "admin")))
        coEvery { kit.alerts.resolve("a-1", "admin", "готово") } returns
            Outcome.Success(view.copy(alert = alert.copy(state = AlertState.RESOLVED, resolvedBy = "admin")))

        val acked = act("a-1", """{"action":"acknowledge","comment":"смотрим"}""")
        val resolved = act("a-1", """{"action":"resolve","comment":"готово"}""")

        assertThat(acked.json()["ackBy"]!!.jsonPrimitive.content).isEqualTo("admin")
        assertThat(resolved.json()["state"]!!.jsonPrimitive.content).isEqualTo("resolved")
    }

    @Test
    fun `action errors map to 400 404 409`() = kit.run {
        coEvery { kit.alerts.resolve("a-1", "admin", null) } returns
            Outcome.Failure(ServerError.Validation("comment", "required"))
        coEvery { kit.alerts.resolve("a-2", "admin", "x") } returns
            Outcome.Failure(ServerError.NotFound("alert", "a-2"))
        coEvery { kit.alerts.acknowledge("a-3", "admin", null) } returns
            Outcome.Failure(ServerError.Conflict("already_resolved"))

        val noComment = act("a-1", """{"action":"resolve"}""")
        val missing = act("a-2", """{"action":"resolve","comment":"x"}""")
        val conflict = act("a-3", """{"action":"acknowledge"}""")
        val unknown = act("a-1", """{"action":"delete"}""")
        val malformed = act("a-1", """{"comment":"x"}""")

        assertThat(noComment.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(noComment.json()["field"]!!.jsonPrimitive.content).isEqualTo("comment")
        assertThat(missing.status).isEqualTo(HttpStatusCode.NotFound)
        assertThat(conflict.status).isEqualTo(HttpStatusCode.Conflict)
        assertThat(conflict.json()["reason"]!!.jsonPrimitive.content).isEqualTo("already_resolved")
        assertThat(unknown.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(unknown.json()["field"]!!.jsonPrimitive.content).isEqualTo("action")
        assertThat(malformed.status).isEqualTo(HttpStatusCode.BadRequest)
    }

    @Test
    fun `summary maps problems`() = kit.run {
        val problem =
            ProblemRestaurant(Samples.restaurant, RuleId.R05, 4, 6, "api", null, null, 7, mapOf("error" to "timeout"))
        coEvery { kit.alerts.summary() } returns Summary(214, 228, 14, 3, 9, 3, 1, 3, 38, listOf(problem))

        val body = client.get("/api/v1/summary") { bearer(token) }.bodyAsText()
        val dto = RfJson.decodeFromString(SummaryDto.serializer(), body)

        assertThat(dto.devicesOnline).isEqualTo(214)
        assertThat(dto.incidentsOpen).isEqualTo(1)
        val row = dto.problemRestaurants.single()
        assertThat(row.restaurant.code).isEqualTo(Samples.restaurant.code)
        assertThat(row.ruleId).isEqualTo("R-05")
        assertThat(row.count).isEqualTo(4)
        assertThat(row.target).isEqualTo("api")
        assertThat(row.deviceId).isNull()
    }

    @Test
    fun `command history is paged and unknown device is 404`() = kit.run {
        coEvery { kit.commands.history(Samples.deviceId, PageRequest.of(2, 0)) } returns
            Outcome.Success(listOf(Samples.command))
        val dto = RfJson.decodeFromString(
            ItemsDto.serializer(CommandDto.serializer()),
            client.get("/api/v1/devices/d-1/commands?limit=2") { bearer(token) }.bodyAsText(),
        )
        assertThat(dto.items.single().cmdId).isEqualTo(Samples.command.cmdId)

        coEvery { kit.commands.history(any(), any()) } returns Outcome.Failure(ServerError.NotFound("device", "d-2"))
        val unknown = client.get("/api/v1/devices/d-2/commands") { bearer(token) }
        assertThat(unknown.status).isEqualTo(HttpStatusCode.NotFound)
    }

    @Test
    fun `device search params reach the filter and the row carries console fields`() = kit.run {
        val filter = DeviceFilter(query = "кухня", agentVersion = "1.0.0", problemsOnly = true)
        coEvery { kit.devices.list(filter, PageRequest.of()) } returns listOf(
            DeviceOverview(Samples.device, Samples.restaurant, Samples.telemetry, "lwt", listOf(alert)),
        )

        val query = "q=%20%D0%BA%D1%83%D1%85%D0%BD%D1%8F%20&version=1.0.0&problems=true"
        val response = client.get("/api/v1/devices?$query") {
            bearer(token)
        }
        val row = RfJson.decodeFromString(ItemsDto.serializer(DeviceDto.serializer()), response.bodyAsText())
            .items.single()
        assertThat(row.restaurantCode).isEqualTo(Samples.restaurant.code)
        assertThat(row.restaurantName).isEqualTo(Samples.restaurant.name)
        assertThat(row.lastTelemetry?.ts).isEqualTo(Samples.telemetry.ts)
        assertThat(row.lastSessionReason).isEqualTo("lwt")
        assertThat(row.alerts!!.single().restaurantCode).isEqualTo(Samples.restaurant.code)
    }

    @Test
    fun `device search params are validated`() = kit.run {
        listOf("q=${"x".repeat(101)}" to "q", "problems=1" to "problems", "version=${"v".repeat(65)}" to "version")
            .forEach { (query, field) ->
                val response = client.get("/api/v1/devices?$query") { bearer(token) }
                assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
                assertThat(response.json()["field"]!!.jsonPrimitive.content).isEqualTo(field)
            }
        coVerify(exactly = 0) { kit.devices.list(any(), any()) }
    }

    @Test
    fun `device card carries restaurant and alerts`() = kit.run {
        coEvery { kit.devices.get(Samples.deviceId) } returns Outcome.Success(
            DeviceDetails(Samples.device, null, null, Samples.restaurant, listOf(alert)),
        )
        val dto = RfJson.decodeFromString(
            DeviceDetailsDto.serializer(), client.get("/api/v1/devices/d-1") { bearer(token) }.bodyAsText(),
        )
        assertThat(dto.device.restaurantName).isEqualTo(Samples.restaurant.name)
        assertThat(dto.device.alerts!!.single().id).isEqualTo("a-1")
        assertThat(dto.device.lastTelemetry).isNull()
    }
}
