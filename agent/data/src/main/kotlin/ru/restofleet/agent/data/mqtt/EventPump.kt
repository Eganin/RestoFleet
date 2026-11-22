package ru.restofleet.agent.data.mqtt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.restofleet.agent.data.collectors.PackageChange
import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.agent.domain.model.NetworkState
import ru.restofleet.agent.domain.port.WatchedApps
import ru.restofleet.agent.domain.usecase.PublishEvent
import ru.restofleet.agent.domain.usecase.PublishPackageChange
import ru.restofleet.shared.kernel.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Источники событий, которые есть потоками: смена сети (R14) идёт в топик `events` через [PublishEvent], пакеты
 * отслеживаемых приложений (R12) — через [PublishPackageChange] (дубль в окне 5 с отбрасывается, реш. 204).
 * `probe_state` шлёт `RunProbes`, пороги и передний план — `EvaluateThresholds` из цикла телеметрии. Сбой публикации
 * одного события не прерывает приём; упавший источник подписывается заново через [retryDelayMs]. В лог идёт только
 * класс исключения.
 */
class EventPump(
    private val scope: CoroutineScope,
    private val networkChanges: () -> Flow<NetworkState>,
    private val packageEvents: (Set<String>) -> Flow<PackageChange>,
    private val watched: WatchedApps,
    private val publishEvent: PublishEvent,
    private val publishPackageChange: PublishPackageChange,
    private val clock: Clock,
    private val retryDelayMs: Long = DEFAULT_RETRY_MS,
    private val log: (String) -> Unit = {},
) {
    private var jobs: List<Job> = emptyList()

    @Synchronized
    fun start() {
        if (jobs.any { it.isActive }) return
        jobs = listOf(
            scope.launch {
                resilient("network") {
                    networkChanges().collect { publish(AgentEvent.NetworkChanged(clock.nowMillis(), it)) }
                }
            },
            scope.launch {
                resilient("packages") {
                    packageEvents(watched.packages()).collect { change ->
                        guarded("publish PackageChanged") {
                            publishPackageChange(
                                change.pkg,
                                AgentEvent.PackageChanged.Change.valueOf(change.change.name),
                                change.versionCode,
                            )
                        }
                    }
                }
            },
        )
    }

    @Synchronized
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    private suspend fun publish(event: AgentEvent) = guarded("publish ${event::class.java.simpleName}") {
        publishEvent(event)
    }

    private suspend fun resilient(name: String, block: suspend () -> Unit) {
        while (currentCoroutineContext().isActive) {
            guarded(name, block)
            delay(retryDelayMs)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // изоляция источника (канон 78); Error не ловится
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

    companion object {
        const val DEFAULT_RETRY_MS = 10_000L
    }
}
