package ru.restofleet.console.feature.dashboard.data

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.network.AccessTokenSource
import ru.restofleet.console.core.network.RefreshOutcome
import ru.restofleet.console.core.network.realtime.RealtimeClient
import ru.restofleet.console.core.network.realtime.SocketEvent
import ru.restofleet.console.core.network.realtime.WsConnector
import ru.restofleet.console.core.network.realtime.WsSession
import ru.restofleet.console.core.testing.Fixtures
import ru.restofleet.console.core.testing.MockHttp
import ru.restofleet.console.core.testing.MockHttp.json
import ru.restofleet.console.feature.dashboard.domain.DashboardEvent
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent

class DashboardRepositoryTest {
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

    private fun TestScope.realtime(session: Session) =
        RealtimeClient({ _, _ -> session }, Tokens(), { "wss://x/ws" }, backgroundScope)

    private fun api(alertsStatus: HttpStatusCode = HttpStatusCode.OK, summaryStatus: HttpStatusCode = HttpStatusCode.OK) =
        DashboardApi(
            MockHttp.client { req ->
                when (req.url.encodedPath) {
                    "/api/v1/summary" -> if (summaryStatus.value < 400) json(Fixtures.text("rest-console-summary")) else respond("", summaryStatus)
                    "/api/v1/alerts" -> {
                        assertThat(req.url.parameters["limit"]).isEqualTo("5")
                        if (alertsStatus.value < 400) json(Fixtures.text("rest-console-alerts-page")) else respond("", alertsStatus)
                    }
                    else -> error("unexpected ${req.url}")
                }
            },
        )

    private val clock = Clock { 1_795_334_500_000L }

    @Test
    fun `load maps summary and recent alerts and stamps the time`() = runTest {
        val repo = DashboardRepositoryImpl(api(), realtime(Session()), clock)

        val dashboard = (repo.load() as Outcome.Success).value

        assertThat(dashboard.loadedAt).isEqualTo(1_795_334_500_000L)
        assertThat(dashboard.summary.devicesOnline).isEqualTo(214)
        assertThat(dashboard.summary.problems).isNotEmpty()
        assertThat(dashboard.summary.problems.first().code).isEqualTo("MSK-01")
        assertThat(dashboard.recent).isNotEmpty()
        assertThat(dashboard.recent.map { it.ruleId }).contains("R-01")
    }

    @Test
    fun `recent alerts failure does not hide the summary, summary failure is an error`() = runTest {
        val ok = DashboardRepositoryImpl(api(alertsStatus = HttpStatusCode.InternalServerError), realtime(Session()), clock)
        assertThat((ok.load() as Outcome.Success).value.recent).isEmpty()

        val bad = DashboardRepositoryImpl(api(summaryStatus = HttpStatusCode.Forbidden), realtime(Session()), clock)
        assertThat(bad.load()).isEqualTo(Outcome.Failure(ConsoleError.Forbidden))
    }

    @Test
    fun `realtime events become dashboard events`() = runTest {
        val session = Session()
        val rt = realtime(session)
        val repo = DashboardRepositoryImpl(api(), rt, clock)
        repo.events().test {
            rt.start()
            runCurrent()
            assertThat(awaitItem()).isEqualTo(DashboardEvent.ReloadNeeded) // Connected
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-summary-changed")))
            assertThat(awaitItem()).isInstanceOf(DashboardEvent.SummaryPushed::class.java)
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-alert")))
            assertThat(awaitItem()).isEqualTo(DashboardEvent.ReloadNeeded)
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-device-status")))
            assertThat(awaitItem()).isEqualTo(DashboardEvent.ReloadNeeded)
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-resync")))
            assertThat(awaitItem()).isEqualTo(DashboardEvent.ReloadNeeded)
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-hello")))
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-telemetry")))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
