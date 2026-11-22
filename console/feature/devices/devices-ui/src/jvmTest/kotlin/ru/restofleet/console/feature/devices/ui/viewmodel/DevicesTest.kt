package ru.restofleet.console.feature.devices.ui.viewmodel

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.designsystem.StatusTone
import ru.restofleet.console.feature.devices.domain.DeviceAlert
import ru.restofleet.console.feature.devices.domain.DeviceFilter
import ru.restofleet.console.feature.devices.domain.DeviceItem
import ru.restofleet.console.feature.devices.domain.DevicesEvent
import ru.restofleet.console.feature.devices.domain.DevicesPage
import ru.restofleet.console.feature.devices.domain.LoadDevicesPage
import ru.restofleet.console.feature.devices.domain.LoadRestaurantOptions
import ru.restofleet.console.feature.devices.domain.ObserveDevicesEvents
import ru.restofleet.console.feature.devices.domain.RestaurantOption
import ru.restofleet.console.feature.devices.ui.api.IDevicesRouter
import ru.restofleet.console.feature.devices.ui.viewmodel.mapper.DevicesUiStateMapper
import ru.restofleet.console.feature.devices.ui.viewmodel.model.DevicesSession
import ru.restofleet.shared.kernel.Outcome

private fun device(
    id: String = "d1", name: String? = "Кухня 2", status: String = "online", version: String? = "1.0.0",
    alerts: List<DeviceAlert> = emptyList(), lastSeen: Long? = null, reason: String? = null,
    battery: Int? = 34, charging: Boolean? = true, temp: Double? = 46.8, thermal: String? = "SEVERE",
) = DeviceItem(id, name, "kitchen", status, "r1", "Химки", version, lastSeen, reason, battery, charging, temp, thermal, alerts)

class DevicesTest {
    private val now = 10 * 60_000L

    private val overheat = DeviceAlert("R-03", true, null, mapOf("tempC" to "46.8", "thermal" to "SEVERE"))
    private val reducer = DevicesReducer()
    private val mapper = DevicesUiStateMapper()

