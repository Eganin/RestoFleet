package ru.restofleet.agent.data.probes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.agent.domain.model.ProbeTarget
import ru.restofleet.agent.domain.port.ProbeExecutor
import ru.restofleet.agent.domain.port.ShadowStore
import kotlin.coroutines.cancellation.CancellationException

/**
 * Минимальный цикл проб: цели из сохранённой тени, каждая опрашивается раз в свой `intervalSec` (первая проба сразу)
 * через [executor], результат уходит в [publish] (`PublishProbeResult`: офлайн ждёт в outbox). Смена набора целей
 * перезапускает цикл; прочие изменения тени (интервал телеметрии, пороги) его не трогают. Каждая проба и публикация
 * изолированы (канон 78: сбой не останавливает цикл, `Error` не ловится), в лог идёт только класс исключения.
 * `probe_state` (переходы по `failThreshold`) не делается (решение 3.6b).
 */
class ProbeLoop(
    private val scope: CoroutineScope,
    private val shadow: ShadowStore,
    private val executor: ProbeExecutor,
    private val publish: suspend (List<ProbeResult>) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { observeTargets() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Упавший поток тени подписывается заново через [RESTART_DELAY_MS]. */
    private suspend fun observeTargets() {
        while (currentCoroutineContext().isActive) {
            guarded("shadow") {
                shadow.observe()
                    .map { it?.probes.orEmpty() }
                    .distinctUntilChanged()
                    .collectLatest { targets -> runTargets(targets) }
            }
            delay(RESTART_DELAY_MS)
        }
    }

    private suspend fun runTargets(targets: List<ProbeTarget>) = coroutineScope {
        targets.forEach { target -> launch { runTarget(target) } }
    }

    private suspend fun runTarget(target: ProbeTarget) {
        val intervalMs = maxOf(target.intervalSec, MIN_INTERVAL_SEC) * MS_IN_SECOND
        while (currentCoroutineContext().isActive) {
            guarded("probe") { publish(listOf(executor.probe(target))) }
            delay(intervalMs)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // изоляция (канон 78); Error не ловится
    private suspend fun guarded(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            log("$name: stray cancellation")
        } catch (e: Exception) {
            log("$name failed: ${e::class.java.simpleName}")
        }
    }

    private companion object {
        const val MIN_INTERVAL_SEC = 5
        const val MS_IN_SECOND = 1_000L
        const val RESTART_DELAY_MS = 10_000L
    }
}
