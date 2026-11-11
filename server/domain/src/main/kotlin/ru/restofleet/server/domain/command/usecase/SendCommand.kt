package ru.restofleet.server.domain.command.usecase

import ru.restofleet.server.domain.command.Command
import ru.restofleet.server.domain.command.CommandReasons
import ru.restofleet.server.domain.command.CommandRepository
import ru.restofleet.server.domain.command.CommandRequest
import ru.restofleet.server.domain.command.CommandStatus
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.server.domain.device.MessagePublisher
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.IdGenerator
import ru.restofleet.shared.kernel.Outcome

/**
 * Команда устройству (FR-BE-40). Команда записывается `queued` с `expiresAt = now + ttl`, после commit публикуется
 * `cmd/req` (Response Topic, Correlation Data = `cmdId`, Message Expiry = ttl). Офлайн-устройству брокер доставит
 * её при подключении (постоянная сессия, FR-BR-07). Отказ брокера: команда завершается `failed/broker_unavailable`
 * (реш. 238), результат — [ServerError.BrokerUnavailable].
 */
class SendCommand(
    private val devices: DeviceRepository,
    private val commands: CommandRepository,
    private val publisher: MessagePublisher,
    private val tx: TransactionRunner,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend operator fun invoke(request: CommandRequest): Outcome<Command, ServerError> {
        when {
            !CommandRequest.TYPE.matches(request.type) -> ServerError.Validation("type", "invalid")
            request.ttlSec !in CommandRequest.TTL_SEC -> ServerError.Validation("ttlSec", "out_of_range")
            else -> null
        }?.let { return Outcome.Failure(it) }
        val stored = tx.transactional<Command, ServerError> {
            val device = devices.findById(request.deviceId)
                ?: return@transactional Outcome.Failure(ServerError.NotFound("device", request.deviceId.value))
            if (device.isDecommissioned) {
                return@transactional Outcome.Failure(ServerError.DeviceDecommissioned(request.deviceId.value))
            }
            val now = clock.nowMillis()
            val command = Command(
                cmdId = "$CMD_PREFIX${ids.newId()}",
                deviceId = request.deviceId,
                type = request.type,
                params = request.params,
                status = CommandStatus.QUEUED,
                createdAt = now,
                expiresAt = now + request.ttlSec * MS_PER_SEC,
                deliveredAt = null,
                finishedAt = null,
                result = null,
                reason = null,
            )
            commands.insert(command)
            Outcome.Success(command)
        }
        return if (stored is Outcome.Success) publish(stored.value) else stored
    }

    private suspend fun publish(command: Command): Outcome<Command, ServerError> =
        when (val published = publisher.publishCommand(command)) {
            is Outcome.Success -> Outcome.Success(command)
            is Outcome.Failure -> {
                tx.inTransaction {
                    commands.findForUpdate(command.cmdId)
                        ?.takeIf { it.status == CommandStatus.QUEUED }
                        ?.let {
                            commands.update(
                                it.copy(
                                    status = CommandStatus.FAILED,
                                    finishedAt = clock.nowMillis(),
                                    reason = CommandReasons.BROKER_UNAVAILABLE,
                                ),
                            )
                        }
                }
                published
            }
        }

    private companion object {
        const val CMD_PREFIX = "c-"
        const val MS_PER_SEC = 1_000L
    }
}
