package ru.restofleet.console.core.mvi

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.mvi.contract.MviAction
import ru.restofleet.console.core.mvi.contract.MviEffect
import ru.restofleet.console.core.mvi.contract.MviIntent
import ru.restofleet.console.core.mvi.contract.MviState

class BaseMviViewModelTest {

    private data class S(val value: Int) : MviState

    private sealed interface Intent : MviIntent {
        data class Set(val value: Int) : Intent
        data class Notify(val tags: List<String>) : Intent
    }

    private data class SetValue(val value: Int) : MviAction

    private data class Effect(val tag: String) : MviEffect

    private class TestViewModel(scope: CoroutineScope) :
        BaseMviViewModel<S, Int, Intent, SetValue, Effect>(
            store = MviStore(S(0), { action: SetValue, _: S -> S(action.value) }) { it.value },
            scope = scope,
        ) {
        init {
            reduce(SetValue(INITIAL))
        }

        val currentState: S get() = state

        override fun onIntent(intent: Intent) {
            when (intent) {
                is Intent.Set -> reduce(SetValue(intent.value))
                is Intent.Notify -> intent.tags.forEach { emitEffect(Effect(it)) }
            }
        }

        companion object {
            const val INITIAL = 42
        }
    }

    private fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())

    @Test
    fun `init of subclass may call reduce`() {
        val vm = TestViewModel(scope())

        assertThat(vm.uiState.value).isEqualTo(TestViewModel.INITIAL)
        assertThat(vm.currentState).isEqualTo(S(TestViewModel.INITIAL))
    }

    @Test
    fun `intent changes uiState`() {
        val vm = TestViewModel(scope())

        vm.onIntent(Intent.Set(7))

        assertThat(vm.uiState.value).isEqualTo(7)
    }

    @Test
    fun `effects arrive in emit order`() = runTest {
        val vm = TestViewModel(scope())

        vm.effect.test {
            vm.onIntent(Intent.Notify(listOf("a", "b", "c")))
            assertThat(awaitItem()).isEqualTo(Effect("a"))
            assertThat(awaitItem()).isEqualTo(Effect("b"))
            assertThat(awaitItem()).isEqualTo(Effect("c"))
        }
    }

    @Test
    fun `effect sent before subscription is not lost`() = runTest {
        val vm = TestViewModel(scope())
        vm.onIntent(Intent.Notify(listOf("early")))

        vm.effect.test {
            assertThat(awaitItem()).isEqualTo(Effect("early"))
        }
    }

    @Test
    fun `onDestroy cancels scope`() {
        val scope = scope()
        val vm = TestViewModel(scope)
        assertThat(scope.isActive).isTrue()

        vm.onDestroy()

        assertThat(scope.isActive).isFalse()
    }
}
