package ru.restofleet.server.api.shadow

import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import ru.restofleet.server.api.ShadowUseCases
import ru.restofleet.server.api.error.orThrow
import ru.restofleet.server.api.http.parseJsonObject
import ru.restofleet.server.api.http.pathDeviceId
import ru.restofleet.shared.protocol.ShadowJson

/** `GET` — тень; `PUT` — тело как merge-patch к `desired` (реш. 237), служебные ключи корня отбрасываются. */
fun Route.shadowRoutes(useCases: ShadowUseCases) {
    route("/devices/{id}/shadow") {
        get {
            call.respond(useCases.get(call.pathDeviceId()).orThrow().toDto())
        }
        put {
            val id = call.pathDeviceId()
            val patch = ShadowJson.fromJson(parseJsonObject(call.receiveText()))
            val updated = useCases.update(id, patch).orThrow()
            call.respond(updated.shadow.toDto().copy(published = updated.published))
        }
    }
}
