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
import kotlinx.serialization.Serializable
import ru.restofleet.server.api.RestaurantUseCases
import ru.restofleet.server.api.error.orThrow
import ru.restofleet.server.api.http.ItemsDto
import ru.restofleet.server.api.http.pathParam
import ru.restofleet.server.api.http.requireText
import ru.restofleet.server.domain.restaurant.Restaurant
import ru.restofleet.server.domain.restaurant.RestaurantDraft
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.server.domain.restaurant.RestaurantPatch

@Serializable
data class RestaurantDto(
    val id: String,
    val code: String,
    val name: String,
    val city: String,
    val address: String,
    val timezone: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** `POST /restaurants`. */
@Serializable
data class CreateRestaurantRequest(
    val code: String,
    val name: String,
    val city: String,
    val address: String = "",
    val timezone: String,
)

/** `PATCH /restaurants/{id}`: отсутствующее поле не меняется. */
@Serializable
data class UpdateRestaurantRequest(
    val code: String? = null,
    val name: String? = null,
    val city: String? = null,
    val address: String? = null,
    val timezone: String? = null,
)

/** Рестораны (FR-BE-01): в К — через Swagger UI и dev-seed (`1-coursework.md` 4.5). */
fun Route.restaurantRoutes(useCases: RestaurantUseCases) {
    route("/restaurants") {
        get {
            call.respond(ItemsDto(useCases.list().map { it.toDto() }))
        }
        post {
            val request = call.receive<CreateRestaurantRequest>()
            val created = useCases.create(request.toDraft()).orThrow()
            call.response.header(HttpHeaders.Location, "$RESTAURANTS_PATH/${created.id.value}")
            call.respond(HttpStatusCode.Created, created.toDto())
        }
        get("/{id}") {
            call.respond(useCases.get(RestaurantId(call.pathParam("id"))).orThrow().toDto())
        }
        patch("/{id}") {
            val request = call.receive<UpdateRestaurantRequest>()
            call.respond(useCases.update(RestaurantId(call.pathParam("id")), request.toPatch()).orThrow().toDto())
        }
    }
}

private const val RESTAURANTS_PATH = "/api/v1/restaurants"
private const val MAX_TEXT = 200

internal fun Restaurant.toDto() = RestaurantDto(id.value, code, name, city, address, timezone, createdAt, updatedAt)

private fun CreateRestaurantRequest.toDraft() = RestaurantDraft(
    code = requireText("code", code, MAX_TEXT),
    name = requireText("name", name, MAX_TEXT),
    city = requireText("city", city, MAX_TEXT),
    address = requireText("address", address, MAX_TEXT),
    timezone = requireText("timezone", timezone, MAX_TEXT),
)

private fun UpdateRestaurantRequest.toPatch() = RestaurantPatch(
    code = code?.let { requireText("code", it, MAX_TEXT) },
    name = name?.let { requireText("name", it, MAX_TEXT) },
    city = city?.let { requireText("city", it, MAX_TEXT) },
    address = address?.let { requireText("address", it, MAX_TEXT) },
    timezone = timezone?.let { requireText("timezone", it, MAX_TEXT) },
)
