package ru.restofleet.console.feature.devices.data

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.respond
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
import ru.restofleet.console.feature.devices.domain.DeviceFilter
import ru.restofleet.console.feature.devices.domain.DevicesEvent
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome

class DevicesRepositoryTest {
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

    private val queries = mutableListOf<Map<String, List<String>>>()

    private fun TestScope.repo(session: Session = Session(), failing: Boolean = false): DevicesRepositoryImpl {
        val client = MockHttp.client { req ->
            queries += req.url.parameters.entries().associate { it.key to it.value }
            when {
                failing -> respond("", HttpStatusCode.InternalServerError)
                req.url.encodedPath == "/api/v1/devices" -> json("""{"items":[${Fixtures.text("rest-console-device")}]}""")
                req.url.encodedPath == "/api/v1/restaurants" -> json("""{"items":[${Fixtures.text("rest-console-restaurant")}]}""")
                else -> error("unexpected ${req.url}")
            }
        }
        val rt = RealtimeClient({ _, _ -> session }, Tokens(), { "wss://x/ws" }, backgroundScope)
        return DevicesRepositoryImpl(DevicesApi(client), rt, Clock { 42L })
    }

    @Test
    fun `page sends every active filter and maps the row`() = runTest {
        val filter = DeviceFilter("кух", "r1", "kitchen", "online", "1.0.0", problemsOnly = true)

        val page = (repo().page(filter, offset = 30, limit = 30) as Outcome.Success).value

        assertThat(queries.single()).containsExactly(
            "restaurant", listOf("r1"), "role", listOf("kitchen"), "status", listOf("online"), "version", listOf("1.0.0"),
            "q", listOf("кух"), "problems", listOf("true"), "limit", listOf("30"), "offset", listOf("30"),
        )
        assertThat(page.loadedAt).isEqualTo(42L)
        assertThat(page.hasMore).isFalse()
        val item = page.items.single()
        assertThat(item.name).isEqualTo("Кухня 2")
        assertThat(item.batteryLevel).isEqualTo(34)
        assertThat(item.thermal).isEqualTo("SEVERE")
        assertThat(item.restaurantName).isEqualTo("Северная кухня")
    }

    @Test
    fun `page omits inactive filters and reports more when the page is full`() = runTest {
        val page = (repo().page(DeviceFilter(problemsOnly = false), 0, limit = 1) as Outcome.Success).value

        assertThat(queries.single().keys).containsExactly("limit", "offset")
        assertThat(page.hasMore).isTrue()
    }

    @Test
    fun `restaurants become labelled options and failures are mapped`() = runTest {
        val options = (repo().restaurants() as Outcome.Success).value
        assertThat(options.single().label).isEqualTo("Северная кухня · MSK-01")

        assertThat(repo(failing = true).page(DeviceFilter(), 0, 10)).isEqualTo(Outcome.Failure(ConsoleError.Server(500, null)))
    }

    @Test
    fun `realtime maps status changes and reload signals`() = runTest {
        val session = Session()
        val repo = repo(session)
        repo.events().test {
            // репозиторий не запускает клиента: подключение стартует корень; здесь сокет открывается вручную
            cancelAndIgnoreRemainingEvents()
        }
        val rt = RealtimeClient({ _, _ -> session }, Tokens(), { "wss://x/ws" }, backgroundScope)
        val events = DevicesRepositoryImpl(DevicesApi(MockHttp.client { error("no http") }), rt, Clock { 0L }).events()
        events.test {
            rt.start()
            runCurrent()
            assertThat(awaitItem()).isEqualTo(DevicesEvent.ReloadNeeded)
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-device-status")))
            assertThat(awaitItem()).isInstanceOf(DevicesEvent.StatusChanged::class.java)
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-alert")))
            assertThat(awaitItem()).isEqualTo(DevicesEvent.ReloadNeeded)
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-resync")))
            assertThat(awaitItem()).isEqualTo(DevicesEvent.ReloadNeeded)
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-incident")))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
