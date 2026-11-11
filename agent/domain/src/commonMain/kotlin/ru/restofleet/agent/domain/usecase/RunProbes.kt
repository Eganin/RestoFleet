package ru.restofleet.agent.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.agent.domain.model.ProbeTarget
import ru.restofleet.agent.domain.port.ProbeExecutor
import ru.restofleet.agent.domain.probe.ProbeStates
import ru.restofleet.shared.probes.ProbeState

/**
 * Прогон проб доступности (FR-AG-31…35, 44): цели проверяются параллельно через [executor], каждый итог учитывает
 * автомат цели ([ProbeStates], `shared:probes`), и на каждую смену состояния сразу уходит событие `probe_state`
 * (`up`/`down`, класс последней ошибки, число неудач подряд), затем один отчёт `probe` на весь прогон. Без связи оба
 * сообщения ждут в outbox ([PublishOrBuffer], FR-AG-35). Плановые пробы (`ProbeSchedule` в цикле data-слоя) и
 * внеочередные (`run_probe`, `collect_diagnostics`) идут через один use case — автомат у цели один. Замок держит
 * порядок учёта: параллельные прогоны одной цели не перемешивают счётчик неудач.
 *
 * @see ru.restofleet.shared.probes.ProbeSchedule
 */
class RunProbes(
    private val executor: ProbeExecutor,
    private val states: ProbeStates,
    private val publishProbes: PublishProbeResult,
    private val publishEvent: PublishEvent,
) {
    private val mutex = Mutex()

    suspend operator fun invoke(targets: List<ProbeTarget>): List<ProbeResult> {
        if (targets.isEmpty()) return emptyList()
        val results = coroutineScope { targets.map { async { executor.probe(it) } }.awaitAll() }
        mutex.withLock {
            targets.zip(results).forEach { (target, result) ->
                states.record(target, result)?.let { transition ->
                    publishEvent(
                        AgentEvent.ProbeStateChanged(
                            ts = result.ts,
                            target = target.id,
                            up = transition.to == ProbeState.UP,
                            error = transition.error,
                            failures = transition.failures,
                        ),
                    )
                }
            }
        }
        publishProbes(results)
        return results
    }
}
