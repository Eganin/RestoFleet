package ru.restofleet.agent.domain.port

import ru.restofleet.agent.domain.command.Level
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/** Показ сообщения персоналу; результат попадает в `CommandOutcome.Succeeded.result`. */
fun interface UserNotifier {
    suspend fun show(text: String, level: Level): Outcome<Map<String, String>, DomainError>
}
