package ru.restofleet.console.feature.alerts.data

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
import ru.restofleet.console.feature.alerts.domain.AlertState
import ru.restofleet.console.feature.alerts.domain.AlertsEvent
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome

class AlertsRepositoryTest {
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

    private fun TestScope.repo(session: Session = Session(), failState: String? = null): AlertsRepositoryImpl {
        val client = MockHttp.client { req ->
            log += req.method.value + " " + req.url.encodedPath + "?" + req.url.encodedQuery
            bodies += String(req.body.toByteArray())
            when {
                req.method.value == "PATCH" -> json(Fixtures.text("rest-console-alert"))
                req.url.parameters["state"] == failState -> respond("", HttpStatusCode.InternalServerError)
                req.url.parameters["state"] == "open" -> json(Fixtures.text("rest-console-alerts-page"))
                else -> json("""{"items":[]}""")
            }
        }
        val rt = RealtimeClient({ _, _ -> session }, Tokens(), { "wss://x/ws" }, backgroundScope)
        return AlertsRepositoryImpl(AlertsApi(client), rt, Clock { 55L })
    }

    @Test
    fun `lists load three states in parallel and stamp the time`() = runTest {
        val lists = (repo().lists(100) as Outcome.Success).value

        assertThat(log.map { it.substringAfter("state=").substringBefore("&") }).containsExactly("open", "acknowledged", "resolved")
        assertThat(log.all { "limit=100" in it }).isTrue()
        assertThat(lists.open).isNotEmpty()
        assertThat(lists.open.first().ruleId).isNotEmpty()
        assertThat(lists.acknowledged).isEmpty()
        assertThat(lists.resolved).isEmpty()
        assertThat(lists.loadedAt).isEqualTo(55L)
    }

    @Test
    fun `any failed list fails the load`() = runTest {
        val result = repo(failState = "resolved").lists(100)
        assertThat(result).isEqualTo(Outcome.Failure(ConsoleError.Server(500, null)))
    }

    @Test
    fun `acknowledge and resolve send the action with an optional comment`() = runTest {
        val repo = repo()

        val acked = (repo.acknowledge("a-1") as Outcome.Success).value
        repo.resolve("a-1", "Заменили кабель")

        assertThat(log[0]).startsWith("PATCH /api/v1/alerts/a-1")
        assertThat(bodies[0]).isEqualTo("""{"action":"acknowledge"}""")
        assertThat(bodies[1]).isEqualTo("""{"action":"resolve","comment":"Заменили кабель"}""")
        assertThat(acked.state).isEqualTo(AlertState.fromWire(acked.state.wire))
    }

    @Test
    fun `conflict from the server is mapped`() = runTest {
        val client = MockHttp.client { respond("", HttpStatusCode.Conflict) }
        val rt = RealtimeClient({ _, _ -> Session() }, Tokens(), { "wss://x/ws" }, backgroundScope)
        val repo = AlertsRepositoryImpl(AlertsApi(client), rt, Clock { 0L })
        assertThat(repo.acknowledge("a")).isEqualTo(Outcome.Failure(ConsoleError.Conflict(null)))
    }

    @Test
    fun `realtime alert is pushed with its state, resync and connect ask for reload`() = runTest {
        val session = Session()
        val rt = RealtimeClient({ _, _ -> session }, Tokens(), { "wss://x/ws" }, backgroundScope)
        val repo = AlertsRepositoryImpl(AlertsApi(MockHttp.client { error("no http") }), rt, Clock { 0L })
        repo.events().test {
            rt.start()
            runCurrent()
            assertThat(awaitItem()).isEqualTo(AlertsEvent.ReloadNeeded)
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-alert")))
            val pushed = awaitItem() as AlertsEvent.Pushed
            assertThat(pushed.alert.critical).isTrue()
            assertThat(pushed.alert.state).isEqualTo(AlertState.Open)
            assertThat(pushed.alert.parentIncidentId).isNull()
            val suppressed = Fixtures.text("ws-alert").replace("\"auto\": false", "\"auto\": false, \"parentIncidentId\": \"i-1\"")
            session.incoming.send(SocketEvent.Text(suppressed))
            assertThat((awaitItem() as AlertsEvent.Pushed).alert.parentIncidentId).isEqualTo("i-1")
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-resync")))
            assertThat(awaitItem()).isEqualTo(AlertsEvent.ReloadNeeded)
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-incident")))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
