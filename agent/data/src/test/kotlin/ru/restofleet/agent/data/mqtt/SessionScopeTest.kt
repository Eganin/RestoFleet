package ru.restofleet.agent.data.mqtt

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

class SessionScopeTest {
    @Test
    fun uncaughtExceptionIsLoggedByClassAndTheScopeKeepsWorking() = runTest {
        val log = mutableListOf<String>()
        val scope = sessionScope(StandardTestDispatcher(testScheduler)) { log += it }

        scope.launch { throw IllegalStateException("secret detail") }
        advanceUntilIdle()
        var ranAfter = false
        scope.launch { ranAfter = true }
        advanceUntilIdle()

        assertThat(log).hasSize(1)
        assertThat(log.single()).contains("IllegalStateException")
        assertThat(log.single()).doesNotContain("secret detail")
        assertThat(scope.isActive).isTrue()
        assertThat(ranAfter).isTrue()
        scope.cancel()
    }

    @Test
    fun errorIsNotSwallowedByTheHandler() {
        val handler = sessionExceptionHandler {}

        assertThrows(AssertionError::class.java) { handler.handleException(EmptyCoroutineContext, AssertionError("x")) }
    }
}
