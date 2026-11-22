package ru.restofleet.server.domain.command.usecase

import ru.restofleet.server.domain.command.Command
import ru.restofleet.server.domain.command.CommandRepository
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.shared.kernel.Outcome

/** `GET /commands/{cmdId}`. */
class GetCommand(
    private val commands: CommandRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(cmdId: String): Outcome<Command, ServerError> =
        tx.inTransaction { commands.find(cmdId) }
            ?.let { Outcome.Success(it) }
            ?: Outcome.Failure(ServerError.NotFound("command", cmdId))
}
