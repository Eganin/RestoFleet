package ru.restofleet.server.domain.command.usecase

import ru.restofleet.server.domain.command.Command
import ru.restofleet.server.domain.command.CommandRepository
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome

/** `GET /devices/{id}/commands` (FR-CN-07): история команд устройства, новые сначала; неизвестное устройство — 404. */
class ListDeviceCommands(
    private val devices: DeviceRepository,
    private val commands: CommandRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        id: DeviceId,
        page: PageRequest = PageRequest.of(),
    ): Outcome<List<Command>, ServerError> =
        tx.inTransaction {
            if (devices.findById(id) == null) null else commands.listByDevice(id, page)
        }?.let { Outcome.Success(it) } ?: Outcome.Failure(ServerError.NotFound("device", id.value))
}
