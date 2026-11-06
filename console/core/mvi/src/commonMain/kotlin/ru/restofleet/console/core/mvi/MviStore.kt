package ru.restofleet.console.core.mvi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.restofleet.console.core.mvi.contract.MviAction
import ru.restofleet.console.core.mvi.contract.MviState

/**
 * Владелец состояния экрана. Записи состояния наружу нет — только [dispatch], поэтому пересчёт
 * проекции [uiState] нельзя обойти. `dispatch` вызывается из одного потока (main) — как и ViewModel.
 */
class MviStore<S : MviState, U, A : MviAction>(
    initialState: S,
    private val reducer: Reducer<A, S>,
    private val project: (S) -> U,
) {
    var state: S = initialState
        private set

    private val _uiState = MutableStateFlow(project(initialState))
    val uiState: StateFlow<U> = _uiState.asStateFlow()

    /** Применяет [action]; `uiState.value` согласован с [state] сразу после возврата. */
    fun dispatch(action: A) {
        val previous = state
        val next = reducer.reduce(action, previous)
        if (next === previous || next == previous) return
        state = next
        _uiState.value = project(next)
    }
}
