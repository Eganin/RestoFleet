package ru.restofleet.agent.data.collectors

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.shared.kernel.Clock
import kotlin.coroutines.CoroutineContext

class SubscriptionCheckTest {
    private val clock = Clock { 7L }

    private fun <T> kotlinx.coroutines.test.TestScope.check(
        events: kotlinx.coroutines.flow.Flow<T>,
        scope: CoroutineScope,
        describe: (T) -> String = { it.toString() },
    ) = SubscriptionCheck(CheckId.R12, clock, events, scope, describe = describe)

    @Test
    fun noEventsYetIsOkWithNote() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val result = check(MutableSharedFlow<String>(), scope).run()
        assertThat(result.status).isEqualTo(CheckStatus.OK)
        assertThat(result.raw).containsExactly("events", "0")
        assertThat(result.note).contains("no events yet")
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun runRightAfterCreationWaitsForSubscriptionStart() = runTest {
        // collect ещё не запущен (диспетчер не отработал), run() обязан дождаться старта, а не вернуть OK вслепую
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val result = check(MutableSharedFlow<String>(), scope).run()
        assertThat(result.status).isEqualTo(CheckStatus.OK)
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun subscriptionThatNeverStartsIsNotChecked() = runTest {
        val never = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
        }
        val result = check(MutableSharedFlow<String>(), CoroutineScope(never)).run()
        assertThat(result.status).isEqualTo(CheckStatus.NOT_CHECKED)
        assertThat(result.note).isEqualTo("subscription not started yet")
    }

    @Test
    fun lastEventIsReported() = runTest {
        val events = MutableSharedFlow<String>()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val check = check(events, scope) { "e:$it" }
        events.emit("a")
        events.emit("b")
        val result = check.run()
        assertThat(result.status).isEqualTo(CheckStatus.OK)
        assertThat(result.raw).containsExactly("events", "2", "last_event", "e:b", "last_at", "7")
        assertThat(result.note).isNull()
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun failedSubscriptionIsUnavailable() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val failing = flow<String> { throw SecurityException("denied") }
        val result = check(failing, scope).run()
        assertThat(result.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(result.note).isEqualTo("Failed: denied")
    }

    @Test
    fun failureWithoutMessageUsesClassName() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val failing = flow<String> { throw IllegalStateException() }
        assertThat(check(failing, scope).run().note).isEqualTo("Failed: IllegalStateException")
    }

    @Test
    fun cancelledSubscriptionIsNotOk() = runTest {
        val job = Job()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job)
        val check = check(MutableSharedFlow<String>(), scope)
        job.cancel()
        val result = check.run()
        assertThat(result.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(result.note).isEqualTo("Failed: subscription not active")
    }

    @Test
    fun completedSubscriptionIsNotOk() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val result = check(emptyFlow<String>(), scope).run()
        assertThat(result.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(result.note).isEqualTo("Failed: subscription not active")
        runCurrent()
    }
}
