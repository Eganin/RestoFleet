package ru.restofleet.console.core.mvi

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.mvi.contract.MviAction
import ru.restofleet.console.core.mvi.contract.MviState

class MviStoreTest {

    private data class CounterState(val count: Int) : MviState

    private sealed interface CounterAction : MviAction {
        data object Increment : CounterAction
        data object Noop : CounterAction
        data object SameValue : CounterAction
    }

    private val reducer = Reducer<CounterAction, CounterState> { action, state ->
        when (action) {
            CounterAction.Increment -> state.copy(count = state.count + 1)
            CounterAction.Noop -> state
            CounterAction.SameValue -> state.copy()
        }
    }

    private var projectCalls = 0

    private fun store() = MviStore(CounterState(0), reducer) { s: CounterState ->
        projectCalls++
        "count=${s.count}"
    }

    @Test
    fun `initial uiState is projection of initial state`() {
        val store = store()

        assertThat(store.uiState.value).isEqualTo("count=0")
        assertThat(store.state).isEqualTo(CounterState(0))
    }

    @Test
    fun `uiState equals project of next state right after dispatch`() {
        val store = store()

        store.dispatch(CounterAction.Increment)

        assertThat(store.state).isEqualTo(CounterState(1))
        assertThat(store.uiState.value).isEqualTo("count=1")
    }

    @Test
    fun `project is not called when reducer returns same instance`() {
        val store = store()
        val callsAfterInit = projectCalls

        store.dispatch(CounterAction.Noop)

        assertThat(projectCalls).isEqualTo(callsAfterInit)
    }

    @Test
    fun `uiState does not emit again when next equals previous by value`() = runTest {
        val store = store()

        store.uiState.test {
            assertThat(awaitItem()).isEqualTo("count=0")
            store.dispatch(CounterAction.SameValue)
            expectNoEvents()
        }
    }

    @Test
    fun `uiState emits when state changes`() = runTest {
        val store = store()

        store.uiState.test {
            awaitItem()
            store.dispatch(CounterAction.Increment)
            assertThat(awaitItem()).isEqualTo("count=1")
        }
    }
}
