package ru.restofleet.console.core.mvi

import ru.restofleet.console.core.mvi.contract.MviAction
import ru.restofleet.console.core.mvi.contract.MviState

/**
 * Чистая функция перехода состояния: без I/O, времени и побочных эффектов.
 * Если действие ничего не меняет, возвращает тот же экземпляр [state] — это отключает пересчёт проекции.
 */
fun interface Reducer<A : MviAction, S : MviState> {
    fun reduce(action: A, state: S): S
}
