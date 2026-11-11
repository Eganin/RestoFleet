package ru.restofleet.server.domain.device.usecase

import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.device.DeviceDetails
import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.server.domain.ingest.TelemetryRepository
import ru.restofleet.server.domain.presence.SessionRepository
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome

/** `GET /devices/{id}` (текущее состояние). */
class GetDevice(
    private val devices: DeviceRepository,
    private val telemetry: TelemetryRepository,
    private val sessions: SessionRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(id: DeviceId): Outcome<DeviceDetails, ServerError> = tx.inTransaction {
        devices.findById(id)?.let { device ->
            DeviceDetails(device, telemetry.latest(id), sessions.latest(id))
        }
    }?.let { Outcome.Success(it) } ?: Outcome.Failure(ServerError.NotFound("device", id.value))
}
