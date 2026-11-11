package ru.restofleet.agent.domain.probe

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.agent.domain.model.ProbeTarget
import ru.restofleet.shared.probes.ProbeState
import ru.restofleet.shared.probes.ProbeStateMachine
import ru.restofleet.shared.probes.Transition

/** Последнее известное о цели проверки: состояние автомата и результат последней пробы (экран статуса, FR-AG-05). */
data class ProbeStatus(
    val targetId: String,
    val state: ProbeState,
    val consecutiveFailures: Int,
    val last: ProbeResult,
)

/**
 * Автоматы `up ↔ down` целей (FR-AG-34, реш. 226) — по одному [ProbeStateMachine] на `id`. Смена `failThreshold` цели
 * заводит новый автомат (начинает с `UNKNOWN`). Состояние в памяти: после перезапуска агента первый успех снова даёт
 * `UNKNOWN → UP`, и сервер снимает устаревший `down`. Не потокобезопасен: пишет только `RunProbes` под своим замком;
 * [statuses] читают все.
 */
class ProbeStates {
    private val machines = mutableMapOf<String, ProbeStateMachine>()
    private val _statuses = MutableStateFlow<List<ProbeStatus>>(emptyList())

    /** По одной записи на цель, по `id`; цели, убранные из тени, остаются до перезапуска (фильтрует читатель). */
    val statuses: StateFlow<List<ProbeStatus>> = _statuses.asStateFlow()

    /** Учитывает итог пробы; переход — только при смене состояния. */
    fun record(target: ProbeTarget, result: ProbeResult): Transition? {
        val threshold = target.failThreshold.coerceAtLeast(1)
        val machine = machines[target.id]?.takeIf { it.failThreshold == threshold }
            ?: ProbeStateMachine(threshold).also { machines[target.id] = it }
        val transition = machine.record(result.ok, result.error)
        val status = ProbeStatus(target.id, machine.state, machine.consecutiveFailures, result)
        _statuses.value = (_statuses.value.filterNot { it.targetId == target.id } + status).sortedBy { it.targetId }
        return transition
    }
}
