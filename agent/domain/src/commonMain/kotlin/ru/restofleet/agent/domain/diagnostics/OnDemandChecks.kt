package ru.restofleet.agent.domain.diagnostics

import ru.restofleet.agent.domain.command.CommandOutcome

/**
 * Внеочередные проверки по команде оператора: `run_probe` (FR-AG-64) и `collect_diagnostics` (FR-AG-62, реш. 221).
 * Расписание проб их не ограничивает (реш. 228). [ru.restofleet.agent.domain.usecase.ExecuteCommand] знает только
 * этот интерфейс; реализация — [DiagnosticsCommands].
 */
interface OnDemandChecks {
    suspend fun runProbe(targetId: String): CommandOutcome

    suspend fun collectDiagnostics(): CommandOutcome

    /** Внеочередных проверок нет (граф без MQTT-сессии, тесты): обе команды — `unsupported` / `not_available`. */
    object Unavailable : OnDemandChecks {
        private const val NOT_AVAILABLE = "not_available"

        override suspend fun runProbe(targetId: String): CommandOutcome = CommandOutcome.Unsupported(NOT_AVAILABLE)

        override suspend fun collectDiagnostics(): CommandOutcome = CommandOutcome.Unsupported(NOT_AVAILABLE)
    }
}
