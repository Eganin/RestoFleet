package ru.restofleet.server.api

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.slf4j.LoggerFactory
import ru.restofleet.server.domain.auth.SessionTokens
import ru.restofleet.shared.kernel.Outcome
import java.sql.SQLTransientConnectionException

/** Единый формат ошибок, отсутствие входных данных в ответах и в журнале. */
class ErrorsAndLoggingTest {
    private val kit = ApiTestKit()
    private val token by lazy { kit.token() }
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private var level: Level? = null

    @Before
    fun captureLogs() {
        level = root.level
        root.level = Level.INFO
        appender.start()
        root.addAppender(appender)
    }

    @After
    fun releaseLogs() {
        root.detachAppender(appender)
        root.level = level
    }

    private fun logText(): String =
        appender.list.joinToString("\n") { it.formattedMessage + (it.throwableProxy?.message ?: "") }

    @Test
    fun `unhandled failure is 500 without its message in the body or the log`() = kit.run {
        coEvery { kit.restaurants.list() } throws IllegalStateException("SQL: INSERT password_hash='secret-hash'")

        val response = client.get("/api/v1/restaurants") { bearer(token) }

        assertThat(response.status).isEqualTo(HttpStatusCode.InternalServerError)
        assertThat(response.errorCode()).isEqualTo("internal")
        assertThat(response.bodyAsText()).doesNotContain("secret-hash")
        assertThat(logText()).contains("IllegalStateException")
        assertThat(logText()).doesNotContain("secret-hash")
    }

    @Test
    fun `lost database connection is 503`() = kit.run {
        coEvery { kit.restaurants.list() } throws IllegalStateException("tx", SQLTransientConnectionException("pool"))

        val response = client.get("/api/v1/restaurants") { bearer(token) }

        assertThat(response.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
        assertThat(response.errorCode()).isEqualTo("unavailable")
    }

    @Test
    fun `too large body is 413 and an unknown route is a json 404`() = kit.run {
        val huge = "{\"login\":\"" + "x".repeat(100 * 1024) + "\",\"password\":\"p\"}"

        val tooLarge = client.post("/api/v1/auth/login") { json(huge) }
        val unknown = client.get("/api/v1/nothing-here") { bearer(token) }

        assertThat(tooLarge.status).isEqualTo(HttpStatusCode.PayloadTooLarge)
        assertThat(tooLarge.errorCode()).isEqualTo("payload_too_large")
        assertThat(unknown.status).isEqualTo(HttpStatusCode.NotFound)
        assertThat(unknown.errorCode()).isEqualTo("not_found")
    }

    @Test
    fun `call log has method path and status but no authorization, query or body`() = kit.run {
        coEvery { kit.auth.login(any(), any()) } returns
            Outcome.Success(SessionTokens("issued-access", 1, "issued-refresh", 2))
        coEvery { kit.restaurants.list() } returns emptyList()

        client.post("/api/v1/auth/login") { json("""{"login":"admin","password":"pw-in-body-42"}""") }
        client.get("/api/v1/restaurants?code=query-value-42") { bearer(token) }

        val log = logText()
        assertThat(log).contains("200 POST /api/v1/auth/login")
        assertThat(log).contains("200 GET /api/v1/restaurants")
        listOf("pw-in-body-42", token, "issued-access", "issued-refresh", "query-value-42", "Bearer").forEach {
            assertThat(log).doesNotContain(it)
        }
    }
}
