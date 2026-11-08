package ru.restofleet.agent.domain.usecase

import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.command.Command
import ru.restofleet.agent.domain.command.CommandOutcome
import ru.restofleet.agent.domain.port.CheckResultJournal
import ru.restofleet.shared.kernel.Clock

/**
 * Исполняет команду как проверку матрицы ([id]: C1–C4, D2–D5) и записывает результат в журнал.
 * Итог `Succeeded` — OK, `Failed` — UNAVAILABLE, `Unsupported` — NOT_APPLICABLE для `requires_device_owner`,
 * иначе UNAVAILABLE.
 */
class RunCommandCheck(
    private val execute: ExecuteCommand,
    private val journal: CheckResultJournal,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: CheckId, command: Command): CheckResult {
        val now = clock.nowMillis()
        val result = when (val outcome = execute(command)) {
            is CommandOutcome.Succeeded -> CheckResult(
                id,
                CheckStatus.OK,
                outcome.result,
                "succeeded".takeIf { outcome.result.isEmpty() },
                now,
            )
            is CommandOutcome.Failed -> CheckResult(id, CheckStatus.UNAVAILABLE, emptyMap(), outcome.reason, now)
            is CommandOutcome.Unsupported -> {
                val status = if (outcome.reason == REQUIRES_DEVICE_OWNER) {
                    CheckStatus.NOT_APPLICABLE
                } else {
                    CheckStatus.UNAVAILABLE
                }
                CheckResult(id, status, emptyMap(), outcome.reason, now)
            }
        }
        journal.record(result)
        return result
    }

    private companion object {
        const val REQUIRES_DEVICE_OWNER = "requires_device_owner"
    }
}
