package ru.restofleet.agent.data.command

import ru.restofleet.shared.kernel.DomainError

/** Ожидаемый отказ адаптера команд обычного режима; [code] уходит в `CommandOutcome.Failed.reason`. */
data class CommandError(override val code: String) : DomainError
