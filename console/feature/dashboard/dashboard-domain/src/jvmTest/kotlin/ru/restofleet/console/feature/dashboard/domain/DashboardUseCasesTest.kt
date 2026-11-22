package ru.restofleet.console.feature.dashboard.domain

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Outcome

class DashboardUseCasesTest {
    private val problem = ProblemRestaurant("r", "C", "Сокол", "R-05", 4, 6, "api", "d", "Кухня", 1L, mapOf("k" to "v"))
    private val alert = RecentAlert("a", "R-03", true, "d", "Кухня", "Сокол", null, 2L, emptyMap())
    private val summary = Summary(1, 2, 1, 0, 0, 0, 0, 3, listOf(problem))
    private val dashboard = Dashboard(summary, listOf(alert), 10L)

    private class Repo(val result: Outcome<Dashboard, ConsoleError>, val events: Flow<DashboardEvent>) :
        DashboardRepository {
        override suspend fun load() = result
        override fun events() = events
    }

    @Test
    fun `load delegates and keeps failures`() = runTest {
        assertThat(LoadDashboard(Repo(Outcome.Success(dashboard), flowOf()))()).isEqualTo(Outcome.Success(dashboard))
        val failure = Outcome.Failure(ConsoleError.Network)
        assertThat(LoadDashboard(Repo(failure, flowOf()))()).isEqualTo(failure)
    }

    @Test
    fun `events are passed through`() = runTest {
        val repo = Repo(Outcome.Success(dashboard), flowOf(DashboardEvent.SummaryPushed(summary), DashboardEvent.ReloadNeeded))
        ObserveDashboardEvents(repo)().test {
            assertThat(awaitItem()).isEqualTo(DashboardEvent.SummaryPushed(summary))
            assertThat(awaitItem()).isEqualTo(DashboardEvent.ReloadNeeded)
            awaitComplete()
        }
    }
}
