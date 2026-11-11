package ru.restofleet.server.domain.command.usecase

import ru.restofleet.server.domain.command.CommandRepository
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.shared.kernel.Clock

/** Планировщик (каждые 5 с, `server:app`): `queued`/`delivered` с истёкшим `expiresAt` → `expired` (P2). */
class ExpireCommands(
    private val commands: CommandRepository,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Int = tx.inTransaction { commands.expireDue(clock.nowMillis()) }
}
