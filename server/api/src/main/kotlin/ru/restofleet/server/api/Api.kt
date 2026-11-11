package ru.restofleet.server.api

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import org.slf4j.event.Level
import ru.restofleet.server.api.auth.AuthProviders
import ru.restofleet.server.api.auth.installSecurity
import ru.restofleet.server.api.auth.loginRoute
import ru.restofleet.server.api.auth.refreshRoute
import ru.restofleet.server.api.command.commandRoutes
import ru.restofleet.server.api.device.deviceRoutes
import ru.restofleet.server.api.error.installErrorHandling
import ru.restofleet.server.api.health.healthRoute
import ru.restofleet.server.api.http.clientKey
import ru.restofleet.server.api.poll.pollRoutes
import ru.restofleet.server.api.provisioning.provisionRoute
import ru.restofleet.server.api.provisioning.tokenRoutes
import ru.restofleet.server.api.restaurant.restaurantRoutes
import ru.restofleet.server.api.shadow.shadowRoutes
import ru.restofleet.shared.protocol.RfJson
import kotlin.time.Duration.Companion.milliseconds

/** Пути верхнего уровня. Префикс API — тот же, что в `RestPaths` протокола (`/api/v1`). */
object ApiPaths {
    const val PREFIX = "/api/v1"
    const val HEALTH = "/health"
    const val SWAGGER = "swagger"
    const val OPENAPI_RESOURCE = "openapi/documentation.yaml"
}

internal object RateLimits {
    val LOGIN = RateLimitName("login")
    val PROVISION = RateLimitName("provision")
}

/**
 * REST API бэкенда (план 2.3): JSON по правилам протокола (`RfJson`), лимит тела, журнал вызовов без заголовков и тел,
 * единый формат ошибок, JWT (консоль/Swagger) и Basic (устройства), rate limit на вход и провижининг, Swagger UI.
 * Use cases — параметром [useCases] (Koin их собирает в `server:app`, здесь Koin нет).
 */
fun Application.restofleetApi(useCases: ApiUseCases, settings: ApiSettings) {
    install(ContentNegotiation) { json(RfJson) }
    install(RequestBodyLimit) { bodyLimit { settings.maxBodyBytes } }
    installCallLogging()
    installErrorHandling()
    installSecurity(settings.jwt, useCases.poll.authenticate)
    installRateLimits(settings.rateLimit)
    routing {
        healthRoute(ApiPaths.HEALTH, useCases.health)
        swaggerUI(path = ApiPaths.SWAGGER, swaggerFile = ApiPaths.OPENAPI_RESOURCE)
        route(ApiPaths.PREFIX) {
            healthRoute(ApiPaths.HEALTH, useCases.health)
            rateLimit(RateLimits.LOGIN) { loginRoute(useCases.auth) }
            refreshRoute(useCases.auth)
            rateLimit(RateLimits.PROVISION) { provisionRoute(useCases.provisioning) }
            authenticate(AuthProviders.DEVICE) { pollRoutes(useCases.poll) }
            authenticate(AuthProviders.ADMIN) {
                restaurantRoutes(useCases.restaurants)
                deviceRoutes(useCases.devices)
                shadowRoutes(useCases.shadows)
                commandRoutes(useCases.commands)
                tokenRoutes(useCases.provisioning)
            }
        }
    }
}

/**
 * Одна строка на вызов: статус, метод, путь **без** query, время. Заголовки (`Authorization`) и тела не пишутся;
 * `/health` (healthcheck compose каждые 10 с) — не пишется.
 */
private fun Application.installCallLogging() {
    install(CallLogging) {
        level = Level.INFO
        disableDefaultColors()
        filter { call -> !call.request.path().endsWith(ApiPaths.HEALTH) }
        format { call ->
            val status = call.response.status()?.value ?: 0
            "$status ${call.request.httpMethod.value} ${call.request.path()} ${call.processingTimeMillis()}ms"
        }
    }
}

private fun Application.installRateLimits(settings: RateLimitSettings) {
    install(RateLimit) {
        register(RateLimits.LOGIN) {
            rateLimiter(limit = settings.loginPerPeriod, refillPeriod = settings.periodMillis.milliseconds)
            requestKey { call -> call.clientKey() }
        }
        register(RateLimits.PROVISION) {
            rateLimiter(limit = settings.provisionPerPeriod, refillPeriod = settings.periodMillis.milliseconds)
            requestKey { call -> call.clientKey() }
        }
    }
}
