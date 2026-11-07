package ru.restofleet.agent.domain.port

import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/** Звуковой сигнал «найти устройство»; в результат реализация кладёт, например, `ringerMode`. */
fun interface Beeper {
    suspend fun beep(durationMs: Long): Outcome<Map<String, String>, DomainError>
}
