package ru.restofleet.server.api

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.mockk.coVerify
import org.junit.Test

/** `POST /auth/logout` (реш. 417, 438): без JWT, всегда 204; тело — `{refreshToken}`. */
class LogoutRouteTest {
    private val kit = ApiTestKit()

    @Test
    fun `logout needs no access token and always answers 204`() = kit.run {
        val response = client.post("/api/v1/auth/logout") { json("""{"refreshToken":"rt-1"}""") }

        assertThat(response.status).isEqualTo(HttpStatusCode.NoContent)
        coVerify(exactly = 1) { kit.auth.logout("rt-1") }
    }

    @Test
    fun `malformed body is 400 and nothing is revoked`() = kit.run {
        val response = client.post("/api/v1/auth/logout") { json("""{"token":"rt-1"}""") }

        assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        coVerify(exactly = 0) { kit.auth.logout(any()) }
    }
}
