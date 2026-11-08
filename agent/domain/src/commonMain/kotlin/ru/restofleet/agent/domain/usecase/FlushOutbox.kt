package ru.restofleet.agent.domain.usecase

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.restofleet.agent.domain.model.FlushResult
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.agent.domain.port.MessagePublisher
import ru.restofleet.agent.domain.port.Outbox
import ru.restofleet.shared.kernel.Outcome
import kotlin.coroutines.cancellation.CancellationException

/**
 * Досылает outbox в порядке `peek`: пачками по [batch], по одной записи, не быстрее [ratePerSec] в секунду. Запись
 * удаляется сразу после своей успешной публикации (при обрыве возможен дубль не более одной записи). Сбой
 * публикации (`Failure` или исключение, решение 78) останавливает досылку, запись остаётся. Параллельные вызовы
 * сериализуются `Mutex`, иначе одна запись ушла бы дважды. Настоящая отмена и `Error` пробрасываются.
 */
class FlushOutbox(
    private val publisher: MessagePublisher,
    private val outbox: Outbox,
    private val batch: Int = DEFAULT_BATCH,
    private val ratePerSec: Int = DEFAULT_RATE_PER_SEC,
) {
    private val mutex = Mutex()
    private val intervalMs = (MS_IN_SECOND + ratePerSec - 1) / ratePerSec

    init {
        require(batch > 0) { "batch must be positive, was $batch" }
        require(ratePerSec > 0) { "ratePerSec must be positive, was $ratePerSec" }
    }

    suspend operator fun invoke(): FlushResult = mutex.withLock {
        var sent = 0
        var pending = outbox.peek(batch)
        while (pending.isNotEmpty()) {
            for ((id, message) in pending) {
                if (sent > 0) delay(intervalMs)
                if (!publishSucceeded(message)) return@withLock FlushResult(sent, outbox.size())
                outbox.remove(listOf(id))
                sent++
            }
            pending = outbox.peek(batch)
        }
        FlushResult(sent, outbox.size())
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // изоляция сбоев, решение 78
    private suspend fun publishSucceeded(m: OutgoingMessage): Boolean =
        try {
            publisher.publish(m) is Outcome.Success
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            false
        } catch (e: Exception) {
            false
        }

    private companion object {
        const val DEFAULT_BATCH = 50
        const val DEFAULT_RATE_PER_SEC = 20
        const val MS_IN_SECOND = 1_000L
    }
}
