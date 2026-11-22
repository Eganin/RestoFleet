package ru.restofleet.console.feature.devices.domain

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Outcome

class DevicesUseCasesTest {
    private class Repo : DevicesRepository {
        var args: Triple<DeviceFilter, Int, Int>? = null
        override suspend fun page(filter: DeviceFilter, offset: Int, limit: Int): Outcome<DevicesPage, ConsoleError> {
            args = Triple(filter, offset, limit)
            return Outcome.Success(DevicesPage(emptyList(), offset, false, 1L))
        }
        override suspend fun restaurants() = Outcome.Success(listOf(RestaurantOption("r", "Сокол")))
        override fun events(): Flow<DevicesEvent> = flowOf(DevicesEvent.ReloadNeeded)
    }

    @Test
    fun `filter defaults to problems only and models hold their data`() {
        assertThat(DeviceFilter().problemsOnly).isTrue()
        val alert = DeviceAlert("R-03", true, null, mapOf("tempC" to "46.8"))
        val item = DeviceItem("d", "Кухня", "kitchen", "online", "r", "Сокол", "1.0", 1L, null, 34, true, 46.8, "SEVERE", listOf(alert))
        assertThat(item.alerts.single().ruleId).isEqualTo("R-03")
        assertThat(DevicesEvent.StatusChanged("d", "offline", null).status).isEqualTo("offline")
    }

    @Test
    fun `page uses the default size and passes filter and offset through`() = runTest {
        val repo = Repo()
        val filter = DeviceFilter(query = "q", role = "kitchen", problemsOnly = false)

        LoadDevicesPage(repo)(filter, offset = 60)
        assertThat(repo.args).isEqualTo(Triple(filter, 60, LoadDevicesPage.PAGE_SIZE))
        LoadDevicesPage(repo)(filter, offset = 0, limit = 5)
        assertThat(repo.args!!.third).isEqualTo(5)
    }

    @Test
    fun `restaurants and events are delegated`() = runTest {
        val repo = Repo()
        assertThat(LoadRestaurantOptions(repo)()).isEqualTo(Outcome.Success(listOf(RestaurantOption("r", "Сокол"))))
        ObserveDevicesEvents(repo)().test {
            assertThat(awaitItem()).isEqualTo(DevicesEvent.ReloadNeeded)
            awaitComplete()
        }
    }
}
