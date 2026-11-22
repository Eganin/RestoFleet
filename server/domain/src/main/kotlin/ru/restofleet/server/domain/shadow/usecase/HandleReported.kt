package ru.restofleet.server.domain.shadow.usecase

import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.server.domain.ingest.IngestOutcome
import ru.restofleet.server.domain.shadow.DefaultDesired
import ru.restofleet.server.domain.shadow.ReportedShadow
import ru.restofleet.server.domain.shadow.Shadow
import ru.restofleet.server.domain.shadow.ShadowRepository
import ru.restofleet.shared.kernel.Clock

/**
 * `shadow/reported` (FR-BE-20): сохраняет `reported`, `appliedVersion`, `appliedAt`, `errors`. Отчёт со старой
 * `appliedVersion`, чем сохранённая, отбрасывается (порядок доставки не гарантирован при передоставке). Неизвестное
 * или выведенное устройство — отбрасывается.
 */
class HandleReported(
    private val devices: DeviceRepository,
    private val shadows: ShadowRepository,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(report: ReportedShadow): IngestOutcome = tx.inTransaction {
        val device = devices.findById(report.deviceId)
        if (device == null || device.isDecommissioned) return@inTransaction IngestOutcome.DROPPED_UNKNOWN_DEVICE
        val now = clock.nowMillis()
        val current = shadows.findForUpdate(report.deviceId)
            ?: Shadow.initial(report.deviceId, DefaultDesired.document, now).copy(desiredVersion = 0)
                .also { shadows.insert(it) }
        if (report.appliedVersion < (current.reportedVersion ?: Long.MIN_VALUE)) {
            IngestOutcome.IGNORED_STALE
        } else {
            shadows.updateReported(report, now)
            IngestOutcome.APPLIED
        }
    }
}
