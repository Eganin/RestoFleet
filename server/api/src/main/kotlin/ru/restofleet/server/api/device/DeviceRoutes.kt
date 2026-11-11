package ru.restofleet.server.api.device

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import ru.restofleet.server.api.DeviceUseCases
import ru.restofleet.server.api.error.ApiException
import ru.restofleet.server.api.error.orThrow
import ru.restofleet.server.api.http.ItemsDto
import ru.restofleet.server.api.http.page
import ru.restofleet.server.api.http.pathDeviceId
import ru.restofleet.server.api.http.queryParam
import ru.restofleet.server.api.http.timeRange
import ru.restofleet.server.domain.device.DeviceFilter
import ru.restofleet.server.domain.device.DeviceRole
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.restaurant.RestaurantId

/**
 * Устройства (FR-BE-01, 04, 11): список с фильтрами, карточка, вывод из эксплуатации (`DELETE`, запись остаётся со
 * статусом `decommissioned`, реш. 248) и истории — от новых к старым, `[from, to)`, `limit`/`offset`.
 */
fun Route.deviceRoutes(useCases: DeviceUseCases) {
    route("/devices") {
        get {
            call.respond(ItemsDto(useCases.list(call.deviceFilter(), call.page()).map { it.toDto() }))
        }
        route("/{id}") {
            get {
                call.respond(useCases.get(call.pathDeviceId()).orThrow().toDto())
            }
            delete {
                call.respond(useCases.decommission(call.pathDeviceId()).orThrow().toDto())
            }
            get("/telemetry") {
                val metrics = call.metrics()
                val items = useCases.telemetry(call.pathDeviceId(), call.timeRange(), call.page()).orThrow()
                call.respond(ItemsDto(items.map { it.toDto(metrics) }))
            }
            get("/probes") {
                val target = call.queryParam("target")
                val items = useCases.probes(call.pathDeviceId(), call.timeRange(), target, call.page()).orThrow()
                call.respond(ItemsDto(items.map { it.toDto() }))
            }
            get("/events") {
                val type = call.queryParam("type")
                val items = useCases.events(call.pathDeviceId(), call.timeRange(), type, call.page()).orThrow()
                call.respond(ItemsDto(items.map { it.toDto() }))
            }
        }
    }
}

/** `?restaurant=&role=&status=`: неизвестная роль или статус — 400 (а не пустой список). */
private fun ApplicationCall.deviceFilter(): DeviceFilter = DeviceFilter(
    restaurantId = queryParam("restaurant")?.let(::RestaurantId),
    role = queryParam("role")?.let { DeviceRole.fromCode(it) ?: throw ApiException.validation("role", "unknown") },
    status = queryParam("status")
        ?.let { DeviceStatus.fromCode(it) ?: throw ApiException.validation("status", "unknown") },
)

/** `?metric=batteryLevel,tempC` — проекция полей (план 2.3); без параметра — все поля. */
private fun ApplicationCall.metrics(): Set<String> {
    val requested = queryParam("metric")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        ?: return TelemetryMetrics.ALL
    requested.firstOrNull { it !in TelemetryMetrics.ALL }?.let { throw ApiException.validation("metric", "unknown") }
    return requested
}
