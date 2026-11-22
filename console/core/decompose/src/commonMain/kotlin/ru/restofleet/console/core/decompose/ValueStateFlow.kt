package ru.restofleet.console.core.decompose

import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext

/** Адаптер `Value` → `StateFlow`; подписка снимается при уничтожении [lifecycle]. */
fun <T : Any> Value<T>.asStateFlow(lifecycle: Lifecycle): StateFlow<T> {
    val flow = MutableStateFlow(value)
    val cancellation = subscribe { flow.value = it }
    lifecycle.doOnDestroy { cancellation.cancel() }
    return flow
}

/** Скоуп компонента: отменяется вместе с его жизненным циклом. */
fun Lifecycle.componentScope(context: CoroutineContext): CoroutineScope {
    val scope = CoroutineScope(context + SupervisorJob())
    doOnDestroy { scope.cancel() }
    return scope
}
