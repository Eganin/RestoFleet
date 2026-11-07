package ru.restofleet.agent.domain.port

import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/**
 * Звуковой сигнал «найти устройство».
 *
 * Контракт: метод возвращается сразу после старта звука, а не по истечении `durationMs` (звук останавливается
 * реализацией самостоятельно). В результат кладутся параметры звука, например `ringerMode`.
 */
fun interface Beeper {
    suspend fun beep(durationMs: Long): Outcome<Map<String, String>, DomainError>
}
