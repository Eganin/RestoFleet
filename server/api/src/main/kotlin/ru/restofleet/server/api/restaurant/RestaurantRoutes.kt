package ru.restofleet.server.api.restaurant

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import ru.restofleet.server.api.RestaurantUseCases
import ru.restofleet.server.api.error.orThrow
import ru.restofleet.server.api.http.pathParam
import ru.restofleet.server.api.http.requireText
import ru.restofleet.server.domain.restaurant.Restaurant
import ru.restofleet.server.domain.restaurant.RestaurantDraft
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.server.domain.restaurant.RestaurantPatch
import ru.restofleet.shared.protocol.rest.console.CreateRestaurantRequestDto
import ru.restofleet.shared.protocol.rest.console.ItemsDto
import ru.restofleet.shared.protocol.rest.console.RestaurantDto
import ru.restofleet.shared.protocol.rest.console.UpdateRestaurantRequestDto

/** Рестораны (FR-BE-01): в К — через Swagger UI и dev-seed (`1-coursework.md` 4.5). */
fun Route.restaurantRoutes(useCases: RestaurantUseCases) {
    route("/restaurants") {
        get {
            call.respond(ItemsDto(useCases.list().map { it.toDto() }))
        }
        post {
            val request = call.receive<CreateRestaurantRequestDto>()
            val created = useCases.create(request.toDraft()).orThrow()
            call.response.header(HttpHeaders.Location, "$RESTAURANTS_PATH/${created.id.value}")
            call.respond(HttpStatusCode.Created, created.toDto())
        }
        get("/{id}") {
            call.respond(useCases.get(RestaurantId(call.pathParam("id"))).orThrow().toDto())
        }
        patch("/{id}") {
            val request = call.receive<UpdateRestaurantRequestDto>()
            call.respond(useCases.update(RestaurantId(call.pathParam("id")), request.toPatch()).orThrow().toDto())
        }
    }
}

private const val RESTAURANTS_PATH = "/api/v1/restaurants"
private const val MAX_TEXT = 200

internal fun Restaurant.toDto() = RestaurantDto(id.value, code, name, city, address, timezone, createdAt, updatedAt)

private fun CreateRestaurantRequestDto.toDraft() = RestaurantDraft(
    code = requireText("code", code, MAX_TEXT),
    name = requireText("name", name, MAX_TEXT),
    city = requireText("city", city, MAX_TEXT),
    address = requireText("address", address, MAX_TEXT),
    timezone = requireText("timezone", timezone, MAX_TEXT),
)

private fun UpdateRestaurantRequestDto.toPatch() = RestaurantPatch(
    code = code?.let { requireText("code", it, MAX_TEXT) },
    name = name?.let { requireText("name", it, MAX_TEXT) },
    city = city?.let { requireText("city", it, MAX_TEXT) },
    address = address?.let { requireText("address", it, MAX_TEXT) },
    timezone = timezone?.let { requireText("timezone", it, MAX_TEXT) },
)
