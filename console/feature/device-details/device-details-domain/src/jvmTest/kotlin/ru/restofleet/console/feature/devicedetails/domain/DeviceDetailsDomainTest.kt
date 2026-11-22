package ru.restofleet.console.feature.devicedetails.domain

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome

class DeviceDetailsDomainTest {
    private val card = DeviceCard(
        "d", "Кухня", "kitchen", "online", "Сокол", "Lenovo", "13", "1.0", "normal", 1L,
        SessionInfo(1L, null, null), Telemetry(1L, 34, true, 46.8, "SEVERE", -71, 4800, 1200, "chrome"),
        listOf(DeviceAlert("R-03", true, null, mapOf("limit" to "47.5"))), 10L,
    )

    private class Repo(val card: DeviceCard) : DeviceDetailsRepository {
        var temperatureArgs: Pair<Long, Int>? = null
        var probesLimit = 0
        var eventsArgs: Pair<Int, Int>? = null
        override suspend fun card(deviceId: String) = Outcome.Success(card)
        override suspend fun temperature(deviceId: String, fromMs: Long, limit: Int): Outcome<List<TempPoint>, ConsoleError> {
            temperatureArgs = fromMs to limit
            return Outcome.Success(listOf(TempPoint(1L, 40.0)))
        }
        override suspend fun probes(deviceId: String, limit: Int): Outcome<List<ProbeResult>, ConsoleError> {
            probesLimit = limit
            return Outcome.Success(listOf(ProbeResult(1L, "api", true, 204, null, 200, 9, 31, 64, 108)))
        }
        override suspend fun events(deviceId: String, offset: Int, limit: Int): Outcome<EventsPage, ConsoleError> {
            eventsArgs = offset to limit
            return Outcome.Success(EventsPage(listOf(DeviceEvent(1L, "probe_state", mapOf("state" to "down"))), offset, true))
        }
        override fun live(deviceId: String): Flow<DeviceLiveEvent> = flowOf(DeviceLiveEvent.ReloadNeeded, DeviceLiveEvent.StatusChanged("offline", 5L))
    }

    @Test
    fun `card is delegated`() = runTest {
        assertThat(LoadDeviceCard(Repo(card))("d")).isEqualTo(Outcome.Success(card))
    }

    @Test
    fun `temperature asks for the last 30 minutes`() = runTest {
        val repo = Repo(card)
        val result = LoadTemperatureHistory(repo, Clock { 100 * 60_000L })("d")

        assertThat(repo.temperatureArgs).isEqualTo((70 * 60_000L) to LoadTemperatureHistory.LIMIT)
        assertThat((result as Outcome.Success).value.single().tempC).isEqualTo(40.0)
    }

    @Test
    fun `probes and events use their page sizes`() = runTest {
        val repo = Repo(card)
        LoadProbes(repo)("d")
        assertThat(repo.probesLimit).isEqualTo(30)
        val page = (LoadEvents(repo)("d", 40) as Outcome.Success).value
        assertThat(repo.eventsArgs).isEqualTo(40 to LoadEvents.PAGE_SIZE)
        assertThat(page.hasMore).isTrue()
    }

    @Test
    fun `live events are passed through`() = runTest {
        ObserveDeviceLive(Repo(card))("d").test {
            assertThat(awaitItem()).isEqualTo(DeviceLiveEvent.ReloadNeeded)
            assertThat(awaitItem()).isEqualTo(DeviceLiveEvent.StatusChanged("offline", 5L))
            awaitComplete()
        }
        assertThat(DeviceLiveEvent.TelemetryPushed(card.telemetry!!).telemetry.batteryLevel).isEqualTo(34)
    }

    @Test
    fun `threshold comes from the overheat alert or defaults`() {
        assertThat(TempThreshold.of(card.alerts)).isEqualTo(47.5)
        assertThat(TempThreshold.of(emptyList())).isEqualTo(45.0)
        assertThat(TempThreshold.of(listOf(DeviceAlert("R-03", true, null, mapOf("limit" to "x"))))).isEqualTo(45.0)
        assertThat(TempThreshold.of(listOf(DeviceAlert("R-02", false, null, mapOf("limit" to "9"))))).isEqualTo(45.0)
    }
}
