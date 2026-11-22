package ru.restofleet.console.feature.restaurants.ui.viewmodel

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.feature.restaurants.domain.LoadRestaurants
import ru.restofleet.console.feature.restaurants.domain.Restaurant
import ru.restofleet.console.feature.restaurants.ui.api.IRestaurantsRouter
import ru.restofleet.console.feature.restaurants.ui.viewmodel.mapper.RestaurantsUiStateMapper
import ru.restofleet.shared.kernel.Outcome

class RestaurantsTest {
    private val sokol = Restaurant("1", "MSK-01", "Сокол", "Москва", "ул. Учебная, 10")
    private val arbat = Restaurant("2", "MSK-02", "Арбат", "Москва", "")
    private val reducer = RestaurantsReducer()
    private val mapper = RestaurantsUiStateMapper()

    @Test
    fun `reducer table`() {
        val loaded = reducer.reduce(RestaurantsAction.Loaded(listOf(sokol)), RestaurantsSession())
        assertThat(loaded).isEqualTo(RestaurantsSession(listOf(sokol), "", false, null))
        assertThat(reducer.reduce(RestaurantsAction.LoadStarted, loaded).loading).isFalse()
        assertThat(reducer.reduce(RestaurantsAction.LoadStarted, RestaurantsSession()).loading).isTrue()
        assertThat(reducer.reduce(RestaurantsAction.LoadFailed(ConsoleError.Network), loaded).error).isEqualTo(ConsoleError.Network)
        assertThat(reducer.reduce(RestaurantsAction.QueryChanged("с"), loaded).query).isEqualTo("с")
    }

    @Test
    fun `mapper filters rows and explains empty and error states`() {
        val s = RestaurantsSession(listOf(sokol, arbat), "", false, null)
        assertThat(mapper.map(s).rows.map { it.title }).containsExactly("Сокол · MSK-01", "Арбат · MSK-02").inOrder()
        assertThat(mapper.map(s).rows.map { it.subtitle }).containsExactly("Москва, ул. Учебная, 10", "Москва").inOrder()
        assertThat(mapper.map(s.copy(query = "арб")).rows.map { it.id }).containsExactly("2")
        assertThat(mapper.map(s.copy(query = "zzz")).emptyText).isEqualTo("Ничего не найдено")
        assertThat(mapper.map(s).emptyText).isNull()
        assertThat(mapper.map(s.copy(error = ConsoleError.Network)).errorText).isEqualTo("Нет связи с сервером")
        assertThat(mapper.map(s.copy(error = ConsoleError.Forbidden)).errorText).contains("Не удалось")
        assertThat(mapper.map(RestaurantsSession()).emptyText).isNull()
    }

    private fun vm(result: Outcome<List<Restaurant>, ConsoleError>, router: IRestaurantsRouter): RestaurantsViewModel {
        val load = mockk<LoadRestaurants>()
        coEvery { load() } returns result
        return RestaurantsViewModel(load, router, CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()))
    }

    @Test
    fun `loads on start, searches locally and opens devices of the restaurant`() = runTest {
        val router = mockk<IRestaurantsRouter>(relaxed = true)
        val vm = vm(Outcome.Success(listOf(sokol, arbat)), router)
        assertThat(vm.uiState.value.rows).hasSize(2)

        vm.onIntent(RestaurantsIntent.User.SearchChanged("сок"))
        assertThat(vm.uiState.value.rows).hasSize(1)
        vm.onIntent(RestaurantsIntent.User.Open("1"))
        vm.onIntent(RestaurantsIntent.User.Open("unknown"))

        verify(exactly = 1) { router.openRestaurantDevices("1", "Сокол") }
        vm.onDestroy()
    }

    @Test
    fun `failure is shown and refresh retries`() = runTest {
        val vm = vm(Outcome.Failure(ConsoleError.Network), mockk(relaxed = true))
        assertThat(vm.uiState.value.errorText).isEqualTo("Нет связи с сервером")
        vm.onIntent(RestaurantsIntent.User.Refresh)
        assertThat(vm.uiState.value.errorText).isEqualTo("Нет связи с сервером")
        vm.onDestroy()
    }
}
