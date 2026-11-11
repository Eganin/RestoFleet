package ru.restofleet.server.domain.shadow.usecase

import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.server.domain.shadow.DefaultDesired
import ru.restofleet.server.domain.shadow.Shadow
import ru.restofleet.server.domain.shadow.ShadowRepository
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome

/**
 * `GET /devices/{id}/shadow`: `desired`, `reported` и `delta` ([Shadow.delta]). Без строки тени — `desired` по
 * умолчанию, версия 0.
 */
class GetShadow(
    private val devices: DeviceRepository,
    private val shadows: ShadowRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(id: DeviceId): Outcome<Shadow, ServerError> = tx.inTransaction {
        devices.findById(id)?.let { device ->
            shadows.find(id) ?: Shadow.initial(id, DefaultDesired.document, device.createdAt).copy(desiredVersion = 0)
        }
    }?.let { Outcome.Success(it) } ?: Outcome.Failure(ServerError.NotFound("device", id.value))
}
