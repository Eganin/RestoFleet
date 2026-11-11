package ru.restofleet.server.domain.command.usecase

import ru.restofleet.server.domain.command.CommandReport
import ru.restofleet.server.domain.command.CommandRepository
import ru.restofleet.server.domain.command.CommandTransitions
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.ingest.IngestOutcome
import ru.restofleet.shared.kernel.Clock

/**
 * `cmd/res` (MQTT) и `POST /poll/commands/result` (REST): переход по [CommandTransitions], идемпотентно. Отчёт о
 * чужой команде (`cmdId` другого устройства) или о неизвестной отбрасывается — устройство пишет только в свой
 * `cmd/res`, а REST-вызов несёт аутентифицированный `deviceId` (BOLA).
 */
class HandleCommandResult(
    private val commands: CommandRepository,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(report: CommandReport): IngestOutcome = tx.inTransaction {
        val command = commands.findForUpdate(report.cmdId)
        if (command == null || command.deviceId != report.deviceId) {
            IngestOutcome.DROPPED_UNKNOWN_DEVICE
        } else {
            CommandTransitions.apply(command, report, clock.nowMillis())
                ?.let {
                    commands.update(it)
                    IngestOutcome.APPLIED
                }
                ?: IngestOutcome.IGNORED_STALE
        }
    }
}
