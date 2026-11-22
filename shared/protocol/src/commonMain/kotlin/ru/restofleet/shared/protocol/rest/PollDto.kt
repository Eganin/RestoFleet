package ru.restofleet.shared.protocol.rest

import kotlinx.serialization.Serializable
import ru.restofleet.shared.protocol.dto.CommandRequestDto
import ru.restofleet.shared.protocol.dto.CommandResultDto
import ru.restofleet.shared.protocol.dto.EventDto
import ru.restofleet.shared.protocol.dto.ProbeReportDto
import ru.restofleet.shared.protocol.dto.TelemetryDto

/**
 * `POST /api/v1/poll/telemetry` (HTTP Basic учёткой устройства, P9; реш. 222): то же, что MQTT-сообщения `telemetry`,
 * `probe` и `events`, одним запросом. Опрос также отмечает присутствие устройства (реш. 214).
 */
@Serializable
data class PollTelemetryRequestDto(
    val telemetry: TelemetryDto,
    val probe: ProbeReportDto? = null,
    val events: List<EventDto> = emptyList(),
)

/** `GET /api/v1/poll/commands`: команды устройства в статусе `queued`; бэкенд помечает их доставленными. */
@Serializable
data class PollCommandsResponseDto(
    val commands: List<CommandRequestDto> = emptyList(),
)

/** `POST /api/v1/poll/commands/result`: тело совпадает с MQTT `cmd/res` (тот же контракт идемпотентности). */
typealias PollCommandResultDto = CommandResultDto

/** Пути REST-контракта устройства (ТЗ 8.2). */
object RestPaths {
    const val API_PREFIX = "/api/v1"
    const val PROVISION = "$API_PREFIX/provision"
    const val POLL_TELEMETRY = "$API_PREFIX/poll/telemetry"
    const val POLL_COMMANDS = "$API_PREFIX/poll/commands"
    const val POLL_COMMAND_RESULT = "$API_PREFIX/poll/commands/result"
}
