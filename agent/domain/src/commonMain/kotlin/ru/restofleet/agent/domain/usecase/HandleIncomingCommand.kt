package ru.restofleet.agent.domain.usecase

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import ru.restofleet.agent.domain.command.CommandOutcome
import ru.restofleet.agent.domain.command.CommandStatus
import ru.restofleet.agent.domain.command.CommandTimeouts
import ru.restofleet.agent.domain.model.IncomingCommand
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.agent.domain.port.PayloadEncoder
import ru.restofleet.agent.domain.port.ProcessedCommands
import ru.restofleet.shared.kernel.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Идемпотентная обработка входящей команды. Повтор `cmdId` игнорируется без ответа. Иначе всегда сначала `received`
 * (P1), затем `markProcessed` и только потом исполнение (at-most-once для всех команд: после перезагрузки повтор
 * не исполнится; цена — при смерти процесса посреди исполнения итог теряется, сервер закроет команду по таймауту),
 * затем итог. Итог: `expired` при `expiresAt < now`, `invalid_params:<parseError>`, `unknown_type:<rawType>`, `timeout`
 * (потолок — [CommandTimeouts]), либо результат [ExecuteCommand]. Исключение любого порта или энкодера не роняет
 * обработчик (решение 78): `failed`/`exception:<Класс>`; настоящая отмена и `Error` пробрасываются. Команды
 * исполняются последовательно вызывающим.
 */
class HandleIncomingCommand(
    private val execute: ExecuteCommand,
    private val processed: ProcessedCommands,
    private val publish: PublishOrBuffer,
    private val encoder: PayloadEncoder,
    private val clock: Clock,
) {
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // изоляция сбоев, решение 78
    suspend operator fun invoke(cmd: IncomingCommand) {
        try {
            handle(cmd)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            reportException(cmd, e)
        } catch (e: Exception) {
            reportException(cmd, e)
        }
    }

    private suspend fun handle(cmd: IncomingCommand) {
        if (processed.isProcessed(cmd.cmdId)) return
        reply(cmd, CommandStatus.RECEIVED, null)
        processed.markProcessed(cmd.cmdId)
        val outcome = resolve(cmd)
        val status = when (outcome) {
            is CommandOutcome.Succeeded -> CommandStatus.SUCCEEDED
            is CommandOutcome.Failed -> CommandStatus.FAILED
            is CommandOutcome.Unsupported -> CommandStatus.UNSUPPORTED
        }
        reply(cmd, status, outcome)
    }

    private suspend fun resolve(cmd: IncomingCommand): CommandOutcome {
        val command = cmd.command
        return when {
            cmd.expiresAt != null && cmd.expiresAt < clock.nowMillis() -> CommandOutcome.Failed(REASON_EXPIRED)
            command == null && cmd.parseError != null ->
                CommandOutcome.Failed("$REASON_INVALID_PARAMS_PREFIX${cmd.parseError}")
            command == null -> CommandOutcome.Unsupported("$REASON_UNKNOWN_TYPE_PREFIX${cmd.rawType}")
            else -> withTimeoutOrNull(CommandTimeouts.forCommand(command)) { execute(command) }
                ?: CommandOutcome.Failed(REASON_TIMEOUT)
        }
    }

    private suspend fun reply(cmd: IncomingCommand, status: String, outcome: CommandOutcome?) {
        val now = clock.nowMillis()
        publish(
            OutgoingMessage(
                kind = MessageKind.COMMAND_RESULT,
                payload = encoder.commandResult(cmd.cmdId, status, outcome, now),
                qos = RESULT_QOS,
                correlationData = cmd.correlationData,
                responseTopic = cmd.responseTopic,
                createdAt = now,
            ),
        )
    }

    /** Последняя линия обороны: сам ответ об ошибке тоже может упасть (сломанный энкодер, outbox) — тогда молчим. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // изоляция сбоев, решение 78
    private suspend fun reportException(cmd: IncomingCommand, cause: Exception) {
        val failure = CommandOutcome.Failed("exception:${cause::class.simpleName ?: "Unknown"}")
        try {
            reply(cmd, CommandStatus.FAILED, failure)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
        } catch (e: Exception) {
            return
        }
    }

    private companion object {
        const val RESULT_QOS = 1
        const val REASON_EXPIRED = "expired"
        const val REASON_TIMEOUT = "timeout"
        const val REASON_INVALID_PARAMS_PREFIX = "invalid_params:"
        const val REASON_UNKNOWN_TYPE_PREFIX = "unknown_type:"
    }
}
