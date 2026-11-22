package ru.restofleet.server.domain.health

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.domain.health.usecase.CheckHealth
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertFailsWith

class CheckHealthTest {
    private fun probe(name: String, check: suspend () -> Boolean) = object : HealthProbe {
        override val name = name

        override suspend fun isHealthy() = check()
    }

    @Test
    fun reportsEachComponent() = runTest {
        val report = CheckHealth(
            listOf(probe("db") { true }, probe("broker") { false }, probe("boom") { error("x") }),
        )()
        assertThat(report.components).containsExactly("db", true, "broker", false, "boom", false)
        assertThat(report.healthy).isFalse()
        assertThat(CheckHealth(listOf(probe("db") { true }))().healthy).isTrue()
    }

    @Test
    fun cancellationPropagates() = runTest {
        assertFailsWith<CancellationException> {
            CheckHealth(listOf(probe("db") { throw CancellationException("stop") }))()
        }
    }
}
