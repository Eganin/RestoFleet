package ru.restofleet.server.api

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import ru.restofleet.server.domain.auth.SessionTokens
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome

class AuthAndSecurityTest {
    private val kit = ApiTestKit()
    private val tokens = SessionTokens("access-jwt", 1_000, "refresh-1", 2_000)

    /** Все маршруты консоли под JWT (метод, путь). */
    private val adminRoutes = listOf(
        HttpMethod.Get to "/api/v1/restaurants",
        HttpMethod.Post to "/api/v1/restaurants",
        HttpMethod.Get to "/api/v1/restaurants/r-1",
        HttpMethod.Patch to "/api/v1/restaurants/r-1",
        HttpMethod.Get to "/api/v1/devices",
        HttpMethod.Get to "/api/v1/devices/d-1",
        HttpMethod.Delete to "/api/v1/devices/d-1",
        HttpMethod.Get to "/api/v1/devices/d-1/telemetry",
        HttpMethod.Get to "/api/v1/devices/d-1/probes",
        HttpMethod.Get to "/api/v1/devices/d-1/events",
        HttpMethod.Get to "/api/v1/devices/d-1/shadow",
        HttpMethod.Put to "/api/v1/devices/d-1/shadow",
        HttpMethod.Post to "/api/v1/devices/d-1/commands",
        HttpMethod.Get to "/api/v1/commands/c-1",
        HttpMethod.Post to "/api/v1/provisioning/tokens",
        HttpMethod.Post to "/api/v1/provisioning/tokens:batch",
    )

    @Test
    fun `login returns both tokens and forbids caching`() = kit.run {
        coEvery { kit.auth.login("admin", "secret-password") } returns Outcome.Success(tokens)

        val response = client.post("/api/v1/auth/login") { json("""{"login":"admin","password":"secret-password"}""") }

        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(response.headers[HttpHeaders.CacheControl]).isEqualTo("no-store")
        val body = response.json()
        assertThat(body["accessToken"]!!.jsonPrimitive.content).isEqualTo("access-jwt")
        assertThat(body["refreshToken"]!!.jsonPrimitive.content).isEqualTo("refresh-1")
        assertThat(body["tokenType"]!!.jsonPrimitive.content).isEqualTo("Bearer")
    }

    @Test
    fun `wrong credentials are 401 without details`() = kit.run {
        coEvery { kit.auth.login(any(), any()) } returns Outcome.Failure(ServerError.Unauthorized)

        val response = client.post("/api/v1/auth/login") { json("""{"login":"admin","password":"nope"}""") }

        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(response.errorCode()).isEqualTo("unauthorized")
    }

    @Test
    fun `malformed login body is 400 and the body is not echoed`() = kit.run {
        val response = client.post("/api/v1/auth/login") { json("""{"login":"admin","password":"leaky-secret""") }

        assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(response.errorCode()).isEqualTo("bad_request")
        assertThat(response.json().toString()).doesNotContain("leaky-secret")
    }

