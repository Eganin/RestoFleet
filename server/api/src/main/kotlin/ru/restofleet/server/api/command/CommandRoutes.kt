package ru.restofleet.server.api.command

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException
import ru.restofleet.server.api.CommandUseCases
import ru.restofleet.server.api.error.ApiException
import ru.restofleet.server.api.error.orThrow
import ru.restofleet.server.api.http.page
import ru.restofleet.server.api.http.parseJsonObject
import ru.restofleet.server.api.http.pathDeviceId
import ru.restofleet.server.api.http.pathParam
import ru.restofleet.server.domain.command.Command
import ru.restofleet.server.domain.command.CommandRequest
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.ShadowJson
import ru.restofleet.shared.protocol.dto.CommandRequestDto
import ru.restofleet.shared.protocol.rest.ApiErrorDto
import ru.restofleet.shared.protocol.rest.console.CommandDto
import ru.restofleet.shared.protocol.rest.console.ItemsDto
import ru.restofleet.shared.protocol.rest.console.SendCommandRequestDto

/**
 * Команды (FR-BE-40): команда записывается `queued` и публикуется в `cmd/req` (офлайн-устройству брокер доставит её
 * при подключении). Отказ брокера — 503, команда остаётся в истории `failed/broker_unavailable` (реш. 238).
 */
fun Route.commandRoutes(useCases: CommandUseCases) {
    post("/devices/{id}/commands") {
        val id = call.pathDeviceId()
        val request = try {
            RfJson.decodeFromJsonElement(SendCommandRequestDto.serializer(), parseJsonObject(call.receiveText()))
        } catch (_: SerializationException) {
            throw ApiException(HttpStatusCode.BadRequest, ApiErrorDto("bad_request", "malformed request body"))
        }
        val command = useCases.send(
            CommandRequest(id, request.type, ShadowJson.fromJson(request.params), request.ttlSec),
        ).orThrow()
        call.response.header(HttpHeaders.Location, "/api/v1/commands/${command.cmdId}")
        call.respond(HttpStatusCode.Created, command.toDto())
    }
    get("/devices/{id}/commands") {
        val history = useCases.history(call.pathDeviceId(), call.page()).orThrow()
        call.respond(ItemsDto(history.map { it.toDto() }))
    }
    get("/commands/{cmdId}") {
        call.respond(useCases.get(call.pathParam("cmdId")).orThrow().toDto())
    }
}

internal fun Command.toDto() = CommandDto(
    cmdId = cmdId,
    deviceId = deviceId.value,
    type = type,
    params = ShadowJson.toJson(params),
    status = status.code,
    createdAt = createdAt,
    expiresAt = expiresAt,
    deliveredAt = deliveredAt,
    finishedAt = finishedAt,
    result = result,
    reason = reason,
)

/** Команда в формате `cmd/req` протокола — для `GET /poll/commands` (тот же контракт, что MQTT, реш. 222). */
internal fun Command.toRequestDto() = CommandRequestDto(
    cmdId = cmdId,
    type = type,
    params = ShadowJson.toJson(params),
    expiresAt = expiresAt,
)
