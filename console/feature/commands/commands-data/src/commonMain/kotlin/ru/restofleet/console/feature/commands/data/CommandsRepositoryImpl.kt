package ru.restofleet.console.feature.commands.data

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.feature.commands.domain.CommandRecord
import ru.restofleet.console.feature.commands.domain.CommandState
import ru.restofleet.console.feature.commands.domain.CommandType
import ru.restofleet.console.feature.commands.domain.CommandsRepository
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.kernel.map
import ru.restofleet.shared.protocol.rest.console.CommandDto

class CommandsRepositoryImpl(private val api: CommandsApi) : CommandsRepository {
    override suspend fun mode(deviceId: String): Outcome<String?, ConsoleError> = api.device(deviceId).map { it.device.mode }

    override suspend fun send(deviceId: String, type: CommandType, params: Map<String, String>) =
        api.send(deviceId, type.wire, params).map { it.toDomain() }

    override suspend fun history(deviceId: String, limit: Int) =
        api.history(deviceId, limit).map { dto -> dto.items.map { it.toDomain() } }

    override suspend fun status(cmdId: String) = api.status(cmdId).map { it.toDomain() }
}

internal fun CommandDto.toDomain() = CommandRecord(
    cmdId = cmdId,
    type = type,
    state = CommandState.fromWire(status),
    createdAt = createdAt,
    deliveredAt = deliveredAt,
    finishedAt = finishedAt,
    reason = reason,
    result = result.orEmpty(),
)

