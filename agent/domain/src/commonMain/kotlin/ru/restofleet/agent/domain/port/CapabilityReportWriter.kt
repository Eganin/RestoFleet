package ru.restofleet.agent.domain.port

import ru.restofleet.agent.domain.capability.CapabilityReport
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/** Сохраняет отчёт и возвращает путь к файлу. */
fun interface CapabilityReportWriter {
    suspend fun write(report: CapabilityReport): Outcome<String, DomainError>
}
