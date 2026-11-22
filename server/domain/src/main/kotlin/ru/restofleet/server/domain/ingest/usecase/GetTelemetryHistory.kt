package ru.restofleet.server.domain.ingest.usecase

import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TimeRange
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.server.domain.ingest.TelemetryRepository
import ru.restofleet.server.domain.ingest.TelemetrySnapshot
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome

/** `GET /devices/{id}/telemetry?from=&to=` — от новых к старым. Выбор метрики (`metric=`) — проекция в api. */
class GetTelemetryHistory(
    private val devices: DeviceRepository,
    private val telemetry: TelemetryRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        id: DeviceId,
        range: TimeRange,
        page: PageRequest = PageRequest.of(),
    ): Outcome<List<TelemetrySnapshot>, ServerError> = tx.inTransaction {
        devices.findById(id)?.let { telemetry.telemetry(id, range, page) }
    }?.let { Outcome.Success(it) } ?: Outcome.Failure(ServerError.NotFound("device", id.value))
}
