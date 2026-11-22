package ru.restofleet.console.feature.dashboard.ui.viewmodel

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.designsystem.StatusTone
import ru.restofleet.console.feature.dashboard.domain.Dashboard
import ru.restofleet.console.feature.dashboard.domain.DashboardEvent
import ru.restofleet.console.feature.dashboard.domain.LoadDashboard
import ru.restofleet.console.feature.dashboard.domain.ObserveDashboardEvents
import ru.restofleet.console.feature.dashboard.domain.ProblemRestaurant
import ru.restofleet.console.feature.dashboard.domain.RecentAlert
import ru.restofleet.console.feature.dashboard.domain.Summary
import ru.restofleet.console.feature.dashboard.ui.api.IDashboardRouter
import ru.restofleet.console.feature.dashboard.ui.viewmodel.mapper.DashboardUiStateMapper
import ru.restofleet.console.feature.dashboard.ui.viewmodel.model.DashboardSession
import ru.restofleet.shared.kernel.Outcome

class DashboardTest {
    private val now = 1_000_000L

    private fun summary(online: Int = 214, total: Int = 228, offline: Int = 14, hour: Int = 3, open: Int = 9, crit: Int = 3,
        problems: Int = 3, restaurants: Int = 38, list: List<ProblemRestaurant> = emptyList()) =
        Summary(online, total, offline, hour, open, crit, problems, restaurants, list)

    private val problem = ProblemRestaurant(
        "r1", "MSK-01", "Сокол", "R-05", 4, 6, "api", null, null, now - 60_000, mapOf("error" to "timeout", "failures" to "3"),
    )
    private val alert = RecentAlert(
        "a1", "R-03", true, "d1", "Кухня 2", "Химки", null, now - 120_000, mapOf("tempC" to "46.8", "thermal" to "SEVERE"),
    )
    private fun dashboard(s: Summary = summary(list = listOf(problem))) = Dashboard(s, listOf(alert), now)

    private val reducer = DashboardReducer()
    private val mapper = DashboardUiStateMapper()

    @Test
    fun `reducer table`() {
        val loaded = reducer.reduce(DashboardAction.Loaded(dashboard()), DashboardSession())
        assertThat(loaded).isEqualTo(DashboardSession(loading = false, data = dashboard()))
        assertThat(reducer.reduce(DashboardAction.LoadStarted, loaded).loading).isTrue()
        assertThat(reducer.reduce(DashboardAction.LoadStarted, loaded).data).isNotNull()
        val failed = reducer.reduce(DashboardAction.LoadFailed(ConsoleError.Network), loaded)
        assertThat(failed.error).isEqualTo(ConsoleError.Network)
        assertThat(failed.data).isNotNull()
        val pushed = reducer.reduce(DashboardAction.SummaryPushed(summary(online = 1)), failed)
        assertThat(pushed.data!!.summary.devicesOnline).isEqualTo(1)
        assertThat(pushed.data!!.recent).hasSize(1)
        assertThat(pushed.error).isNull()
        val initial = DashboardSession()
        assertThat(reducer.reduce(DashboardAction.SummaryPushed(summary()), initial)).isSameInstanceAs(initial)
    }

    @Test
    fun `mapper builds counters like the mockup`() {
        val ui = mapper.map(DashboardSession(loading = false, data = dashboard()))
        assertThat(ui.counters.map { it.label to it.value }).containsExactly(
            "Онлайн" to "214", "Офлайн" to "14", "Тревоги" to "9", "Ресторанов с проблемами" to "3",
        ).inOrder()
        assertThat(ui.counters[0].caption).isEqualTo("из 228 · 93 %")
        assertThat(ui.counters[1].caption).isEqualTo("+3 за час")
        assertThat(ui.counters[2].caption).isEqualTo("3 критичные")
        assertThat(ui.counters[2].tone).isEqualTo(StatusTone.Crit)
        assertThat(ui.counters[3].caption).isEqualTo("из 38")
        assertThat(ui.loading).isFalse()
    }

    @Test
    fun `mapper tones table`() {
        fun tones(s: Summary) = mapper.map(DashboardSession(false, Dashboard(s, emptyList(), now))).counters.map { it.tone }
        assertThat(tones(summary(online = 228, offline = 0, hour = 0, open = 0, crit = 0, problems = 0)))
            .containsExactly(StatusTone.Ok, StatusTone.Off, StatusTone.Ok, StatusTone.Ok).inOrder()
        assertThat(tones(summary(online = 160, total = 228, open = 2, crit = 0)))
            .containsExactly(StatusTone.Warn, StatusTone.Warn, StatusTone.Warn, StatusTone.Warn).inOrder()
        assertThat(tones(summary(online = 10, total = 228))[0]).isEqualTo(StatusTone.Crit)
        assertThat(mapper.map(DashboardSession(false, Dashboard(summary(online = 0, total = 0), emptyList(), now))).empty).isTrue()
    }

