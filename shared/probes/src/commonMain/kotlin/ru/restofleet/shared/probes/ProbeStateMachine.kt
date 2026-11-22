package ru.restofleet.shared.probes

/** Состояние цели проверки; [code] — `probe_state.state` на проводе (`unknown` наружу не отправляется). */
enum class ProbeState(val code: String) {
    UNKNOWN("unknown"),
    UP("up"),
    DOWN("down"),
}

/**
 * Смена состояния цели — повод для события `probe_state` (FR-AG-34, 44). [failures] — неудач подряд на момент
 * перехода (у `UP` — 0), [error] — класс последней неудачи (у `UP` — `null`).
 */
data class Transition(
    val from: ProbeState,
    val to: ProbeState,
    val failures: Int,
    val error: ProbeErrorClass?,
)

/**
 * Автомат `up ↔ down` одной цели (FR-AG-34, реш. 226): начальное [ProbeState.UNKNOWN]; [failThreshold] неудач подряд
 * → [ProbeState.DOWN]; первый успех → [ProbeState.UP]. Переход возвращается только при смене состояния, в том числе
 * `UNKNOWN → UP`: после перезапуска агента это снимает устаревший `down` на сервере. Порог меньше 1 считается 1.
 * Не потокобезопасен: один экземпляр на цель, вызовы из одного цикла.
 */
class ProbeStateMachine(failThreshold: Int = DEFAULT_FAIL_THRESHOLD) {
    val failThreshold: Int = failThreshold.coerceAtLeast(1)

    var state: ProbeState = ProbeState.UNKNOWN
        private set

    var consecutiveFailures: Int = 0
        private set

    fun onSuccess(): Transition? {
        consecutiveFailures = 0
        return moveTo(ProbeState.UP, error = null)
    }

    fun onFailure(error: ProbeErrorClass): Transition? {
        consecutiveFailures++
        return if (consecutiveFailures >= failThreshold) moveTo(ProbeState.DOWN, error) else null
    }

    /** Итог пробы целиком: неудача без класса считается [ProbeErrorClass.OTHER]. */
    fun record(ok: Boolean, error: ProbeErrorClass?): Transition? =
        if (ok) onSuccess() else onFailure(error ?: ProbeErrorClass.OTHER)

    private fun moveTo(target: ProbeState, error: ProbeErrorClass?): Transition? {
        if (state == target) return null
        val transition = Transition(state, target, consecutiveFailures, error)
        state = target
        return transition
    }

    companion object {
        /** K по умолчанию (FR-AG-34). */
        const val DEFAULT_FAIL_THRESHOLD = 3
    }
}
