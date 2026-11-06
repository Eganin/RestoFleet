package ru.restofleet.console.core.mvi

import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import ru.restofleet.console.core.mvi.contract.MviAction
import ru.restofleet.console.core.mvi.contract.MviEffect
import ru.restofleet.console.core.mvi.contract.MviIntent
import ru.restofleet.console.core.mvi.contract.MviState

/**
 * Базовая MVI-ViewModel для KMP: живёт в Essenty `InstanceKeeper`, [scope] отменяется в [onDestroy].
 * Стор приходит параметром конструктора, поэтому `init` наследника может вызывать [reduce].
 * Эффекты идут через `Channel(UNLIMITED)`: не теряются до подписки, подписчик должен быть один.
 */
abstract class BaseMviViewModel<S : MviState, U, I : MviIntent, A : MviAction, E : MviEffect>(
    private val store: MviStore<S, U, A>,
    protected val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : InstanceKeeper.Instance {

    private val effects = Channel<E>(Channel.UNLIMITED)

    val uiState: StateFlow<U> get() = store.uiState
    val effect: Flow<E> = effects.receiveAsFlow()

    protected val state: S get() = store.state

    protected fun reduce(action: A) {
        store.dispatch(action)
    }

    protected fun emitEffect(effect: E) {
        effects.trySend(effect)
    }

    abstract fun onIntent(intent: I)

    override fun onDestroy() {
        scope.cancel()
    }
}
