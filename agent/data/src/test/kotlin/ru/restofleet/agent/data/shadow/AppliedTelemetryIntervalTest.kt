package ru.restofleet.agent.data.shadow

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.model.DesiredShadow

class AppliedTelemetryIntervalTest {
    private fun shadow(sec: Int) = DesiredShadow(1, sec, emptyList(), emptyList())

    @Test
    fun withoutAShadowTheDefaultIsUsed() = runTest {
        assertThat(AppliedTelemetryInterval(StubShadowStore(), 30_000).millis()).isEqualTo(30_000)
    }

    @Test
    fun savedIntervalIsConvertedToMillis() = runTest {
        assertThat(AppliedTelemetryInterval(StubShadowStore(shadow(45)), 30_000).millis()).isEqualTo(45_000)
    }

    @Test
    fun newShadowChangesTheIntervalOnTheNextCall() = runTest {
        val store = StubShadowStore(shadow(45))
        val interval = AppliedTelemetryInterval(store, 30_000)
        interval.millis()

        store.save(shadow(20))

        assertThat(interval.millis()).isEqualTo(20_000)
    }

    @Test
    fun outOfRangeStoredValueFallsBackToTheDefault() = runTest {
        assertThat(AppliedTelemetryInterval(StubShadowStore(shadow(1)), 30_000).millis()).isEqualTo(30_000)
        assertThat(AppliedTelemetryInterval(StubShadowStore(shadow(100_000)), 30_000).millis()).isEqualTo(30_000)
    }

    @Test
    fun storeFailureFallsBackToTheDefault() = runTest {
        val store = StubShadowStore(failure = IllegalStateException("io"))

        assertThat(AppliedTelemetryInterval(store, 30_000).millis()).isEqualTo(30_000)
    }
}
