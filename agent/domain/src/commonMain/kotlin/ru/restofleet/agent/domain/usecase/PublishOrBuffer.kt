package ru.restofleet.agent.domain.usecase

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.agent.domain.port.BrokerSession
import ru.restofleet.agent.domain.port.MessagePublisher
import ru.restofleet.agent.domain.port.Outbox
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.shared.kernel.Outcome
import kotlin.coroutines.cancellation.CancellationException

/**
 * Публикует сообщение сразу или кладёт в [Outbox]. В outbox оно попадает, если сессия не `CONNECTED`, публикация не
 * удалась (`Failure` или исключение, решение 78) либо в outbox уже что-то лежит: порядок держит [FlushOutbox], иначе
 * после реконнекта `succeeded` ушёл бы раньше буферизованного `received` (P1). Буферизованное сообщение получает
 * QoS не ниже 1 (ТЗ 7.1: досылка буфера — QoS 1). Настоящая отмена и `Error` пробрасываются, сообщение не буферизуется.
 */
class PublishOrBuffer(
    private val session: BrokerSession,
    private val publisher: MessagePublisher,
    private val outbox: Outbox,
) {
    suspend operator fun invoke(m: OutgoingMessage) {
        val canPublishNow = session.state.value == SessionState.CONNECTED && outbox.size() == 0
        if (canPublishNow && publishSucceeded(m)) return
        outbox.enqueue(m.copy(qos = maxOf(m.qos, BUFFERED_MIN_QOS)))
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
        const val BUFFERED_MIN_QOS = 1
    }
}
