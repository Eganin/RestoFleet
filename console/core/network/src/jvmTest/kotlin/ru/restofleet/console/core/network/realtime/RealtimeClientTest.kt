package ru.restofleet.console.core.network.realtime

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.network.AccessTokenSource
import ru.restofleet.console.core.network.RefreshOutcome
import ru.restofleet.console.core.testing.Fixtures
import ru.restofleet.shared.protocol.rest.console.ws.WsCloseCodes
import ru.restofleet.shared.protocol.rest.console.ws.WsServerMessage

class RealtimeClientTest {
    private class FakeSession : WsSession {
        val incoming = Channel<SocketEvent>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        override val events: Flow<SocketEvent> = incoming.receiveAsFlow()
        override suspend fun send(text: String) { sent += text }
        override suspend fun close() = Unit
        fun close(code: Int?) {
            incoming.trySend(SocketEvent.Closed(code))
            incoming.close()
        }
    }

    private class FakeConnector(val script: MutableList<() -> WsSession>) : WsConnector {
        val tokensUsed = mutableListOf<String>()
        var connects = 0
        override suspend fun connect(url: String, accessToken: String): WsSession {
            connects++
            tokensUsed += accessToken
            return script.removeAt(0)()
        }
    }

    private class FakeTokens(var access: String? = "a1", var outcome: RefreshOutcome = RefreshOutcome.Refreshed("a2")) :
        AccessTokenSource {
        var refreshCalls = 0
        override fun accessToken(): String? = access
        override suspend fun refresh(failed: String?): RefreshOutcome {
            refreshCalls++
            (outcome as? RefreshOutcome.Refreshed)?.let { access = it.accessToken }
            return outcome
        }
    }

    private fun TestScope.client(connector: WsConnector, tokens: AccessTokenSource, url: String? = "wss://x/ws") =
        RealtimeClient(connector, tokens, { url }, backgroundScope, Backoff(jitter = { it }), policyDelayMs = 60_000)

