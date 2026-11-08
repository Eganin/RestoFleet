package ru.restofleet.agent.domain.usecase

import ru.restofleet.agent.domain.capability.CapabilityReport
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.port.CapabilityReportWriter
import ru.restofleet.agent.domain.port.CheckResultJournal
import ru.restofleet.agent.domain.port.DeviceInfoSource
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/**
 * Прогоняет все проверки и передаёт отчёт writer'у; возвращает путь к файлу или ошибку записи. Проверки, которые
 * `RunCapabilityChecks` не исполняет сам (команды C1–C4, D2–D6), берутся из [journal], если их запускали руками.
 */
class ExportCapabilityReport(
    private val run: RunCapabilityChecks,
    private val info: DeviceInfoSource,
    private val writer: CapabilityReportWriter,
    private val clock: Clock,
    private val journal: CheckResultJournal,
) {
    suspend operator fun invoke(): Outcome<String, DomainError> {
        val manual = journal.recorded()
        val results = run(null).map { result ->
            if (result.status == CheckStatus.NOT_CHECKED) manual[result.id] ?: result else result
        }
        val report = CapabilityReport(info.deviceInfo(), clock.nowMillis(), results)
        return writer.write(report)
    }
}
