package ru.restofleet.agent.domain.usecase

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.restofleet.agent.domain.error.MessageRejected
import ru.restofleet.agent.domain.model.FlushResult
import ru.restofleet.agent.domain.model.FlushScope
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.agent.domain.port.MessagePublisher
import ru.restofleet.agent.domain.port.Outbox
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome
import kotlin.coroutines.cancellation.CancellationException

/**
 * Досылает outbox по одной записи, не быстрее [ratePerSec] в секунду. Запись удаляется сразу после своей успешной
 * публикации (при обрыве возможен дубль не более одной записи).
 *
 * - [FlushScope.ALL]: в порядке `peek`, пачками по [batch]; замок берётся на пачку и отпускается между пачками
 *   (решение 172), поэтому итоги команд не ждут конца многоминутной досылки;
 * - [FlushScope.COMMAND_RESULTS]: только итоги команд, одним захватом замка (их единицы) — уходят сразу или после
 *   текущей пачки работающей досылки.
 *
 * Окончательный отказ брокера ([MessageRejected], например PUBACK `0x87`) удаляет запись без повтора: в лог идут вид,
 * код и счётчик удалённых, payload — никогда (решение 171). Остальной сбой (`Failure` или исключение, решение 78)
 * останавливает досылку, запись остаётся. Параллельные вызовы не отправят одну запись дважды: `peek` и `remove` идут
 * под одним `Mutex`. Темп держится внутри вызова; на стыке двух параллельных вызовов возможна одна внеочередная
 * отправка. Настоящая отмена и `Error` пробрасываются.
 */
class FlushOutbox(
    private val publisher: MessagePublisher,
    private val outbox: Outbox,
    private val batch: Int = DEFAULT_BATCH,
    private val ratePerSec: Int = DEFAULT_RATE_PER_SEC,
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val intervalMs = (MS_IN_SECOND + ratePerSec - 1) / ratePerSec
    private var droppedTotal = 0L

    init {
        require(batch > 0) { "batch must be positive, was $batch" }
        require(ratePerSec > 0) { "ratePerSec must be positive, was $ratePerSec" }
    }

    suspend operator fun invoke(scope: FlushScope = FlushScope.ALL): FlushResult {
        var sent = 0
        var dropped = 0

        // Вызывается под замком; `true` — пачка ушла целиком, стоит взять следующую
        suspend fun sendBatch(): Boolean {
            val pending = when (scope) {
                FlushScope.ALL -> outbox.peek(batch)
                FlushScope.COMMAND_RESULTS -> outbox.peek(batch, MessageKind.COMMAND_RESULT)
            }
            for ((id, message) in pending) {
                if (sent + dropped > 0) delay(intervalMs)
                val failure = deliver(message)
                when {
                    failure == null -> sent++
                    failure is MessageRejected -> {
                        dropped++
                        logDropped(message, failure)
                    }
                    else -> return false
                }
                outbox.remove(listOf(id))
            }
            return pending.isNotEmpty()
        }

        when (scope) {
            FlushScope.ALL -> do {
                val more = mutex.withLock { sendBatch() }
            } while (more)
            FlushScope.COMMAND_RESULTS -> mutex.withLock {
                do {
                    val more = sendBatch()
                } while (more)
            }
        }
        return FlushResult(sent, outbox.size(), dropped)
    }

    private fun logDropped(message: OutgoingMessage, rejection: MessageRejected) {
        droppedTotal++
        log(
            "outbox: dropped record rejected by broker " +
                "(kind=${message.kind}, reason=${rejection.code}, total=$droppedTotal)",
        )
    }

    /** `null` — опубликовано; иначе причина сбоя (исключение — [PublishThrew], решение 78). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // изоляция сбоев, решение 78
    private suspend fun deliver(m: OutgoingMessage): DomainError? =
        try {
            when (val outcome = publisher.publish(m)) {
                is Outcome.Success -> null
                is Outcome.Failure -> outcome.error
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            PublishThrew
        } catch (e: Exception) {
            PublishThrew
        }

    private data object PublishThrew : DomainError {
        override val code: String = "publish_threw"
    }

    private companion object {
        const val DEFAULT_BATCH = 50
        const val DEFAULT_RATE_PER_SEC = 20
        const val MS_IN_SECOND = 1_000L
    }
}