    @Test
    fun `connect emits Connected then decoded messages, unknown and broken frames are skipped`() = runTest {
        val session = FakeSession()
        val rt = client(FakeConnector(mutableListOf({ session })), FakeTokens())
        rt.events.test {
            rt.start()
            assertThat(awaitItem()).isEqualTo(RealtimeEvent.Connected)
            session.incoming.send(SocketEvent.Text("""{"v":1,"type":"future","data":{}}"""))
            session.incoming.send(SocketEvent.Text("garbage"))
            session.incoming.send(SocketEvent.Text(Fixtures.text("ws-resync")))
            val msg = awaitItem() as RealtimeEvent.Message
            assertThat(msg.message).isInstanceOf(WsServerMessage.Resync::class.java)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `token goes only into connect call and repeated start does not reconnect`() = runTest {
        val connector = FakeConnector(mutableListOf({ FakeSession() }))
        val rt = client(connector, FakeTokens("secret-token"))
        rt.start()
        rt.start()
        runCurrent()

        assertThat(connector.connects).isEqualTo(1)
        assertThat(connector.tokensUsed).containsExactly("secret-token")
    }

    @Test
    fun `4401 refreshes the token and reconnects at once`() = runTest {
        val first = FakeSession()
        val connector = FakeConnector(mutableListOf({ first }, { FakeSession() }))
        val tokens = FakeTokens()
        val rt = client(connector, tokens)
        rt.start()
        runCurrent()

        first.close(WsCloseCodes.TOKEN_EXPIRED)
        runCurrent()

        assertThat(tokens.refreshCalls).isEqualTo(1)
        assertThat(connector.tokensUsed).containsExactly("a1", "a2").inOrder()
    }

    @Test
    fun `4401 with ended session emits SessionLost and stops`() = runTest {
        val first = FakeSession()
        val connector = FakeConnector(mutableListOf({ first }))
        val tokens = FakeTokens(outcome = RefreshOutcome.SessionEnded)
        val rt = client(connector, tokens)
        rt.events.test {
            rt.start()
            assertThat(awaitItem()).isEqualTo(RealtimeEvent.Connected)
            first.close(WsCloseCodes.TOKEN_EXPIRED)
            assertThat(awaitItem()).isEqualTo(RealtimeEvent.SessionLost)
        }
        advanceTimeBy(120_000)
        assertThat(connector.connects).isEqualTo(1)
    }

    @Test
    fun `1013 reconnects after backoff and emits Connected again so consumers resync`() = runTest {
        val first = FakeSession()
        val connector = FakeConnector(mutableListOf({ first }, { FakeSession() }))
        val rt = client(connector, FakeTokens())
        rt.events.test {
            rt.start()
            assertThat(awaitItem()).isEqualTo(RealtimeEvent.Connected)
            first.close(WsCloseCodes.TRY_AGAIN_LATER)
            advanceTimeBy(999)
            expectNoEvents()
            advanceTimeBy(2)
            assertThat(awaitItem()).isEqualTo(RealtimeEvent.Connected)
        }
    }

    @Test
    fun `1008 does not reconnect immediately`() = runTest {
        val first = FakeSession()
        val connector = FakeConnector(mutableListOf({ first }, { FakeSession() }))
        val rt = client(connector, FakeTokens())
        rt.start()
        runCurrent()

        first.close(WsCloseCodes.POLICY_VIOLATION)
        advanceTimeBy(59_000)
        assertThat(connector.connects).isEqualTo(1)
        advanceTimeBy(2_000)
        assertThat(connector.connects).isEqualTo(2)
    }

    @Test
    fun `connect failures back off exponentially up to the cap and hello resets the attempt`() = runTest {
        val failing = { throw IllegalStateException("down") }
        val hello = FakeSession().also { it.incoming.trySend(SocketEvent.Text(Fixtures.text("ws-hello"))) }
        val connector = FakeConnector(
            mutableListOf<() -> WsSession>(failing, failing, failing, { hello }, { FakeSession() }),
        )
        val rt = client(connector, FakeTokens())
        rt.start()
        runCurrent()
        assertThat(connector.connects).isEqualTo(1)
        advanceTimeBy(1_001)
        assertThat(connector.connects).isEqualTo(2)
        advanceTimeBy(2_001)
        assertThat(connector.connects).isEqualTo(3)
        advanceTimeBy(4_001)
        assertThat(connector.connects).isEqualTo(4)
        hello.close(null)
        runCurrent()
        advanceTimeBy(1_001)
        assertThat(connector.connects).isEqualTo(5)
    }

    @Test
    fun `handshake 401 refreshes once and retries, other handshake errors back off`() = runTest {
        val connector = FakeConnector(
            mutableListOf<() -> WsSession>({ throw WsHandshakeException(401) }, { FakeSession() }),
        )
        val tokens = FakeTokens()
        val rt = client(connector, tokens)
        rt.start()
        runCurrent()
        advanceTimeBy(1_001)

        assertThat(tokens.refreshCalls).isEqualTo(1)
        assertThat(connector.tokensUsed).containsExactly("a1", "a2").inOrder()
    }

    @Test
    fun `no token triggers refresh, no server address waits`() = runTest {
        val connector = FakeConnector(mutableListOf({ FakeSession() }))
        val tokens = FakeTokens(access = null)
        client(connector, tokens).start()
        runCurrent()
        assertThat(tokens.refreshCalls).isEqualTo(1)
        assertThat(connector.connects).isEqualTo(1)

        val idle = FakeConnector(mutableListOf())
        client(idle, FakeTokens(), url = null).start()
        runCurrent()
        assertThat(idle.connects).isEqualTo(0)
    }

    @Test
    fun `unavailable refresh retries later`() = runTest {
        val connector = FakeConnector(mutableListOf({ FakeSession() }))
        val tokens = FakeTokens(access = null, outcome = RefreshOutcome.Unavailable)
        client(connector, tokens).start()
        runCurrent()
        assertThat(connector.connects).isEqualTo(0)
        assertThat(tokens.refreshCalls).isEqualTo(1)
    }

    @Test
    fun `device subscriptions are sent now and replayed after reconnect, stop ends the loop`() = runTest {
        val first = FakeSession()
        val second = FakeSession()
        val connector = FakeConnector(mutableListOf({ first }, { second }))
        val rt = client(connector, FakeTokens())
        rt.start()
        runCurrent()

        rt.subscribeDevice("d1")
        runCurrent()
        assertThat(first.sent.single()).contains("subscribe_device")
        assertThat(first.sent.single()).contains("d1")

        first.close(null)
        advanceTimeBy(1_001)
        assertThat(second.sent.single()).contains("d1")

        rt.unsubscribeDevice("d1")
        runCurrent()
        assertThat(second.sent.last()).contains("unsubscribe_device")

        rt.stop()
        second.close(null)
        advanceTimeBy(120_000)
        assertThat(connector.connects).isEqualTo(2)
    }

    @Test
    fun `backoff grows, is capped and stays within bounds under jitter`() {
        val plain = Backoff(jitter = { it })
        assertThat((0..7).map { plain.delayMs(it) })
            .containsExactly(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L, 30000L).inOrder()
        assertThat(plain.delayMs(-3)).isEqualTo(1000L)
        assertThat(plain.delayMs(500)).isEqualTo(30000L)
        val jittered = Backoff()
        repeat(200) { assertThat(jittered.delayMs(it % 8)).isIn(com.google.common.collect.Range.closed(1000L, 30000L)) }
        assertThat(Backoff(jitter = { 1L }).delayMs(0)).isEqualTo(1000L)
    }
}
