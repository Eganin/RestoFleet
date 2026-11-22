package ru.restofleet.server.api

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import ru.restofleet.server.api.auth.JwtAccessTokenIssuer
import ru.restofleet.server.api.ws.RealtimeHub
import ru.restofleet.server.api.ws.RealtimeSettings
import ru.restofleet.server.domain.realtime.EventBus
import ru.restofleet.server.domain.realtime.RealtimeEvent
import ru.restofleet.server.domain.auth.User
import ru.restofleet.server.domain.health.usecase.CheckHealth
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.SystemClock
import ru.restofleet.shared.kernel.UuidIdGenerator
import ru.restofleet.shared.protocol.RfJson

/**
 * Приложение API с фейковыми use cases (MockK, план 2.3): каждый тест задаёт ответы нужных use cases, остальные
 * при вызове падают (strict mock) — так видно, что маршрут не зовёт лишнего.
 */
class ApiTestKit(
    val rateLimit: RateLimitSettings = RateLimitSettings(loginPerPeriod = 100, provisionPerPeriod = 100),
    val realtimeSettings: RealtimeSettings = RealtimeSettings(),
) {
    val jwt = JwtSettings(secret = "test-secret-test-secret-test-secret-42")
    val settings = ApiSettings(jwt, rateLimit, maxBodyBytes = 64 * 1024, realtime = realtimeSettings)

    val auth = AuthUseCases(mockk(), mockk(), mockk(relaxed = true))
    val restaurants = RestaurantUseCases(mockk(), mockk(), mockk(), mockk())
    val devices = DeviceUseCases(mockk(), mockk(), mockk(), mockk(), mockk(), mockk())
    val shadows = ShadowUseCases(mockk(), mockk())
    val commands = CommandUseCases(mockk(), mockk(), mockk())
    val provisioning = ProvisioningUseCases(mockk(), mockk())
    val poll = PollUseCases(mockk(), mockk(), mockk(), mockk(), mockk())
    val health = mockk<CheckHealth>()
    val alerts = AlertUseCases(mockk(), mockk(), mockk(), mockk())
    val incidents = IncidentUseCases(mockk(), mockk(), mockk())
    val bus = TestEventBus()
    val clock = TestClock()
    val hub = RealtimeHub(bus, alerts.summary, clock, realtimeSettings) { bus.dropped }

    val useCases = ApiUseCases(
        auth, restaurants, devices, shadows, commands, provisioning, poll, health, alerts, incidents,
        RealtimeUseCases(hub, clock),
    )

    val admin = User("u-1", "admin", "hash", User.ROLE_ADMIN, 0)

    fun token(user: User = admin, clock: Clock = SystemClock, settings: JwtSettings = jwt): String =
        JwtAccessTokenIssuer(settings, clock, UuidIdGenerator()).issue(user).token

    fun run(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { restofleetApi(useCases, settings) }
        block()
    }
}

fun HttpRequestBuilder.bearer(token: String) = header(HttpHeaders.Authorization, "Bearer $token")

fun HttpRequestBuilder.json(body: String) {
    contentType(ContentType.Application.Json)
    setBody(body)
}

suspend fun HttpResponse.json(): JsonObject = RfJson.parseToJsonElement(bodyAsText()).jsonObject

/** Код ошибки из тела `{code, message}`. */
suspend fun HttpResponse.errorCode(): String? = (json()["code"] as? JsonPrimitive)?.content

/** Шина тестов: события идут в хаб WebSocket; [dropped] имитирует потери шины. */
class TestEventBus : EventBus {
    private val flow = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 1_024)

    @Volatile
    var dropped: Long = 0

    override fun publish(event: RealtimeEvent) {
        check(flow.tryEmit(event)) { "test bus overflow" }
    }

    override fun events(): Flow<RealtimeEvent> = flow
}

/** Часы тестов: [now] двигает тест (4401 по `exp`). */
class TestClock(@Volatile var now: Long = System.currentTimeMillis()) : Clock {
    override fun nowMillis(): Long = now
}
