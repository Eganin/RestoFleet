package ru.restofleet.server.domain.device.usecase

import ru.restofleet.server.domain.command.CommandReasons
import ru.restofleet.server.domain.command.CommandRepository
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.device.BrokerAccounts
import ru.restofleet.server.domain.device.CredentialRepository
import ru.restofleet.server.domain.device.Device
import ru.restofleet.server.domain.device.DeviceCredentialCache
import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.server.domain.device.DecommissionResult
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.device.MessagePublisher
import ru.restofleet.server.domain.presence.SessionCloseReason
import ru.restofleet.server.domain.presence.SessionRepository
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome

/**
 * Вывод устройства из эксплуатации (FR-BE-04, FR-BR-08). В транзакции: учётка DynSec отключается (сессия рвётся),
 * затем `devices.status = decommissioned`, `device_credentials.revoked_at`, открытая сессия закрывается, незавершённые
 * команды (`queued`/`delivered`) → `failed` с причиной `decommissioned` (R-8, реш. 307). Отказ брокера
 * откатывает транзакцию — в БД устройство остаётся активным, вызов можно повторить. После commit — очистка retained
 * `desired`/`status` и сброс кэша Basic (реш. 213). Повторный вызов для уже выведенного устройства идемпотентен и
 * дочищает то, что могло не выполниться в прошлый раз.
 */
class DecommissionDevice(
    private val devices: DeviceRepository,
    private val credentials: CredentialRepository,
    private val sessions: SessionRepository,
    private val commands: CommandRepository,
    private val broker: BrokerAccounts,
    private val publisher: MessagePublisher,
    private val cache: DeviceCredentialCache,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: DeviceId): Outcome<DecommissionResult, ServerError> {
        val outcome = tx.transactional<Device, ServerError> {
            val device = devices.findById(id)
                ?: return@transactional Outcome.Failure(ServerError.NotFound("device", id.value))
            when (val disabled = broker.disableDevice(id)) {
                is Outcome.Failure -> disabled
                is Outcome.Success -> {
                    val now = clock.nowMillis()
                    // R-8: команды выведенному устройству не выдаются и итога не ждут (повтор вызова дочищает)
                    commands.failUnfinished(id, CommandReasons.DECOMMISSIONED, now)
                    if (device.isDecommissioned) {
                        Outcome.Success(device)
                    } else {
                        devices.decommission(id, now)
                        credentials.revoke(id, now)
                        sessions.closeOpen(id, now, SessionCloseReason.DECOMMISSIONED)
                        Outcome.Success(device.copy(status = DeviceStatus.DECOMMISSIONED, decommissionedAt = now))
                    }
                }
            }
        }
        return when (outcome) {
            is Outcome.Failure -> outcome
            is Outcome.Success -> {
                cache.invalidate(id)
                val cleared = publisher.clearRetained(id) is Outcome.Success
                Outcome.Success(DecommissionResult(outcome.value, cleared))
            }
        }
    }
}
