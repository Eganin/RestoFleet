package ru.restofleet.server.domain.shadow.usecase

import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.server.domain.device.MessagePublisher
import ru.restofleet.server.domain.shadow.DefaultDesired
import ru.restofleet.server.domain.shadow.DesiredMerge
import ru.restofleet.server.domain.shadow.DesiredRules
import ru.restofleet.server.domain.shadow.Shadow
import ru.restofleet.server.domain.shadow.ShadowRepository
import ru.restofleet.server.domain.shadow.UpdatedShadow
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.shadow.ShadowDocument

/**
 * `PUT /devices/{id}/shadow` (FR-BE-20, 21): [patch] накладывается на текущий `desired` (реш. 237), результат
 * проверяется [DesiredRules]; изменение повышает версию. Retained `desired` публикуется **после** commit; при отказе
 * брокера тень сохранена, `published = false`, повтор — при подключении устройства. Патч без изменений версию не
 * повышает, но публикуется снова.
 */
class UpdateDesired(
    private val devices: DeviceRepository,
    private val shadows: ShadowRepository,
    private val publisher: MessagePublisher,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: DeviceId, patch: ShadowDocument): Outcome<UpdatedShadow, ServerError> {
        val outcome = tx.transactional<Shadow, ServerError> {
            val device = devices.findById(id)
                ?: return@transactional Outcome.Failure(ServerError.NotFound("device", id.value))
            if (device.isDecommissioned) {
                return@transactional Outcome.Failure(ServerError.DeviceDecommissioned(id.value))
            }
            val now = clock.nowMillis()
            val current = shadows.findForUpdate(id)
                ?: Shadow.initial(id, DefaultDesired.document, now).copy(desiredVersion = 0).also { shadows.insert(it) }
            val merged = DesiredMerge.merge(current.desired, patch)
            val invalid = DesiredRules.validate(merged)
            when {
                invalid != null -> Outcome.Failure(invalid)
                merged == current.desired && current.desiredVersion > 0 -> Outcome.Success(current)
                else -> {
                    val version = current.desiredVersion + 1
                    shadows.updateDesired(id, merged, version, now)
                    Outcome.Success(current.copy(desired = merged, desiredVersion = version, desiredUpdatedAt = now))
                }
            }
        }
        return when (outcome) {
            is Outcome.Failure -> outcome
            is Outcome.Success -> {
                val published = publisher.publishDesired(id, outcome.value.desiredState()) is Outcome.Success
                Outcome.Success(UpdatedShadow(outcome.value, published))
            }
        }
    }
}
