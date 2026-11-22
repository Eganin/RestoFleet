package ru.restofleet.console.feature.devicedetails.data

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
import ru.restofleet.console.feature.devicedetails.domain.DeviceLiveEvent
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome

class DeviceDetailsRepositoryTest {
    private class Session : WsSession {
        val incoming = Channel<SocketEvent>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        override val events: Flow<SocketEvent> = incoming.receiveAsFlow()
        override suspend fun send(text: String) {
            sent += text
        }
        override suspend fun close() = Unit
    }

    private class Tokens : AccessTokenSource {
        override fun accessToken() = "t"
        override suspend fun refresh(failed: String?) = RefreshOutcome.SessionEnded
    }

    private val requests = mutableListOf<String>()

    private fun TestScope.repo(session: Session = Session(), status: HttpStatusCode = HttpStatusCode.OK): DeviceDetailsRepositoryImpl {
        val client = MockHttp.client { req ->
            requests += req.url.encodedPath + "?" + req.url.encodedQuery
            if (status != HttpStatusCode.OK) return@client respond("", status)
            when {
                req.url.encodedPath.endsWith("/telemetry") -> json("""{"items":[
                    {"ts":2000,"seq":2,"tempC":41.5},{"ts":1000,"seq":1,"tempC":40.0},{"ts":3000,"seq":3,"batteryLevel":50}]}""")
                req.url.encodedPath.endsWith("/probes") -> json("""{"items":[${Fixtures.text("rest-console-probe-record")},${Fixtures.text("rest-console-probe-record-failed")}]}""")
                req.url.encodedPath.endsWith("/events") -> json("""{"items":[${Fixtures.text("rest-console-event-record")}]}""")
                else -> json(Fixtures.text("rest-console-device-details"))
            }
        }
        val rt = RealtimeClient({ _, _ -> session }, Tokens(), { "wss://x/ws" }, backgroundScope)
        return DeviceDetailsRepositoryImpl(DeviceDetailsApi(client), rt, Clock { 777L })
    }

    @Test
    fun `card maps device, last telemetry, session and stamps load time`() = runTest {
        val card = (repo().card("d-5a") as Outcome.Success).value

        assertThat(requests.single()).isEqualTo("/api/v1/devices/d-5a?")
        assertThat(card.name).isEqualTo("Зал 3")
        assertThat(card.status).isEqualTo("offline")
        assertThat(card.model).isEqualTo("sdk_gphone64_arm64")
        assertThat(card.osVersion).isEqualTo("16")
        assertThat(card.session!!.reason).isEqualTo("lwt")
        assertThat(card.telemetry!!.batteryLevel).isEqualTo(77)
        assertThat(card.loadedAt).isEqualTo(777L)
        assertThat(card.alerts).isEmpty()
    }

    @Test
    fun `temperature keeps only points with temperature in ascending order`() = runTest {
        val points = (repo().temperature("d", 500L, 200) as Outcome.Success).value

        assertThat(points.map { it.ts }).containsExactly(1000L, 2000L).inOrder()
        assertThat(requests.single()).isEqualTo("/api/v1/devices/d/telemetry?from=500&metric=tempC&limit=200")
    }

    @Test
    fun `probes and events are mapped, event payload is flattened without type and ts`() = runTest {
        val repo = repo()
        val probes = (repo.probes("d", 30) as Outcome.Success).value
        assertThat(probes.map { it.ok }).containsExactly(true, false).inOrder()
        assertThat(probes.first().ttfbMs).isEqualTo(108L)

        val page = (repo.events("d", 20, 20) as Outcome.Success).value
        assertThat(page.offset).isEqualTo(20)
        assertThat(page.hasMore).isFalse()
        assertThat(page.items.single().type).isEqualTo("probe_state")
        assertThat(page.items.single().fields).containsExactly("target", "api", "state", "down", "error", "timeout", "failures", "3")
        assertThat(requests.last()).isEqualTo("/api/v1/devices/d/events?limit=20&offset=20")
    }

    @Test
    fun `events report more when the page is full`() = runTest {
        val page = (repo().events("d", 0, 1) as Outcome.Success).value
        assertThat(page.hasMore).isTrue()
    }

    @Test
    fun `errors are mapped`() = runTest {
        assertThat(repo(status = HttpStatusCode.NotFound).card("x")).isEqualTo(Outcome.Failure(ConsoleError.NotFound))
    }

    @Test
    fun `live subscribes on collect, filters by device and unsubscribes on cancel`() = runTest {
        val session = Session()
        val rt = RealtimeClient({ _, _ -> session }, Tokens(), { "wss://x/ws" }, backgroundScope)
        val repo = DeviceDetailsRepositoryImpl(DeviceDetailsApi(MockHttp.client { error("no http") }), rt, Clock { 0L })
        rt.start()
        runCurrent()

        repo.live("d-0b1f6c2a9e7d4c3b").test {
            runCurrent()
            assertThat(session.sent.single()).contains("subscribe_device")
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-telemetry")))
            assertThat(awaitItem()).isInstanceOf(DeviceLiveEvent.TelemetryPushed::class.java)
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-device-status")))
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-alert")))
            assertThat(awaitItem()).isEqualTo(DeviceLiveEvent.ReloadNeeded)
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-resync")))
            assertThat(awaitItem()).isEqualTo(DeviceLiveEvent.ReloadNeeded)
            cancelAndIgnoreRemainingEvents()
        }
        runCurrent()
        assertThat(session.sent.last()).contains("unsubscribe_device")
    }

    @Test
    fun `status change of this device is mapped`() = runTest {
        val session = Session()
        val rt = RealtimeClient({ _, _ -> session }, Tokens(), { "wss://x/ws" }, backgroundScope)
        val repo = DeviceDetailsRepositoryImpl(DeviceDetailsApi(MockHttp.client { error("no http") }), rt, Clock { 0L })
        rt.start()
        runCurrent()

        repo.live("d-5a7e2b1c0f9d8e6a").test {
            runCurrent()
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-device-status")))
            assertThat(awaitItem()).isEqualTo(DeviceLiveEvent.StatusChanged("offline", 1795334080000L))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `live ignores other devices`() = runTest {
        val session = Session()
        val rt = RealtimeClient({ _, _ -> session }, Tokens(), { "wss://x/ws" }, backgroundScope)
        val repo = DeviceDetailsRepositoryImpl(DeviceDetailsApi(MockHttp.client { error("no http") }), rt, Clock { 0L })
        rt.start()
        runCurrent()

        repo.live("another-device").test {
            runCurrent()
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-telemetry")))
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-device-status")))
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-alert")))
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-incident")))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
