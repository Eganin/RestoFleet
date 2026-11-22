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
import ru.restofleet.agent.domain.model.ProbeTarget
import ru.restofleet.agent.domain.port.ShadowStore
import ru.restofleet.shared.probes.ProbeSchedule
import kotlin.coroutines.cancellation.CancellationException

/**
 * Расписание плановых проб: цели из сохранённой тени, каждая проверяется раз в свой `intervalSec`, но не чаще
 * минимума [schedule] (`shared:probes`, НФТ «нагрузка проверок», реш. 228: 30 с, на стенде 5 с); первая проба сразу.
 * Сам прогон — [run] (`RunProbes`: автомат `up ↔ down`, `probe_state`, отчёт `probe`, офлайн — outbox). Смена набора
 * целей перезапускает цикл; прочие изменения тени (интервал телеметрии, пороги) его не трогают. Каждый прогон
 * изолирован (канон 78: сбой не останавливает цикл, `Error` не ловится), в лог идёт только класс исключения.
 */
class ProbeLoop(
    private val scope: CoroutineScope,
    private val shadow: ShadowStore,
    private val run: suspend (ProbeTarget) -> Unit,
    private val schedule: ProbeSchedule = ProbeSchedule(),
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
        val intervalMs = schedule.intervalMs(target.intervalSec)
        while (currentCoroutineContext().isActive) {
            guarded("probe") { run(target) }
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
        const val RESTART_DELAY_MS = 10_000L
    }
}
