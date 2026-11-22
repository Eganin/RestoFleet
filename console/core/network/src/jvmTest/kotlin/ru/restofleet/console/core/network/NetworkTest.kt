package ru.restofleet.console.core.network

import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.storage.InMemoryTokenStore
import ru.restofleet.console.core.storage.StoredSession
import ru.restofleet.console.core.testing.Fixtures
import ru.restofleet.console.core.testing.MockHttp
import ru.restofleet.console.core.testing.MockHttp.json
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.rest.ApiErrorDto
import ru.restofleet.shared.protocol.rest.console.SummaryDto
import ru.restofleet.shared.protocol.rest.console.TokenResponseDto

class NetworkTest {
    private fun tokens(n: Int) = TokenResponseDto("access-$n", 1, "refresh-$n", 2)

    @Test
    fun `server address table`() {
        val ok = mapOf(
            "https://rf.stand.local" to "https://rf.stand.local",
            "  HTTPS://rf.stand.local/ " to "https://rf.stand.local",
            "https://rf.stand.local:8443/api" to "https://rf.stand.local:8443/api",
        )
        ok.forEach { (raw, expected) -> assertThat(ServerAddress.normalize(raw)).isEqualTo(Outcome.Success(expected)) }
        val bad = mapOf(
            "" to "empty", "   " to "empty", "http://rf.local" to "https_required", "rf.local" to "https_required",
            "https://" to "no_host", "https:///x" to "no_host", "https://u:p@rf.local" to "userinfo",
            "https://rf.local?x=1" to "query", "https://rf.local#a" to "query", "https://rf local" to "whitespace",
            "ftp://rf.local" to "https_required",
        )
        bad.forEach { (raw, reason) ->
            assertThat(ServerAddress.normalize(raw)).isEqualTo(Outcome.Failure(ConsoleError.InvalidAddress(reason)))
        }
    }

    @Test
    fun `ws url swaps scheme`() {
        assertThat(ServerEndpoint().wsUrl()).isNull()
        assertThat(ServerEndpoint("https://rf.local").wsUrl()).isEqualTo("wss://rf.local/api/v1/ws")
    }

    @Test
    fun `error mapper table`() {
        val err = ApiErrorDto("c", "m", field = "f", reason = "r")
        assertThat(ApiErrorMapper.map(401, err)).isEqualTo(ConsoleError.Unauthorized)
        assertThat(ApiErrorMapper.map(403, null)).isEqualTo(ConsoleError.Forbidden)
        assertThat(ApiErrorMapper.map(404, null)).isEqualTo(ConsoleError.NotFound)
        assertThat(ApiErrorMapper.map(409, err)).isEqualTo(ConsoleError.Conflict("r"))
        assertThat(ApiErrorMapper.map(409, ApiErrorDto("code", "m"))).isEqualTo(ConsoleError.Conflict("code"))
        assertThat(ApiErrorMapper.map(400, err)).isEqualTo(ConsoleError.Validation("f", "m"))
        assertThat(ApiErrorMapper.map(422, null)).isEqualTo(ConsoleError.Validation(null, "invalid request"))
        assertThat(ApiErrorMapper.map(429, null, " 7 ")).isEqualTo(ConsoleError.RateLimited(7))
        assertThat(ApiErrorMapper.map(429, null, "later")).isEqualTo(ConsoleError.RateLimited(null))
        assertThat(ApiErrorMapper.map(503, err)).isEqualTo(ConsoleError.Server(503, "c"))
        assertThat(ApiErrorMapper.parseBody("<html>502</html>")).isNull()
        assertThat(ApiErrorMapper.parseBody(Fixtures.text("rest-console-error"))).isNotNull()
    }

