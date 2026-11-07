package ru.restofleet.agent.domain.command

/** Итог исполнения команды; уходит в `CommandResultDto` (`succeeded` / `failed` / `unsupported`). */
sealed interface CommandOutcome {
    data class Succeeded(val result: Map<String, String> = emptyMap()) : CommandOutcome
    data class Failed(val reason: String) : CommandOutcome
    data class Unsupported(val reason: String) : CommandOutcome
}
