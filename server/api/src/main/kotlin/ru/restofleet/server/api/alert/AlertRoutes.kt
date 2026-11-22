package ru.restofleet.server.api.alert

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import ru.restofleet.server.api.AlertUseCases
import ru.restofleet.server.api.auth.JwtClaims
import ru.restofleet.server.api.error.ApiException
import ru.restofleet.server.api.error.orThrow
import ru.restofleet.server.api.http.booleanParam
import ru.restofleet.server.api.http.page
import ru.restofleet.server.api.http.pathParam
import ru.restofleet.server.api.http.queryParam
import ru.restofleet.server.domain.alert.AlertFilter
import ru.restofleet.server.domain.alert.AlertState
import ru.restofleet.server.domain.alert.RuleId
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.protocol.rest.console.AlertActionDto
import ru.restofleet.shared.protocol.rest.console.AlertActions
import ru.restofleet.shared.protocol.rest.console.ItemsDto

/**
 * Тревоги и сводка (FR-CN-02, 08; реш. 413, 416): список с фильтрами, подтверждение и закрытие. Автор действия —
 * `login` из JWT (реш. 446): его видно в консоли, а не id пользователя.
 */
fun Route.alertRoutes(useCases: AlertUseCases) {
    route("/alerts") {
        get {
            call.respond(ItemsDto(useCases.list(call.alertFilter(), call.page()).map { it.toDto() }))
        }
        patch("/{id}") {
            val id = call.pathParam("id")
            val action = call.receive<AlertActionDto>()
            val by = call.userLogin()
            val view = when (action.action) {
                AlertActions.ACKNOWLEDGE -> useCases.acknowledge(id, by, action.comment)
                AlertActions.RESOLVE -> useCases.resolve(id, by, action.comment)
                else -> throw ApiException.validation("action", "unknown")
            }.orThrow()
            call.respond(view.toDto())
        }
    }
    get("/summary") {
        call.respond(useCases.summary().toDto())
    }
}

/** `?state=&restaurant=&device=&rule=&includeSuppressed=`: неизвестное значение — 400. */
private fun ApplicationCall.alertFilter() = AlertFilter(
    state = queryParam("state")?.let { AlertState.fromCode(it) ?: throw ApiException.validation("state", "unknown") },
    restaurantId = queryParam("restaurant")?.let(::RestaurantId),
    deviceId = queryParam("device")?.let { DeviceId.parse(it) ?: throw ApiException.validation("device", "invalid") },
    rule = queryParam("rule")?.let { code ->
        RuleId.fromCode(code)?.takeIf { it in RuleId.ALERT_RULES } ?: throw ApiException.validation("rule", "unknown")
    },
    includeSuppressed = booleanParam("includeSuppressed") ?: false,
)

/** `login` из access JWT (маршрут под `authenticate(ADMIN)`). */
internal fun ApplicationCall.userLogin(): String =
    checkNotNull(principal<JWTPrincipal>()) { "route is not under user authentication" }
        .payload.getClaim(JwtClaims.LOGIN).asString()
