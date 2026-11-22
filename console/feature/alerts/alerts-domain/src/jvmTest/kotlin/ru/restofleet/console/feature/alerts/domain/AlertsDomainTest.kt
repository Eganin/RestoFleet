package ru.restofleet.console.feature.alerts.domain

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Outcome

class AlertsDomainTest {
    private val alert = AlertItem(
        "a1", "R-03", true, AlertState.Open, "d1", "Кухня 2", "Химки", null, 1L, null, null, null, false, mapOf("tempC" to "46.8"),
    )
    private val lists = AlertLists(listOf(alert), emptyList(), emptyList(), 5L)

    private class Repo(val alert: AlertItem, val lists: AlertLists) : AlertsRepository {
        var limit = 0
        val acted = mutableListOf<String>()
        override suspend fun lists(limit: Int): Outcome<AlertLists, ConsoleError> {
            this.limit = limit
            return Outcome.Success(lists)
        }
        override suspend fun acknowledge(id: String): Outcome<AlertItem, ConsoleError> {
            acted += "ack:$id"
            return Outcome.Success(alert.copy(state = AlertState.Acknowledged))
        }
        override suspend fun resolve(id: String, comment: String): Outcome<AlertItem, ConsoleError> {
            acted += "resolve:$id:$comment"
            return Outcome.Success(alert.copy(state = AlertState.Resolved))
        }
        override fun events(): Flow<AlertsEvent> = flowOf(AlertsEvent.ReloadNeeded, AlertsEvent.Pushed(alert))
    }

    @Test
    fun `state wire names round trip, unknown is open`() {
        AlertState.entries.forEach { assertThat(AlertState.fromWire(it.wire)).isEqualTo(it) }
        assertThat(AlertState.fromWire("???")).isEqualTo(AlertState.Open)
    }

    @Test
    fun `load, acknowledge and events are delegated`() = runTest {
        val repo = Repo(alert, lists)
        assertThat(LoadAlerts(repo)()).isEqualTo(Outcome.Success(lists))
        assertThat(repo.limit).isEqualTo(LoadAlerts.LIMIT)
        assertThat((AcknowledgeAlert(repo)("a1") as Outcome.Success).value.state).isEqualTo(AlertState.Acknowledged)
        ObserveAlertsEvents(repo)().test {
            assertThat(awaitItem()).isEqualTo(AlertsEvent.ReloadNeeded)
            assertThat(awaitItem()).isEqualTo(AlertsEvent.Pushed(alert))
            awaitComplete()
        }
    }

    @Test
    fun `resolve requires a comment of up to 500 characters and trims it`() = runTest {
        val repo = Repo(alert, lists)
        val resolve = ResolveAlert(repo)

        assertThat(resolve("a1", "")).isEqualTo(Outcome.Failure(ConsoleError.InvalidInput("comment", "empty")))
        assertThat(resolve("a1", "   ")).isEqualTo(Outcome.Failure(ConsoleError.InvalidInput("comment", "empty")))
        assertThat(resolve("a1", "x".repeat(501))).isEqualTo(Outcome.Failure(ConsoleError.InvalidInput("comment", "too_long")))
        assertThat(repo.acted).isEmpty()

        resolve("a1", "  Заменили кабель ")
        resolve("a1", "x".repeat(500))

        assertThat(repo.acted[0]).isEqualTo("resolve:a1:Заменили кабель")
        assertThat(repo.acted).hasSize(2)
    }
}