    @Test
    fun `reducer pages append and the first page replaces`() {
        val first = reducer.reduce(DevicesAction.PageLoaded(DevicesPage(listOf(device("a")), 0, true, 5)), DevicesSession())
        assertThat(first.items.map { it.id }).containsExactly("a")
        assertThat(first.hasMore).isTrue()
        assertThat(first.loading).isFalse()
        val more = reducer.reduce(DevicesAction.PageLoaded(DevicesPage(listOf(device("b")), 1, false, 6)), first)
        assertThat(more.items.map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(more.hasMore).isFalse()
        val again = reducer.reduce(DevicesAction.PageLoaded(DevicesPage(listOf(device("c")), 0, false, 7)), more)
        assertThat(again.items.map { it.id }).containsExactly("c")
    }

    @Test
    fun `reducer filter change resets the list, failures keep rows, status change patches a row`() {
        val loaded = DevicesSession(items = listOf(device("a")), loading = false, hasMore = true)
        val changed = reducer.reduce(DevicesAction.FilterChanged(DeviceFilter(role = "hall")), loaded)
        assertThat(changed.items).isEmpty()
        assertThat(changed.loading).isTrue()
        assertThat(changed.filter.role).isEqualTo("hall")
        assertThat(reducer.reduce(DevicesAction.ReloadStarted, loaded).loading).isFalse()
        assertThat(reducer.reduce(DevicesAction.ReloadStarted, DevicesSession()).loading).isTrue()
        assertThat(reducer.reduce(DevicesAction.MoreStarted, loaded).loadingMore).isTrue()
        val failed = reducer.reduce(DevicesAction.LoadFailed(ConsoleError.Network), loaded.copy(loadingMore = true))
        assertThat(failed.items).hasSize(1)
        assertThat(failed.loadingMore).isFalse()
        assertThat(failed.error).isEqualTo(ConsoleError.Network)
        val options = listOf(RestaurantOption("r", "Сокол"))
        assertThat(reducer.reduce(DevicesAction.RestaurantsLoaded(options), loaded).restaurants).isEqualTo(options)
        val patched = reducer.reduce(DevicesAction.StatusChanged("a", "offline", 99), loaded)
        assertThat(patched.items.single().status).isEqualTo("offline")
        assertThat(patched.items.single().lastSeenAt).isEqualTo(99)
        val keep = reducer.reduce(DevicesAction.StatusChanged("a", "online", null), patched)
        assertThat(keep.items.single().lastSeenAt).isEqualTo(99)
        assertThat(reducer.reduce(DevicesAction.StatusChanged("zzz", "offline", 1), loaded).items).isEqualTo(loaded.items)
    }

    @Test
    fun `mapper rows like the mockup`() {
        val s = DevicesSession(
            items = listOf(
                device(alerts = listOf(overheat)),
                device("d2", "Зал 3", "offline", lastSeen = now - 7 * 60_000, reason = "lwt", battery = null, temp = null, thermal = null),
                device("d3", null, "provisioned", lastSeen = null, battery = null, temp = null, thermal = null),
                device("d4", "Сборка 1", battery = 18, charging = false, temp = 31.2, thermal = "NONE"),
            ),
            loading = false, loadedAt = now,
        )
        val rows = mapper.map(s).rows
        assertThat(rows[0].title).isEqualTo("Кухня 2 · Химки")
        assertThat(rows[0].metrics).isEqualTo("34 % ⚡ · 46.8 °C · SEVERE")
        assertThat(rows[0].ruleId).isEqualTo("R-03")
        assertThat(rows[0].tone).isEqualTo(StatusTone.Crit)
        assertThat(rows[1].metrics).isEqualTo("офлайн 7 мин · lwt")
        assertThat(rows[1].statusText).isEqualTo("офлайн")
        assertThat(rows[1].tone).isEqualTo(StatusTone.Off)
        assertThat(rows[2].title).isEqualTo("d3 · Химки")
        assertThat(rows[2].metrics).isEqualTo("ещё не выходило на связь")
        assertThat(rows[3].metrics).isEqualTo("18 % · 31.2 °C")
        assertThat(rows[3].tone).isEqualTo(StatusTone.Ok)
        assertThat(rows[3].ruleId).isNull()
    }

    @Test
    fun `mapper alert detail is not duplicated and warning tone is used`() {
        val warn = DeviceAlert("R-06", false, null, mapOf("freeMb" to "410"))
        val rows = mapper.map(DevicesSession(items = listOf(device(alerts = listOf(warn), thermal = null)), loading = false)).rows
        assertThat(rows.single().metrics).isEqualTo("34 % ⚡ · 46.8 °C · свободно 410 МБ")
        assertThat(rows.single().tone).isEqualTo(StatusTone.Warn)
        assertThat(rows.single().ruleTone).isEqualTo(StatusTone.Warn)
        val dup = mapper.map(DevicesSession(items = listOf(device(alerts = listOf(DeviceAlert("R-02", false, null, mapOf("batteryLevel" to "34"))), temp = null, thermal = null)), loading = false)).rows
        assertThat(dup.single().metrics).isEqualTo("34 % ⚡")
    }

    @Test
    fun `mapper filters, options, empty texts and errors`() {
        val s = DevicesSession(
            items = listOf(device(version = "1.2.0"), device("d2", version = "1.0.0"), device("d3", version = null)),
            filter = DeviceFilter(role = "hall", problemsOnly = false),
            restaurants = listOf(RestaurantOption("r1", "Химки · MSK-02")),
            loading = false, hasMore = true,
        )
        val ui = mapper.map(s)
        assertThat(ui.versions.map { it.label }).containsExactly("Все", "1.0.0", "1.2.0").inOrder()
        assertThat(ui.roles.first { it.selected }.value).isEqualTo("hall")
        assertThat(ui.restaurants.map { it.label }).containsExactly("Все", "Химки · MSK-02").inOrder()
        assertThat(ui.statuses).hasSize(4)
        assertThat(ui.canLoadMore).isTrue()
        assertThat(ui.count).isEqualTo(3)
        assertThat(ui.title).isEqualTo("Устройства")
        assertThat(mapper.map(s.copy(title = "Химки")).title).isEqualTo("Химки")
        assertThat(mapper.map(s.copy(loadingMore = true)).canLoadMore).isFalse()

        val empty = DevicesSession(loading = false)
        assertThat(mapper.map(empty).emptyText).isEqualTo("Проблем нет — все устройства в порядке")
        assertThat(mapper.map(empty.copy(filter = DeviceFilter(query = "x"))).emptyText).isEqualTo("Ничего не найдено")
        assertThat(mapper.map(empty.copy(filter = DeviceFilter(problemsOnly = false))).emptyText).isEqualTo("Ничего не найдено")
        assertThat(mapper.map(DevicesSession()).emptyText).isNull()
        assertThat(mapper.map(empty.copy(error = ConsoleError.Network)).errorText).isEqualTo("Нет связи с сервером")
        assertThat(mapper.map(empty.copy(error = ConsoleError.Unauthorized)).errorText).contains("Сессия")
        assertThat(mapper.map(empty.copy(error = ConsoleError.Forbidden)).errorText).contains("Не удалось")
    }

    private class Env(private val scope: TestScope) {
        val events = MutableSharedFlow<DevicesEvent>(extraBufferCapacity = 8)
        val router = mockk<IDevicesRouter>(relaxed = true)
        val load = mockk<LoadDevicesPage>()
        val restaurants = mockk<LoadRestaurantOptions>()
        val observe = mockk<ObserveDevicesEvents>()
        val requests = mutableListOf<Pair<DeviceFilter, Int>>()
        var pages: (DeviceFilter, Int) -> Outcome<DevicesPage, ConsoleError> = { _, offset ->
            Outcome.Success(DevicesPage(listOf(device("d$offset")), offset, offset < 1, 1L))
        }

        init {
            coEvery { load(any(), any(), any()) } answers {
                requests += firstArg<DeviceFilter>() to secondArg<Int>()
                pages(firstArg(), secondArg())
            }
            coEvery { restaurants() } returns Outcome.Success(listOf(RestaurantOption("r1", "Химки")))
            every { observe() } returns events
        }

        fun vm(filter: DeviceFilter = DeviceFilter(), title: String? = null) = DevicesViewModel(
            load, restaurants, observe, router, filter, title,
            CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scope.testScheduler)),
        )
    }

    @Test
    fun `starts with problems filter, loads restaurants and the first page`() = runTest {
        val env = Env(this)
        val vm = env.vm(title = "Химки")

        assertThat(env.requests.single()).isEqualTo(DeviceFilter() to 0)
        assertThat(vm.uiState.value.title).isEqualTo("Химки")
        assertThat(vm.uiState.value.rows).hasSize(1)
        assertThat(vm.uiState.value.restaurants).hasSize(2)
        vm.onDestroy()
    }

    @Test
    fun `filter intents reload from the first page`() = runTest {
        val env = Env(this)
        val vm = env.vm()

        vm.onIntent(DevicesIntent.User.ToggleProblems)
        vm.onIntent(DevicesIntent.User.RestaurantSelected("r1"))
        vm.onIntent(DevicesIntent.User.RoleSelected("hall"))
        vm.onIntent(DevicesIntent.User.StatusSelected("online"))
        vm.onIntent(DevicesIntent.User.VersionSelected("1.0.0"))

        val last = env.requests.last().first
        assertThat(last).isEqualTo(DeviceFilter(restaurantId = "r1", role = "hall", status = "online", version = "1.0.0", problemsOnly = false))
        assertThat(env.requests.all { it.second == 0 }).isTrue()
        vm.onDestroy()
    }

    @Test
    fun `search waits for a pause in typing`() = runTest {
        val env = Env(this)
        val vm = env.vm()
        val before = env.requests.size

        vm.onIntent(DevicesIntent.User.SearchChanged("к"))
        vm.onIntent(DevicesIntent.User.SearchChanged("ку"))
        advanceTimeBy(200)
        assertThat(env.requests.size).isEqualTo(before)
        advanceTimeBy(150)

        assertThat(env.requests.size).isEqualTo(before + 1)
        assertThat(env.requests.last().first.query).isEqualTo("ку")
        vm.onDestroy()
    }

    @Test
    fun `load more appends once and stops at the end`() = runTest {
        val env = Env(this)
        val vm = env.vm()

        vm.onIntent(DevicesIntent.User.LoadMore)
        assertThat(vm.uiState.value.rows.map { it.id }).containsExactly("d0", "d1").inOrder()
        vm.onIntent(DevicesIntent.User.LoadMore)

        assertThat(env.requests.map { it.second }).containsExactly(0, 1).inOrder()
        vm.onDestroy()
    }

    @Test
    fun `errors are shown and refresh recovers, stale answers are dropped`() = runTest {
        val env = Env(this)
        env.pages = { _, _ -> Outcome.Failure(ConsoleError.Network) }
        val vm = env.vm()
        assertThat(vm.uiState.value.errorText).isEqualTo("Нет связи с сервером")

        env.pages = { _, o -> Outcome.Success(DevicesPage(listOf(device("ok")), o, false, 1L)) }
        vm.onIntent(DevicesIntent.User.Refresh)

        assertThat(vm.uiState.value.errorText).isNull()
        assertThat(vm.uiState.value.rows.single().id).isEqualTo("ok")
        vm.onDestroy()
    }

    @Test
    fun `realtime patches status in place and reload signal refetches`() = runTest {
        val env = Env(this)
        val vm = env.vm()
        val before = env.requests.size

        env.events.emit(DevicesEvent.StatusChanged("d0", "offline", 5))
        assertThat(vm.uiState.value.rows.single().statusText).isEqualTo("офлайн")
        assertThat(env.requests.size).isEqualTo(before)

        env.events.emit(DevicesEvent.ReloadNeeded)
        assertThat(env.requests.size).isEqualTo(before + 1)
        vm.onDestroy()
    }

    @Test
    fun `navigation intents go to the router`() = runTest {
        val env = Env(this)
        val vm = env.vm()
        vm.onIntent(DevicesIntent.User.OpenDevice("d9"))
        vm.onIntent(DevicesIntent.User.OpenProvisioning)
        verify { env.router.openDevice("d9") }
        verify { env.router.openProvisioning() }
        vm.onDestroy()
    }
}
