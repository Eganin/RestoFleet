package ru.restofleet.console.feature.incidents.data

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.network.AccessTokenSource
import ru.restofleet.console.core.network.RefreshOutcome
import ru.restofleet.console.core.network.realtime.RealtimeClient
import ru.restofleet.console.core.network.realtime.SocketEvent
import ru.restofleet.console.core.network.realtime.WsSession
import ru.restofleet.console.core.testing.Fixtures
import ru.restofleet.console.core.testing.MockHttp
import ru.restofleet.console.core.testing.MockHttp.json
import ru.restofleet.console.feature.incidents.domain.IncidentKind
import ru.restofleet.console.feature.incidents.domain.IncidentsEvent
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome

class IncidentsRepositoryTest {
    private class Session : WsSession {
        val incoming = Channel<SocketEvent>(Channel.UNLIMITED)
        override val events: Flow<SocketEvent> = incoming.receiveAsFlow()
        override suspend fun send(text: String) = Unit
        override suspend fun close() = Unit
    }

    private class Tokens : AccessTokenSource {
        override fun accessToken() = "t"
        override suspend fun refresh(failed: String?) = RefreshOutcome.SessionEnded
    }

    private val log = mutableListOf<String>()
    private val bodies = mutableListOf<String>()
    private val day = 24 * 60 * 60 * 1000L

    private fun TestScope.repo(status: HttpStatusCode = HttpStatusCode.OK, session: Session = Session()): IncidentsRepositoryImpl {
        val client = MockHttp.client { req ->
            log += req.method.value + " " + req.url.encodedPath + "?" + req.url.encodedQuery
            bodies += String(req.body.toByteArray())
            when {
                status != HttpStatusCode.OK -> respond("", status)
                req.method.value == "PATCH" -> json(Fixtures.text("rest-console-incident"))
                req.url.encodedPath.endsWith("/incidents") && req.url.parameters["state"] == "open" ->
                    json("""{"items":[${Fixtures.text("rest-console-incident")}]}""")
                req.url.encodedPath.endsWith("/incidents") -> json("""{"items":[]}""")
                else -> json(Fixtures.text("rest-console-incident-details"))
            }
        }
        val rt = RealtimeClient({ _, _ -> session }, Tokens(), { "wss://x/ws" }, backgroundScope)
        return IncidentsRepositoryImpl(IncidentsApi(client), rt, Clock { 10 * day })
    }

    @Test
    fun `lists ask for open incidents and for resolved ones of the last day`() = runTest {
        val lists = (repo().lists() as Outcome.Success).value

        assertThat(log).containsExactly(
            "GET /api/v1/incidents?state=open", "GET /api/v1/incidents?state=resolved&from=${9 * day}",
        )
        assertThat(lists.open).hasSize(1)
        assertThat(lists.resolved).isEmpty()
        assertThat(lists.loadedAt).isEqualTo(10 * day)
        val incident = lists.open.single()
        assertThat(incident.kind).isNotEqualTo(IncidentKind.Unknown)
        assertThat(incident.restaurants).isNotEmpty()
    }

    @Test
    fun `details carry evidence with thresholds and per restaurant counters`() = runTest {
        val details = (repo().details("i-213") as Outcome.Success).value

        assertThat(log.single()).isEqualTo("GET /api/v1/incidents/i-213?")
        assertThat(details.incident.resolved).isTrue()
        assertThat(details.incident.resolveReason).isEqualTo("cleared")
        assertThat(details.incident.suppressedAlerts).isEqualTo(6)
        assertThat(details.incident.comment).isEqualTo("Провайдер подтвердил аварию")
        assertThat(details.evidence.restaurants).isNotEmpty()
        assertThat(details.evidence.restaurants.first().devicesTotal).isGreaterThan(0)
        assertThat(details.evidence.thresholds.p).isGreaterThan(0)
    }

    @Test
    fun `acknowledge sends the action with a comment`() = runTest {
        val incident = (repo().acknowledge("i-213", "Проверили") as Outcome.Success).value

        assertThat(log.single()).startsWith("PATCH /api/v1/incidents/i-213")
        assertThat(bodies.single()).isEqualTo("""{"action":"acknowledge","comment":"Проверили"}""")
        assertThat(incident.id).isNotEmpty()
    }

    @Test
    fun `failures are mapped`() = runTest {
        assertThat(repo(HttpStatusCode.Forbidden).lists()).isEqualTo(Outcome.Failure(ConsoleError.Forbidden))
        assertThat(repo(HttpStatusCode.Conflict).acknowledge("x", null)).isEqualTo(Outcome.Failure(ConsoleError.Conflict(null)))
        assertThat(repo(HttpStatusCode.NotFound).details("x")).isEqualTo(Outcome.Failure(ConsoleError.NotFound))
    }

    @Test
    fun `resolved list failure fails the load`() = runTest {
        val client = MockHttp.client { req ->
            if (req.url.parameters["state"] == "open") json("""{"items":[]}""") else respond("", HttpStatusCode.BadGateway)
        }
        val rt = RealtimeClient({ _, _ -> Session() }, Tokens(), { "wss://x/ws" }, backgroundScope)
        val repo = IncidentsRepositoryImpl(IncidentsApi(client), rt, Clock { 0L })
        assertThat(repo.lists()).isEqualTo(Outcome.Failure(ConsoleError.Server(502, null)))
    }

    @Test
    fun `realtime incident is pushed, connect and resync ask for reload`() = runTest {
        val session = Session()
        val rt = RealtimeClient({ _, _ -> session }, Tokens(), { "wss://x/ws" }, backgroundScope)
        val repo = IncidentsRepositoryImpl(IncidentsApi(MockHttp.client { error("no http") }), rt, Clock { 0L })
        repo.events().test {
            rt.start()
            runCurrent()
            assertThat(awaitItem()).isEqualTo(IncidentsEvent.ReloadNeeded)
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-incident")))
            assertThat(awaitItem()).isInstanceOf(IncidentsEvent.Pushed::class.java)
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-resync")))
            assertThat(awaitItem()).isEqualTo(IncidentsEvent.ReloadNeeded)
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-alert")))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
