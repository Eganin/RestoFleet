package ru.restofleet.agent.data.collectors

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.shared.kernel.Clock

class SubscriptionCheckTest {
    private val clock = Clock { 7L }

    @Test
    fun noEventsYetIsOkWithNote() = runTest {
        val events = MutableSharedFlow<String>()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val check = SubscriptionCheck(CheckId.R12, clock, events, scope) { it }
        val result = check.run()
        assertThat(result.status).isEqualTo(CheckStatus.OK)
        assertThat(result.raw).containsExactly("events", "0")
        assertThat(result.note).contains("no events yet")
    }

    @Test
    fun lastEventIsReported() = runTest {
        val events = MutableSharedFlow<String>()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val check = SubscriptionCheck(CheckId.R14, clock, events, scope) { "e:$it" }
        events.emit("a")
        events.emit("b")
        val result = check.run()
        assertThat(result.status).isEqualTo(CheckStatus.OK)
        assertThat(result.raw).containsExactly("events", "2", "last_event", "e:b", "last_at", "7")
        assertThat(result.note).isNull()
    }

    @Test
    fun failedSubscriptionIsUnavailable() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val failing = flow<String> { throw SecurityException("denied") }
        val check = SubscriptionCheck(CheckId.R14, clock, failing, scope) { it }
        val result = check.run()
        assertThat(result.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(result.note).isEqualTo("Failed: denied")
    }

    @Test
    fun failureWithoutMessageUsesClassName() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val failing = flow<String> { throw IllegalStateException() }
        val check = SubscriptionCheck(CheckId.R14, clock, failing, scope) { it }
        assertThat(check.run().note).isEqualTo("Failed: IllegalStateException")
    }

    @Test
    fun cancellationIsNotAnError() = runTest {
        val job = kotlinx.coroutines.Job()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job)
        val check = SubscriptionCheck(CheckId.R14, clock, MutableSharedFlow<String>(), scope) { it }
        job.cancel()
        assertThat(check.run().status).isEqualTo(CheckStatus.OK)
        MutableSharedFlow<String>().test { cancel() }
    }
}