    @Test
    fun `refresh rotates the pair and rejects a bad token with 401`() = kit.run {
        coEvery { kit.auth.refresh("refresh-1") } returns Outcome.Success(tokens.copy(refreshToken = "refresh-2"))
        coEvery { kit.auth.refresh("stolen") } returns Outcome.Failure(ServerError.Unauthorized)

        val ok = client.post("/api/v1/auth/refresh") { json("""{"refreshToken":"refresh-1"}""") }
        val bad = client.post("/api/v1/auth/refresh") { json("""{"refreshToken":"stolen"}""") }

        assertThat(ok.status).isEqualTo(HttpStatusCode.OK)
        assertThat(ok.json()["refreshToken"]!!.jsonPrimitive.content).isEqualTo("refresh-2")
        assertThat(bad.status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun `login is rate limited per client with 429 and retry-after`() = ApiTestKit(
        RateLimitSettings(loginPerPeriod = 2, provisionPerPeriod = 100, periodMillis = 60_000),
    ).let { limited ->
        limited.run {
            coEvery { limited.auth.login(any(), any()) } returns Outcome.Failure(ServerError.Unauthorized)
            val attempt = suspend {
                client.post("/api/v1/auth/login") {
                    headers.append(HttpHeaders.XForwardedFor, "203.0.113.7")
                    json("""{"login":"admin","password":"guess"}""")
                }
            }

            val statuses = List(3) { attempt().status }
            val limitedResponse = attempt()
            val otherClient = client.post("/api/v1/auth/login") {
                headers.append(HttpHeaders.XForwardedFor, "203.0.113.8")
                json("""{"login":"admin","password":"guess"}""")
            }

            assertThat(statuses).containsExactly(
                HttpStatusCode.Unauthorized,
                HttpStatusCode.Unauthorized,
                HttpStatusCode.TooManyRequests,
            ).inOrder()
            assertThat(limitedResponse.errorCode()).isEqualTo("rate_limited")
            assertThat(limitedResponse.headers[HttpHeaders.RetryAfter]).isNotNull()
            assertThat(otherClient.status).isEqualTo(HttpStatusCode.Unauthorized)
            coVerify(exactly = 3) { limited.auth.login(any(), any()) }
        }
    }

    @Test
    fun `failed device basic auth is limited per client with 429 and stops costing bcrypt`() = ApiTestKit(
        RateLimitSettings(loginPerPeriod = 100, provisionPerPeriod = 100, pollAuthFailuresPerPeriod = 2),
    ).let { limited ->
        limited.run {
            // R-7: считаются только ответы 401; успешные опросы того же клиента лимит не тратят
            coEvery { limited.poll.authenticate("d-1", "guess") } returns Outcome.Failure(ServerError.Unauthorized)
            coEvery { limited.poll.authenticate("d-1", "pw-1") } returns Outcome.Success(Samples.deviceId)
            coEvery { limited.poll.commands(Samples.deviceId) } returns emptyList()
            val poll: suspend (String, String) -> HttpResponse = { ip, password ->
                client.get("/api/v1/poll/commands") {
                    headers.append(HttpHeaders.XForwardedFor, ip)
                    basicAuth("d-1", password)
                }
            }

            val successes = List(3) { poll("203.0.113.7", "pw-1").status }
            val failures = List(2) { poll("203.0.113.7", "guess").status }
            val blocked = poll("203.0.113.7", "guess")
            val blockedWithPassword = poll("203.0.113.7", "pw-1")
            val otherClient = poll("203.0.113.8", "pw-1")

            assertThat(successes).containsExactly(HttpStatusCode.OK, HttpStatusCode.OK, HttpStatusCode.OK)
            assertThat(failures).containsExactly(HttpStatusCode.Unauthorized, HttpStatusCode.Unauthorized)
            assertThat(blocked.status).isEqualTo(HttpStatusCode.TooManyRequests)
            assertThat(blocked.errorCode()).isEqualTo("rate_limited")
            assertThat(blocked.headers[HttpHeaders.RetryAfter]).isNotNull()
            assertThat(blockedWithPassword.status).isEqualTo(HttpStatusCode.TooManyRequests)
            assertThat(otherClient.status).isEqualTo(HttpStatusCode.OK)
            // Заблокированный запрос до проверки пароля (bcrypt) не доходит
            coVerify(exactly = 2) { limited.poll.authenticate("d-1", "guess") }
            coVerify(exactly = 4) { limited.poll.authenticate("d-1", "pw-1") }
        }
    }

    @Test
    fun `every console route is 401 without a token and never reaches a use case`() = kit.run {
        adminRoutes.forEach { (method, path) ->
            val response = client.request(path) { this.method = method }
            assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(response.errorCode()).isEqualTo("unauthorized")
            assertThat(response.headers[HttpHeaders.WWWAuthenticate]).startsWith("Bearer")
        }
    }

    @Test
    fun `expired, foreign, wrong audience and non admin tokens are rejected`() = kit.run {
        val past = Clock { System.currentTimeMillis() - 16 * 60 * 1000 }
        val rejected = listOf(
            kit.token(clock = past),
            kit.token(settings = kit.jwt.copy(secret = "another-secret-another-secret-another")),
            kit.token(settings = kit.jwt.copy(audience = "someone-else")),
            kit.token(user = kit.admin.copy(role = "operator")),
            "not-a-jwt",
        )

        rejected.forEach { token ->
            val response = client.get("/api/v1/restaurants") { bearer(token) }
            assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        }
    }

    @Test
    fun `device polling requires basic credentials of a device`() = kit.run {
        coEvery { kit.poll.authenticate("d-1", "wrong") } returns Outcome.Failure(ServerError.Unauthorized)

        val anonymous = client.get("/api/v1/poll/commands")
        val wrong = client.get("/api/v1/poll/commands") { basicAuth("d-1", "wrong") }
        val adminToken = client.get("/api/v1/poll/commands") { bearer(kit.token()) }

        listOf(anonymous, wrong, adminToken).forEach {
            assertThat(it.status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(it.errorCode()).isEqualTo("unauthorized")
            assertThat(it.headers[HttpHeaders.WWWAuthenticate]).startsWith("Basic")
        }
    }

    @Test
    fun `device credentials do not open console routes`() = kit.run {
        val response = client.get("/api/v1/devices") { basicAuth("d-1", "device-password") }

        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun `a valid admin token passes authentication`() = kit.run {
        coEvery { kit.restaurants.list() } returns emptyList()

        val response = client.get("/api/v1/restaurants") { bearer(kit.token()) }

        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    }
}
