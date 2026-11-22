package ru.restofleet.console.feature.incidents.domain

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Outcome

class IncidentsDomainTest {
    private val incident = Incident(
        "i1", IncidentKind.BackendGlobal, "R-08", false, "api", listOf(RestaurantRef("r1", "MSK-01", "Сокол")), 1L, null, null, 6, null, null,
    )
    private val evidence = IncidentEvidence(
        2L, listOf(RestaurantEvidence("Сокол", 6, 6, 6, 5, 6, 6, mapOf("timeout" to 5))), 21, Thresholds(50, 10, 38),
    )

    private class Repo(val incident: Incident, val details: IncidentDetails) : IncidentsRepository {
        var ack: Pair<String, String?>? = null
        override suspend fun lists(): Outcome<IncidentLists, ConsoleError> = Outcome.Success(IncidentLists(listOf(incident), emptyList(), 9L))
        override suspend fun details(id: String): Outcome<IncidentDetails, ConsoleError> = Outcome.Success(details)
        override suspend fun acknowledge(id: String, comment: String?): Outcome<Incident, ConsoleError> {
            ack = id to comment
            return Outcome.Success(incident.copy(ackBy = "me", comment = comment))
        }
        override fun events(): Flow<IncidentsEvent> = flowOf(IncidentsEvent.Pushed(incident), IncidentsEvent.ReloadNeeded)
    }

    @Test
    fun `kind wire names round trip, unknown stays unknown`() {
        IncidentKind.entries.filter { it != IncidentKind.Unknown }.forEach {
            assertThat(IncidentKind.fromWire(it.wire)).isEqualTo(it)
        }
        assertThat(IncidentKind.fromWire("future_kind")).isEqualTo(IncidentKind.Unknown)
        assertThat(IncidentKind.fromWire("")).isEqualTo(IncidentKind.Unknown)
    }

    @Test
    fun `load use cases delegate`() = runTest {
        val repo = Repo(incident, IncidentDetails(incident, evidence))
        assertThat((LoadIncidents(repo)() as Outcome.Success).value.open).containsExactly(incident)
        assertThat((LoadIncidentDetails(repo)("i1") as Outcome.Success).value.evidence.thresholds.m).isEqualTo(10)
        ObserveIncidentsEvents(repo)().test {
            assertThat(awaitItem()).isEqualTo(IncidentsEvent.Pushed(incident))
            assertThat(awaitItem()).isEqualTo(IncidentsEvent.ReloadNeeded)
            awaitComplete()
        }
    }

    @Test
    fun `acknowledge trims the comment, sends null for blank and rejects over 500`() = runTest {
        val repo = Repo(incident, IncidentDetails(incident, evidence))
        val ack = AcknowledgeIncident(repo)

        ack("i1", "  Провайдер подтвердил ")
        assertThat(repo.ack).isEqualTo("i1" to "Провайдер подтвердил")
        ack("i1", "   ")
        assertThat(repo.ack).isEqualTo("i1" to null)
        assertThat(ack("i1", "x".repeat(500))).isInstanceOf(Outcome.Success::class.java)
        repo.ack = null
        assertThat(ack("i1", "x".repeat(501))).isEqualTo(Outcome.Failure(ConsoleError.InvalidInput("comment", "too_long")))
        assertThat(repo.ack).isNull()
    }
}
