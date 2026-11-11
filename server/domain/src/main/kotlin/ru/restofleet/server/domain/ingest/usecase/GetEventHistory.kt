package ru.restofleet.server.domain.ingest.usecase

import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TimeRange
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.server.domain.ingest.DeviceEvent
import ru.restofleet.server.domain.ingest.TelemetryRepository
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome

/** `GET /devices/{id}/events?from=&to=&type=` — от новых к старым. */
class GetEventHistory(
    private val devices: DeviceRepository,
    private val telemetry: TelemetryRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(
        id: DeviceId,
        range: TimeRange,
        type: String? = null,
        page: PageRequest = PageRequest.of(),
    ): Outcome<List<DeviceEvent>, ServerError> = tx.inTransaction {
        devices.findById(id)?.let { telemetry.events(id, range, type, page) }
    }?.let { Outcome.Success(it) } ?: Outcome.Failure(ServerError.NotFound("device", id.value))
}
