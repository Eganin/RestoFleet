package ru.restofleet.server.api.poll

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import ru.restofleet.server.api.PollUseCases
import ru.restofleet.server.api.auth.device
import ru.restofleet.server.api.command.toRequestDto
import ru.restofleet.server.api.error.ApiException
import ru.restofleet.server.api.error.ErrorBody
import ru.restofleet.server.domain.ingest.IngestOutcome
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.rest.PollCommandsResponseDto

/**
 * REST-polling для эксперимента (FR-BE-54, P9; реш. 213, 214, 222): HTTP Basic учёткой устройства. Устройство пишет
 * и читает только своё (BOLA): данные тела привязываются к аутентифицированному `deviceId`, команды выдаются только
 * его, отчёт о чужой или неизвестной команде — 404 без уточнения. Ответы на запись — 204 без тела: в эксперименте
 * мерится протокол, а не JSON ответа.
 */
fun Route.pollRoutes(useCases: PollUseCases) {
    post("/poll/telemetry") {
        val id = call.device()
        val batch = useCases.decoder.telemetryBatch(id, call.receiveText()).orInvalidPayload()
        useCases.telemetry(id, batch)
        call.respond(HttpStatusCode.NoContent)
    }
    get("/poll/commands") {
        val commands = useCases.commands(call.device())
        call.respond(PollCommandsResponseDto(commands.map { it.toRequestDto() }))
    }
    post("/poll/commands/result") {
        val id = call.device()
        val report = useCases.decoder.commandReport(id, call.receiveText()).orInvalidPayload()
        when (useCases.result(report)) {
            IngestOutcome.DROPPED_UNKNOWN_DEVICE ->
                throw ApiException(HttpStatusCode.NotFound, ErrorBody("not_found", "command not found"))
            IngestOutcome.APPLIED, IngestOutcome.IGNORED_STALE -> call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun <T> Outcome<T, String>.orInvalidPayload(): T = when (this) {
    is Outcome.Success -> value
    is Outcome.Failure ->
        throw ApiException(HttpStatusCode.BadRequest, ErrorBody(INVALID_PAYLOAD, "payload rejected", reason = error))
}

private const val INVALID_PAYLOAD = "invalid_payload"
