package ru.restofleet.agent.domain.usecase

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.restofleet.agent.domain.error.MessageRejected
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.agent.domain.port.BrokerSession
import ru.restofleet.agent.domain.port.MessagePublisher
import ru.restofleet.agent.domain.port.Outbox
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome
import kotlin.coroutines.cancellation.CancellationException

/**
 * Публикует сообщение сразу или кладёт в [Outbox]. В outbox оно попадает, если сессия не `CONNECTED`, публикация не
 * удалась (`Failure` или исключение, решение 78) либо в outbox уже что-то лежит: порядок держит [FlushOutbox], иначе
 * после реконнекта `succeeded` ушёл бы раньше буферизованного `received` (P1). Буферизованное сообщение получает
 * QoS не ниже 1 (ТЗ 7.1: досылка буфера — QoS 1). Окончательный отказ брокера ([MessageRejected], например PUBACK
 * `0x87`) не буферизуется: повтор не поможет, а запись в outbox задержала бы все следующие сообщения до досылки; в лог
 * идут вид, код и счётчик, payload — никогда (решение 171). Вызовы сериализует `Mutex`: проверка `size()` и публикация
 * неатомарны, без него параллельный вызов обогнал бы только что упавший (сомнение 3.4). Настоящая отмена и `Error`
 * пробрасываются, сообщение не буферизуется.
 */
class PublishOrBuffer(
    private val session: BrokerSession,
    private val publisher: MessagePublisher,
    private val outbox: Outbox,
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private var droppedTotal = 0L

    suspend operator fun invoke(m: OutgoingMessage) = mutex.withLock {
        val canPublishNow = session.state.value == SessionState.CONNECTED && outbox.size() == 0
        val outcome = if (canPublishNow) publishOrNull(m) else null
        val rejected = (outcome as? Outcome.Failure)?.error as? MessageRejected
        when {
            outcome is Outcome.Success -> Unit
            rejected != null -> {
                droppedTotal++
                log(
                    "publish: rejected by broker, not buffered " +
                        "(kind=${m.kind}, reason=${rejected.code}, total=$droppedTotal)",
                )
            }
            else -> outbox.enqueue(m.copy(qos = maxOf(m.qos, BUFFERED_MIN_QOS)))
        }
    }

    /** `null` — публикация бросила (решение 78): сообщение буферизуется. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // изоляция сбоев, решение 78
    private suspend fun publishOrNull(m: OutgoingMessage): Outcome<Unit, DomainError>? =
        try {
            publisher.publish(m)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (e: Exception) {
            null
        }

    private companion object {
        const val BUFFERED_MIN_QOS = 1
    }
}
