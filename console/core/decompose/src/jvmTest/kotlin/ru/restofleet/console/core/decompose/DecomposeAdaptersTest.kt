package ru.restofleet.console.core.decompose

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.create
import com.arkivanov.essenty.lifecycle.destroy
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.isActive
import org.junit.Test

class DecomposeAdaptersTest {
    private class Vm : InstanceKeeper.Instance

    @Test
    fun `value is mirrored into state flow until lifecycle is destroyed`() {
        val lifecycle = LifecycleRegistry()
        lifecycle.create()
        val value = MutableValue(1)

        val flow = value.asStateFlow(lifecycle)
        value.value = 2
        assertThat(flow.value).isEqualTo(2)

        lifecycle.destroy()
        value.value = 3
        assertThat(flow.value).isEqualTo(2)
    }

    @Test
    fun `retained view model is created once per context`() {
        val ctx = DefaultComponentContext(LifecycleRegistry())
        var created = 0

        val first = ctx.retainedViewModel { created++; Vm() }
        val second = ctx.retainedViewModel { created++; Vm() }

        assertThat(second).isSameInstanceAs(first)
        assertThat(created).isEqualTo(1)
    }

    @Test
    fun `content component is implementable`() {
        val c = object : ContentComponent {
            @Composable
            override fun Content() = Unit
        }
        assertThat(c).isNotNull()
    }

    @Test
    fun `component scope is cancelled with the lifecycle`() {
        val lifecycle = LifecycleRegistry()
        lifecycle.create()
        val scope = lifecycle.componentScope(kotlinx.coroutines.Dispatchers.Unconfined)
        assertThat(scope.isActive).isTrue()

        lifecycle.destroy()

        assertThat(scope.isActive).isFalse()
    }
}
