package ru.restofleet.console.core.network.realtime

import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.network.HttpClientFactory
import ru.restofleet.console.core.network.ServerEndpoint
import ru.restofleet.console.core.network.SessionManager
import ru.restofleet.console.core.network.RefreshResult
import ru.restofleet.console.core.storage.InMemoryTokenStore
import ru.restofleet.shared.protocol.rest.console.TokenResponseDto

/** Upgrade-запрос WebSocket на `MockEngine` (RV-2): заголовок `Authorization` ровно один, 401 виден с кодом. */
class KtorWsConnectorTest {
    private val endpoint = ServerEndpoint("https://rf.local")
    private val url = "wss://rf.local/api/v1/ws"

    private fun engine(seen: MutableList<List<String>>) = MockEngine { req ->
        seen += req.headers.getAll(HttpHeaders.Authorization).orEmpty()
        respond("", HttpStatusCode.Unauthorized)
    }

    @Test
    fun `upgrade over the realtime client has one bearer and 401 is reported with its status`() = runTest {
        val seen = mutableListOf<List<String>>()
        val connector = KtorWsConnector(HttpClientFactory.realtime(engine(seen), endpoint))

        val error = runCatching { connector.connect(url, "t1") }.exceptionOrNull()

        assertThat(error).isInstanceOf(WsHandshakeException::class.java)
        assertThat((error as WsHandshakeException).status).isEqualTo(401)
        assertThat(seen).containsExactly(listOf("Bearer t1"))
    }

    @Test
    fun `authorized client keeps an explicit authorization as the only one and does not refresh it`() = runTest {
        var refreshCalls = 0
        val session = SessionManager(InMemoryTokenStore(), {
            refreshCalls++
            RefreshResult.Ok(TokenResponseDto("access-2", 1, "refresh-2", 2))
        }, endpoint)
        session.establish("https://rf.local", "u", TokenResponseDto("access-1", 1, "refresh-1", 2))
        val seen = mutableListOf<List<String>>()
        val connector = KtorWsConnector(HttpClientFactory.authorized(engine(seen), endpoint, session))

        val error = runCatching { connector.connect(url, "access-1") }.exceptionOrNull()

        assertThat(error).isInstanceOf(WsHandshakeException::class.java)
        assertThat(seen).containsExactly(listOf("Bearer access-1"))
        assertThat(refreshCalls).isEqualTo(0)
    }
}
