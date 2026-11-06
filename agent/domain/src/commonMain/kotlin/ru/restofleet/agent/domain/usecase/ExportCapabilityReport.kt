package ru.restofleet.agent.domain.usecase

import ru.restofleet.agent.domain.capability.CapabilityReport
import ru.restofleet.agent.domain.port.CapabilityReportWriter
import ru.restofleet.agent.domain.port.DeviceInfoSource
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/** Прогоняет все проверки и передаёт отчёт writer'у; возвращает путь к файлу или ошибку записи. */
class ExportCapabilityReport(
    private val run: RunCapabilityChecks,
    private val info: DeviceInfoSource,
    private val writer: CapabilityReportWriter,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Outcome<String, DomainError> {
        val results = run(null)
        val report = CapabilityReport(info.deviceInfo(), clock.nowMillis(), results)
        return writer.write(report)
    }
}