    @Test
    fun `api call maps success, http errors, bad body and transport failures`() = runTest {
        val conflictBody = RfJson.encodeToString(ApiErrorDto.serializer(), ApiErrorDto("c", "m", reason = "dup"))
        val client = MockHttp.client { req ->
            when (req.url.encodedPath) {
                "/ok" -> json(Fixtures.text("rest-console-summary"))
                "/bad" -> json("""{"x":1}""")
                "/conflict" -> json(conflictBody, HttpStatusCode.Conflict)
                "/html" -> respond("<html>", HttpStatusCode.BadGateway, headersOf(HttpHeaders.ContentType, "text/html"))
                "/none" -> respond("", HttpStatusCode.NoContent)
                else -> error("no connection")
            }
        }
        assertThat((apiCall<SummaryDto> { client.get("/ok") } as Outcome.Success).value.devicesTotal).isGreaterThan(0)
        suspend fun failure(path: String) = (apiCall<SummaryDto> { client.get(path) } as Outcome.Failure).error
        assertThat(failure("/bad")).isEqualTo(ConsoleError.Server(0, "bad_response"))
        assertThat(failure("/conflict")).isEqualTo(ConsoleError.Conflict("dup"))
        assertThat(failure("/html")).isEqualTo(ConsoleError.Server(502, null))
        assertThat(apiCallUnit { client.post("/none") }).isEqualTo(Outcome.Success(Unit))
        assertThat(apiCallUnit { client.post("/html") }).isEqualTo(Outcome.Failure(ConsoleError.Server(502, null)))
        assertThat(apiCall<SummaryDto> { client.get("/down") }).isEqualTo(Outcome.Failure(ConsoleError.Network))
    }

    @Test
    fun `session establish persists refresh and exposes state`() = runTest {
        val store = InMemoryTokenStore()
        val session = SessionManager(store, { RefreshResult.Unavailable }, ServerEndpoint())

        session.establish("https://rf.local", "ivanova", tokens(1))

        assertThat(session.accessToken()).isEqualTo("access-1")
        assertThat(session.state.value).isEqualTo(SessionState.LoggedIn("https://rf.local", "ivanova"))
        assertThat(store.load()).isEqualTo(StoredSession("https://rf.local", "ivanova", "refresh-1"))
        assertThat(session.currentRefreshToken()).isEqualTo("refresh-1")
        session.end()
        assertThat(session.state.value).isEqualTo(SessionState.LoggedOut)
        assertThat(store.load()).isNull()
        assertThat(session.accessToken()).isNull()
    }

    @Test
    fun `restore reads store and sets endpoint, empty store stays logged out`() = runTest {
        val endpoint = ServerEndpoint()
        val empty = SessionManager(InMemoryTokenStore(), { RefreshResult.Unavailable }, endpoint)
        assertThat(empty.restore()).isEqualTo(SessionState.LoggedOut)

        val store = InMemoryTokenStore(StoredSession("https://rf.local", "u", "r1"))
        val saved = SessionManager(store, { RefreshResult.Ok(tokens(2)) }, endpoint)
        assertThat(saved.restore()).isEqualTo(SessionState.LoggedIn("https://rf.local", "u"))
        assertThat(endpoint.baseUrl).isEqualTo("https://rf.local")
        assertThat(saved.accessToken()).isNull()
        assertThat(saved.refresh(null)).isEqualTo(RefreshOutcome.Refreshed("access-2"))
    }

    @Test
    fun `refresh without refresh token ends the session`() = runTest {
        val session = SessionManager(InMemoryTokenStore(), { RefreshResult.Unavailable }, ServerEndpoint())
        assertThat(session.refresh(null)).isEqualTo(RefreshOutcome.SessionEnded)
    }

