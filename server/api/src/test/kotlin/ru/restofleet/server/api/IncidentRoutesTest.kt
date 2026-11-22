package ru.restofleet.server.api

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TimeRange
import ru.restofleet.server.domain.incident.Incident
import ru.restofleet.server.domain.incident.IncidentEvidence
import ru.restofleet.server.domain.incident.IncidentFilter
import ru.restofleet.server.domain.incident.IncidentKind
import ru.restofleet.server.domain.incident.IncidentThresholds
import ru.restofleet.server.domain.incident.IncidentView
import ru.restofleet.server.domain.incident.ResolveReason
import ru.restofleet.server.domain.incident.RestaurantEvidence
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.rest.console.IncidentDetailsDto
import ru.restofleet.shared.protocol.rest.console.IncidentDto
import ru.restofleet.shared.protocol.rest.console.ItemsDto

/** Инциденты (ПМ3 3.3): список, карточка с доказательствами, подтверждение; 401, 400, 404, 409. */
class IncidentRoutesTest {
    private val kit = ApiTestKit()
    private val token = kit.token()
    private val ghost = RestaurantId("00000000-0000-0000-0000-000000000000")

    private val incident = Incident(
        id = "i-1", kind = IncidentKind.BACKEND_LOCAL, target = "api", restaurants = listOf(Samples.restaurant.id),
        startedAt = 100,
        evidence = IncidentEvidence(
            evaluatedAt = 130,
            restaurants = listOf(
                RestaurantEvidence(Samples.restaurant.id, 6, 6, 6, 6, 6, 6, mapOf("timeout" to 6)),
                RestaurantEvidence(ghost, 1, 1, 1, 1, 1, 1, emptyMap()),
            ),
            otherRestaurantsOk = 11,
            thresholds = IncidentThresholds(50, 3, 12),
        ),
    )
    private val view = IncidentView(incident, listOf(Samples.restaurant), suppressedAlerts = 6)

    @Test
    fun `incidents require a token`() = kit.run {
        assertThat(client.get("/api/v1/incidents").status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(client.get("/api/v1/incidents/i-1").status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun `list filters by state and interval and maps the dto`() = kit.run {
        val closedFilter = IncidentFilter(open = false, range = TimeRange(10, 20))
        coEvery { kit.incidents.list(closedFilter, PageRequest.of()) } returns
            listOf(view.copy(incident = incident.copy(resolvedAt = 150, resolveReason = ResolveReason.CLEARED)))
        coEvery { kit.incidents.list(IncidentFilter(open = true), PageRequest.of()) } returns listOf(view)

        val closed = client.get("/api/v1/incidents?state=resolved&from=10&to=20") { bearer(token) }
        val openNow = client.get("/api/v1/incidents?state=open") { bearer(token) }

        val items = ItemsDto.serializer(IncidentDto.serializer())
        val dto = RfJson.decodeFromString(items, closed.bodyAsText()).items.single()
        assertThat(dto.state).isEqualTo("resolved")
        assertThat(dto.resolveReason).isEqualTo("cleared")
        assertThat(dto.ruleId).isEqualTo("R-09")
        val open = RfJson.decodeFromString(items, openNow.bodyAsText()).items
        assertThat(open.single().restaurants.single().code).isEqualTo(Samples.restaurant.code)
        assertThat(open.single().suppressedAlerts).isEqualTo(6)
        val bad = client.get("/api/v1/incidents?state=closed") { bearer(token) }
        assertThat(bad.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(bad.json()["field"]!!.jsonPrimitive.content).isEqualTo("state")
    }

    @Test
    fun `details carry evidence with restaurant names and skip unknown restaurants`() = kit.run {
        coEvery { kit.incidents.get("i-1") } returns Outcome.Success(view)
        coEvery { kit.incidents.get("i-2") } returns Outcome.Failure(ServerError.NotFound("incident", "i-2"))

        val body = client.get("/api/v1/incidents/i-1") { bearer(token) }.bodyAsText()
        val dto = RfJson.decodeFromString(IncidentDetailsDto.serializer(), body)
        assertThat(dto.incident.id).isEqualTo("i-1")
        val row = dto.evidence.restaurants.single()
        assertThat(row.name).isEqualTo(Samples.restaurant.name)
        assertThat(row.targetDown).isEqualTo(6)
        assertThat(row.errorClasses).containsExactly("timeout", 6)
        assertThat(dto.evidence.thresholds.m).isEqualTo(3)
        assertThat(dto.evidence.otherRestaurantsOk).isEqualTo(11)
        assertThat(client.get("/api/v1/incidents/i-2") { bearer(token) }.status).isEqualTo(HttpStatusCode.NotFound)
    }

    @Test
    fun `acknowledge takes the author from the token, conflicts and bad actions`() = kit.run {
        coEvery { kit.incidents.acknowledge("i-1", "admin", "звоним") } returns
            Outcome.Success(view.copy(incident = incident.copy(ackBy = "admin", ackAt = 200, comment = "звоним")))
        coEvery { kit.incidents.acknowledge("i-3", "admin", null) } returns
            Outcome.Failure(ServerError.Conflict("already_acknowledged"))

        val ok = client.patch("/api/v1/incidents/i-1") {
            bearer(token)
            json("""{"action":"acknowledge","comment":"звоним"}""")
        }
        val conflict = client.patch("/api/v1/incidents/i-3") {
            bearer(token)
            json("""{"action":"acknowledge"}""")
        }
        val unknown = client.patch("/api/v1/incidents/i-1") {
            bearer(token)
            json("""{"action":"resolve"}""")
        }

        assertThat(ok.json()["ackBy"]!!.jsonPrimitive.content).isEqualTo("admin")
        assertThat(conflict.status).isEqualTo(HttpStatusCode.Conflict)
        assertThat(unknown.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(unknown.json()["field"]!!.jsonPrimitive.content).isEqualTo("action")
    }
}
