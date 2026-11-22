package ru.restofleet.server.api.health

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import ru.restofleet.server.domain.health.usecase.CheckHealth

/** Без аутентификации: это и цель проверок агента на стенде. Нездоров — 503 (балансировщик и healthcheck compose). */
fun Route.healthRoute(path: String, checkHealth: CheckHealth) {
    get(path) {
        val report = checkHealth()
        val status = if (report.healthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        call.respond(status, HealthDto(if (report.healthy) "ok" else "degraded", report.components))
    }
}
