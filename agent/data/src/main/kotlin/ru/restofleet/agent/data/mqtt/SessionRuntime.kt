package ru.restofleet.agent.data.mqtt

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.agent.domain.usecase.PublishEvent
import ru.restofleet.shared.kernel.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Всё, что агент делает с брокером, под одним стартом и одной остановкой для сервиса: события жизненного цикла
 * (`boot` при запуске после перезагрузки, иначе `start`; `stop` до отключения), источники событий, циклы вокруг
 * сессии. Сама телеметрия публикуется тиком цикла M1. Остановка идёт в процессном scope: к `onDestroy` сервиса
 * `lifecycleScope` уже отменён, а `offline` нужно успеть отправить.
 */
class SessionRuntime(
    private val supervisor: SessionSupervisor,
    private val pump: EventPump,
    private val publishEvent: PublishEvent,
    private val clock: Clock,
    private val log: (String) -> Unit = {},
) {
    private val lock = Mutex()
    private var started = false

    suspend fun start(reason: String) = lock.withLock {
        if (started) return@withLock
        started = true
        val phase = if (reason == BOOT_REASON) AgentEvent.Lifecycle.Phase.BOOT else AgentEvent.Lifecycle.Phase.START
        supervisor.start()
        pump.start()
        lifecycle(phase)
    }

    suspend fun stop() = lock.withLock {
        if (!started) return@withLock
        started = false
        lifecycle(AgentEvent.Lifecycle.Phase.STOP)
        pump.stop()
        supervisor.stop()
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // событие не должно мешать старту и остановке
    private suspend fun lifecycle(phase: AgentEvent.Lifecycle.Phase) {
        try {
            publishEvent(AgentEvent.Lifecycle(clock.nowMillis(), phase))
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
        } catch (e: Exception) {
            log("lifecycle $phase failed: ${e::class.java.simpleName}")
        }
    }

    private companion object {
        const val BOOT_REASON = "boot"
    }
}
