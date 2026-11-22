package ru.restofleet.console.feature.auth.data

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.network.RefreshResult
import ru.restofleet.console.core.network.ServerEndpoint
import ru.restofleet.console.core.network.SessionManager
import ru.restofleet.console.core.storage.InMemoryTokenStore
import ru.restofleet.console.core.storage.StoredSession
import ru.restofleet.console.core.testing.Fixtures
import ru.restofleet.console.core.testing.MockHttp
import ru.restofleet.console.core.testing.MockHttp.json
import ru.restofleet.console.feature.auth.domain.AuthSession
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.rest.console.TokenResponseDto

private typealias Handler = suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData

class AuthRepositoryTest {
    private class Env(handler: Handler) {
        val requests = mutableListOf<String>()
        val store = InMemoryTokenStore()
        val endpoint = ServerEndpoint()
        val sessions = SessionManager(store, { RefreshResult.Unavailable }, endpoint)
        val client = MockHttp.client { req ->
            requests += req.method.value + " " + req.url
            handler(req)
        }
        val repo = AuthRepositoryImpl(AuthApi(client), sessions, endpoint)
    }

    @Test
    fun `login posts to the typed server, saves the session and exposes it`() = runTest {
        val env = Env { json(Fixtures.text("rest-console-token-response")) }

        val result = env.repo.login("https://rf.stand.local/", "support", "secret")

        assertThat(result).isEqualTo(Outcome.Success(AuthSession("https://rf.stand.local", "support")))
        assertThat(env.requests.single()).isEqualTo("POST https://rf.stand.local/api/v1/auth/login")
        assertThat(env.store.load()!!.login).isEqualTo("support")
        assertThat(env.endpoint.baseUrl).isEqualTo("https://rf.stand.local")
        env.repo.session.test { assertThat(awaitItem()).isEqualTo(AuthSession("https://rf.stand.local", "support")) }
    }

    @Test
    fun `insecure address is rejected before any request`() = runTest {
        val env = Env { error("must not be called") }

        val result = env.repo.login("http://rf.local", "u", "p")

        assertThat(result).isEqualTo(Outcome.Failure(ConsoleError.InvalidAddress("https_required")))
        assertThat(env.requests).isEmpty()
    }

    @Test
    fun `wrong credentials and network failure are mapped, nothing is stored`() = runTest {
        val wrong = Env { respond("", HttpStatusCode.Unauthorized) }
        val result = wrong.repo.login("https://rf.local", "u", "bad")
        assertThat(result).isEqualTo(Outcome.Failure(ConsoleError.Unauthorized))
        assertThat(wrong.store.load()).isNull()

        val down = Env { error("offline") }
        assertThat(down.repo.login("https://rf.local", "u", "p")).isEqualTo(Outcome.Failure(ConsoleError.Network))
        assertThat(down.endpoint.baseUrl).isNull()
    }

    @Test
    fun `restore reads the saved session`() = runTest {
        val env = Env { error("no requests") }
        assertThat(env.repo.restore()).isNull()
        env.store.save(StoredSession("https://rf.local", "u", "r"))

        assertThat(env.repo.restore()).isEqualTo(AuthSession("https://rf.local", "u"))
    }

    @Test
    fun `logout revokes refresh on the server and clears the session`() = runTest {
        val env = Env { respond("", HttpStatusCode.NoContent) }
        env.sessions.establish("https://rf.local", "u", TokenResponseDto("a", 1, "refresh-1", 2))

        env.repo.logout()

        assertThat(env.requests.single()).isEqualTo("POST https://rf.local/api/v1/auth/logout")
        assertThat(env.store.load()).isNull()
        env.repo.session.test { assertThat(awaitItem()).isNull() }
    }

    @Test
    fun `logout clears the session even if the server is down, and without session sends nothing`() = runTest {
        val down = Env { error("offline") }
        down.sessions.establish("https://rf.local", "u", TokenResponseDto("a", 1, "refresh-1", 2))
        down.repo.logout()
        assertThat(down.store.load()).isNull()

        val idle = Env { error("must not be called") }
        idle.repo.logout()
        assertThat(idle.requests).isEmpty()
    }
}
