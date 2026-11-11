package ru.restofleet.server.domain.polling.usecase

import ru.restofleet.server.domain.command.Command
import ru.restofleet.server.domain.command.CommandRepository
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.device.DeviceAnnouncement
import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.server.domain.device.DeviceTransport
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DeviceId

/**
 * `GET /poll/commands` (FR-BE-54): `queued` команды аутентифицированного устройства, ещё не истёкшие, атомарно
 * помечаются `delivered` и отдаются (REST-аналог `received`, P1). Опрос отмечает присутствие (реш. 214).
 */
class PollCommands(
    private val devices: DeviceRepository,
    private val commands: CommandRepository,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(deviceId: DeviceId): List<Command> = tx.inTransaction {
        val now = clock.nowMillis()
        devices.markOnline(deviceId, now, DeviceTransport.POLL, DeviceAnnouncement())
        commands.deliverQueued(deviceId, now)
    }
}