    @Test
    fun `refresh rejected logs out, unavailable keeps the session, new pair is saved before use`() = runTest {
        val store = InMemoryTokenStore()
        var answer: RefreshResult = RefreshResult.Unavailable
        val session = SessionManager(store, { answer }, ServerEndpoint())
        session.establish("https://rf.local", "u", tokens(1))

        assertThat(session.refresh("access-1")).isEqualTo(RefreshOutcome.Unavailable)
        assertThat(session.state.value).isInstanceOf(SessionState.LoggedIn::class.java)

        answer = RefreshResult.Ok(tokens(2))
        assertThat(session.refresh("access-1")).isEqualTo(RefreshOutcome.Refreshed("access-2"))
        assertThat(store.load()!!.refreshToken).isEqualTo("refresh-2")
        assertThat(session.accessToken()).isEqualTo("access-2")

        answer = RefreshResult.Rejected
        assertThat(session.refresh("access-2")).isEqualTo(RefreshOutcome.SessionEnded)
        assertThat(session.state.value).isEqualTo(SessionState.LoggedOut)
        assertThat(store.load()).isNull()
    }

    @Test
    fun `parallel 401 produce one refresh and every request is retried with the new token`() = runTest {
        var refreshCalls = 0
        val refreshApi = RefreshApi { refreshCalls++; RefreshResult.Ok(tokens(2)) }
        val endpoint = ServerEndpoint("https://rf.local")
        val session = SessionManager(InMemoryTokenStore(), refreshApi, endpoint)
        session.establish("https://rf.local", "u", tokens(1))
        val seen = mutableListOf<String?>()
        val engine = MockEngine { req ->
            val auth = req.headers[HttpHeaders.Authorization]
            seen += auth
            if (auth == "Bearer access-2") {
                json("{}")
            } else {
                delay(100)
                respond("", HttpStatusCode.Unauthorized)
            }
        }
        val client = HttpClientFactory.authorized(engine, endpoint, session)

        val statuses = (1..5).map { async { client.get("/x").status } }.awaitAll()

        assertThat(statuses).containsExactly(*Array(5) { HttpStatusCode.OK })
        assertThat(refreshCalls).isEqualTo(1)
        assertThat(seen.count { it == "Bearer access-1" }).isEqualTo(5)
        assertThat(seen.count { it == "Bearer access-2" }).isEqualTo(5)
    }

    @Test
    fun `401 after a failed refresh is returned as is and session ends on rejection`() = runTest {
        val endpoint = ServerEndpoint("https://rf.local")
        val session = SessionManager(InMemoryTokenStore(), { RefreshResult.Rejected }, endpoint)
        session.establish("https://rf.local", "u", tokens(1))
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        val client = HttpClientFactory.authorized(engine, endpoint, session)

        assertThat(client.get("/x").status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(session.state.value).isEqualTo(SessionState.LoggedOut)
    }

    @Test
    fun `bare client sends no authorization`() = runTest {
        val endpoint = ServerEndpoint("https://rf.local")
        var header: String? = "unset"
        val engine = MockEngine { req -> header = req.headers[HttpHeaders.Authorization]; json("{}") }

        HttpClientFactory.bare(engine, endpoint).get("/x") { header(HttpHeaders.Accept, "application/json") }

        assertThat(header).isNull()
    }

    @Test
    fun `refresh api maps statuses`() = runTest {
        suspend fun run(status: HttpStatusCode, body: String = ""): RefreshResult {
            val ct = headersOf(HttpHeaders.ContentType, "application/json")
            return KtorRefreshApi(MockHttp.client { respond(body, status, ct) }).refresh("r")
        }
        val ok = run(HttpStatusCode.OK, Fixtures.text("rest-console-token-response"))
        assertThat(ok).isInstanceOf(RefreshResult.Ok::class.java)
        assertThat(run(HttpStatusCode.Unauthorized)).isEqualTo(RefreshResult.Rejected)
        assertThat(run(HttpStatusCode.BadRequest)).isEqualTo(RefreshResult.Rejected)
        assertThat(run(HttpStatusCode.BadGateway)).isEqualTo(RefreshResult.Unavailable)
        val failing = KtorRefreshApi(MockHttp.client { error("down") })
        assertThat(failing.refresh("r")).isEqualTo(RefreshResult.Unavailable)
    }
}