    @Test
    fun `mapper builds problem and recent lines with relative time`() {
        val ui = mapper.map(DashboardSession(false, dashboard()))
        assertThat(ui.problems.single().line).isEqualTo("Цель api недоступна · timeout ×3 · 4 из 6 устройств")
        assertThat(ui.problems.single().restaurantName).isEqualTo("Сокол")
        assertThat(ui.recent.single().title).isEqualTo("Перегрев · 46.8 °C · SEVERE")
        assertThat(ui.recent.single().where).isEqualTo("Кухня 2 · Химки")
        assertThat(ui.recent.single().ago).isEqualTo("2 мин назад")
        assertThat(ui.recent.single().tone).isEqualTo(StatusTone.Crit)
    }

    @Test
    fun `mapper shows spinner only without data and maps errors`() {
        assertThat(mapper.map(DashboardSession()).loading).isTrue()
        assertThat(mapper.map(DashboardSession(loading = true, data = dashboard())).loading).isFalse()
        fun text(e: ConsoleError) = mapper.map(DashboardSession(false, null, e)).errorText
        assertThat(text(ConsoleError.Network)).isEqualTo("Нет связи с сервером")
        assertThat(text(ConsoleError.Unauthorized)).contains("Сессия")
        assertThat(text(ConsoleError.Forbidden)).contains("прав")
        assertThat(text(ConsoleError.Server(500, null))).contains("Не удалось")
        assertThat(mapper.map(DashboardSession(false, null, null)).errorText).isNull()
    }

    private class Env(results: List<Outcome<Dashboard, ConsoleError>>) {
        val events = MutableSharedFlow<DashboardEvent>(extraBufferCapacity = 8)
        val router = mockk<IDashboardRouter>(relaxed = true)
        val load = mockk<LoadDashboard>()
        val observe = mockk<ObserveDashboardEvents>()
        val calls get() = _calls
        private var _calls = 0

        init {
            val queue = results.toMutableList()
            coEvery { load() } answers { _calls++; if (queue.size > 1) queue.removeAt(0) else queue.first() }
            every { observe() } returns events
        }

        fun vm() = DashboardViewModel(load, observe, router, CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()))
    }

    @Test
    fun `loads on start and reloads on realtime signal, pushed summary is applied without a request`() = runTest {
        val env = Env(listOf(Outcome.Success(dashboard()), Outcome.Success(dashboard(summary(online = 5)))))
        val vm = env.vm()
        assertThat(env.calls).isEqualTo(1)
        assertThat(vm.uiState.value.counters.first().value).isEqualTo("214")

        env.events.emit(DashboardEvent.ReloadNeeded)
        assertThat(env.calls).isEqualTo(2)
        assertThat(vm.uiState.value.counters.first().value).isEqualTo("5")

        env.events.emit(DashboardEvent.SummaryPushed(summary(online = 77)))
        assertThat(env.calls).isEqualTo(2)
        assertThat(vm.uiState.value.counters.first().value).isEqualTo("77")
        vm.onDestroy()
    }

    @Test
    fun `failure is shown and refresh retries`() = runTest {
        val env = Env(listOf(Outcome.Failure(ConsoleError.Network), Outcome.Success(dashboard())))
        val vm = env.vm()
        assertThat(vm.uiState.value.errorText).isEqualTo("Нет связи с сервером")
        assertThat(vm.uiState.value.counters).isEmpty()

        vm.onIntent(DashboardIntent.User.Refresh)

        assertThat(vm.uiState.value.errorText).isNull()
        assertThat(vm.uiState.value.counters).isNotEmpty()
        coVerify(exactly = 2) { env.load() }
        vm.onDestroy()
    }

    @Test
    fun `navigation intents go to the router`() = runTest {
        val env = Env(listOf(Outcome.Success(dashboard())))
        val vm = env.vm()
        vm.onIntent(DashboardIntent.User.OpenDevice("d1"))
        vm.onIntent(DashboardIntent.User.OpenRestaurant("r1", "Сокол"))
        vm.onIntent(DashboardIntent.User.OpenAlerts)
        vm.onIntent(DashboardIntent.User.Logout)
        verify { env.router.openDevice("d1") }
        verify { env.router.openRestaurant("r1", "Сокол") }
        verify { env.router.openAlerts() }
        verify { env.router.logout() }
        vm.onDestroy()
    }
}
