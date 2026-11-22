package ru.restofleet.console.feature.devicedetails.ui.viewmodel

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
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
import ru.restofleet.console.feature.devicedetails.domain.DeviceAlert
import ru.restofleet.console.feature.devicedetails.domain.DeviceCard
import ru.restofleet.console.feature.devicedetails.domain.DeviceEvent
import ru.restofleet.console.feature.devicedetails.domain.DeviceLiveEvent
import ru.restofleet.console.feature.devicedetails.domain.EventsPage
import ru.restofleet.console.feature.devicedetails.domain.LoadDeviceCard
import ru.restofleet.console.feature.devicedetails.domain.LoadEvents
import ru.restofleet.console.feature.devicedetails.domain.LoadProbes
import ru.restofleet.console.feature.devicedetails.domain.LoadTemperatureHistory
import ru.restofleet.console.feature.devicedetails.domain.ObserveDeviceLive
import ru.restofleet.console.feature.devicedetails.domain.ProbeResult
import ru.restofleet.console.feature.devicedetails.domain.SessionInfo
import ru.restofleet.console.feature.devicedetails.domain.TempPoint
import ru.restofleet.console.feature.devicedetails.domain.Telemetry
import ru.restofleet.console.feature.devicedetails.ui.api.IDeviceDetailsRouter
import ru.restofleet.console.feature.devicedetails.ui.viewmodel.mapper.DetailsUiStateMapper
import ru.restofleet.console.feature.devicedetails.ui.viewmodel.model.DetailsSession
import ru.restofleet.console.feature.devicedetails.ui.viewmodel.model.DetailsTab
import ru.restofleet.shared.kernel.Outcome

private const val NOW = 4 * 3_600_000L
private const val MIN = 60_000L

private fun telemetry(
    ts: Long = NOW - 8_000, temp: Double? = 46.8, battery: Int? = 34, charging: Boolean? = true,
    thermal: String? = "SEVERE", rssi: Int? = -71, free: Long? = 4800, avail: Long? = 1200, fg: String? = "com.android.chrome",
) = Telemetry(ts, battery, charging, temp, thermal, rssi, free, avail, fg)

private fun card(
    status: String = "online", alerts: List<DeviceAlert> = emptyList(), t: Telemetry? = telemetry(), name: String? = "Кухня 2",
) = DeviceCard(
    "d1", name, "kitchen", status, "Химки", "Lenovo M10", "13", "1.0.0", "normal", NOW - 8_000,
    SessionInfo(NOW - (3 * 60 + 12) * MIN, null, null), t, alerts, NOW,
)

class DetailsTest {
    private val overheat = DeviceAlert("R-03", true, null, mapOf("limit" to "45"))
    private val reducer = DetailsReducer()
    private val mapper = DetailsUiStateMapper()

    @Test
    fun `reducer card and tabs`() {
        val loaded = reducer.reduce(DetailsAction.CardLoaded(card(), listOf(TempPoint(1, 40.0))), DetailsSession())
        assertThat(loaded.loading).isFalse()
        assertThat(loaded.knownNow).isEqualTo(NOW)
        assertThat(reducer.reduce(DetailsAction.CardStarted, loaded).loading).isFalse()
        assertThat(reducer.reduce(DetailsAction.CardStarted, DetailsSession()).loading).isTrue()
        assertThat(reducer.reduce(DetailsAction.CardFailed(ConsoleError.NotFound), loaded).error).isEqualTo(ConsoleError.NotFound)
        assertThat(reducer.reduce(DetailsAction.TabSelected(DetailsTab.Events), loaded).tab).isEqualTo(DetailsTab.Events)
        assertThat(reducer.reduce(DetailsAction.CommandSheet(true), loaded).commandSheet).isTrue()
        val probes = listOf(ProbeResult(1, "api", true, 204, null, 10, null, null, null, null))
        assertThat(reducer.reduce(DetailsAction.ProbesLoaded(probes), loaded).probes).isEqualTo(probes)
        assertThat(reducer.reduce(DetailsAction.ProbesFailed(ConsoleError.Network), loaded).probesError).isEqualTo(ConsoleError.Network)
    }

    @Test
    fun `reducer events paging`() {
        val e1 = DeviceEvent(1, "a", emptyMap())
        val e2 = DeviceEvent(2, "b", emptyMap())
        val first = reducer.reduce(DetailsAction.EventsLoaded(EventsPage(listOf(e1), 0, true)), DetailsSession())
        assertThat(first.events).containsExactly(e1)
        assertThat(first.eventsHasMore).isTrue()
        val busy = reducer.reduce(DetailsAction.EventsMoreStarted, first)
        assertThat(busy.eventsLoadingMore).isTrue()
        val second = reducer.reduce(DetailsAction.EventsLoaded(EventsPage(listOf(e2), 1, false)), busy)
        assertThat(second.events).containsExactly(e1, e2).inOrder()
        assertThat(second.eventsLoadingMore).isFalse()
        assertThat(reducer.reduce(DetailsAction.EventsFailed(ConsoleError.Network), busy).eventsError).isEqualTo(ConsoleError.Network)
    }

    @Test
    fun `reducer telemetry push updates the card, extends the chart and trims it to 30 minutes`() {
        val base = reducer.reduce(
            DetailsAction.CardLoaded(card(), listOf(TempPoint(NOW - 40 * MIN, 30.0), TempPoint(NOW - 20 * MIN, 35.0))),
            DetailsSession(),
        )
        val pushed = reducer.reduce(DetailsAction.TelemetryPushed(telemetry(ts = NOW + 5_000, temp = 47.0)), base)

        assertThat(pushed.card!!.telemetry!!.tempC).isEqualTo(47.0)
        assertThat(pushed.temp.map { it.tempC }).containsExactly(35.0, 47.0).inOrder()
        assertThat(pushed.knownNow).isEqualTo(NOW + 5_000)
        val dup = reducer.reduce(DetailsAction.TelemetryPushed(telemetry(ts = NOW + 5_000, temp = 48.0)), pushed)
        assertThat(dup.temp.map { it.tempC }).containsExactly(35.0, 48.0).inOrder()
        val noTemp = reducer.reduce(DetailsAction.TelemetryPushed(telemetry(ts = NOW + 9_000, temp = null)), dup)
        assertThat(noTemp.temp).isEqualTo(dup.temp)
        assertThat(noTemp.card!!.telemetry!!.tempC).isNull()
        val empty = DetailsSession()
        assertThat(reducer.reduce(DetailsAction.TelemetryPushed(telemetry()), empty)).isSameInstanceAs(empty)
    }

    @Test
    fun `reducer status change keeps last seen when absent`() {
        val base = reducer.reduce(DetailsAction.CardLoaded(card(), emptyList()), DetailsSession())
        val a = reducer.reduce(DetailsAction.StatusChanged("offline", 99), base)
        assertThat(a.card!!.status).isEqualTo("offline")
        assertThat(a.card!!.lastSeenAt).isEqualTo(99)
        assertThat(reducer.reduce(DetailsAction.StatusChanged("online", null), a).card!!.lastSeenAt).isEqualTo(99)
        val empty = DetailsSession()
        assertThat(reducer.reduce(DetailsAction.StatusChanged("online", 1), empty).card).isNull()
    }

    @Test
    fun `mapper header and tiles like the mockup`() {
        val s = DetailsSession(card = card(alerts = listOf(overheat)), loading = false, knownNow = NOW)
        val ui = mapper.map(s)

        assertThat(ui.title).isEqualTo("Кухня 2 · Химки")
        assertThat(ui.subtitle).isEqualTo("online 3 ч 12 мин · Lenovo M10 · A13")
        assertThat(ui.statusTone).isEqualTo(StatusTone.Crit)
        assertThat(ui.rules).containsExactly("R-03")
        assertThat(ui.tabs).containsExactly("Состояние", "Проверки", "События", "Тень").inOrder()
        assertThat(ui.tiles.map { it.label }).containsExactly("t батареи", "Заряд", "Wi-Fi", "На экране", "Память", "Агент").inOrder()
        assertThat(ui.tiles[0].value).isEqualTo("46.8 °C")
        assertThat(ui.tiles[0].caption).isEqualTo("SEVERE")
        assertThat(ui.tiles[0].alert).isTrue()
        assertThat(ui.tiles[1].value).isEqualTo("34 %")
        assertThat(ui.tiles[1].caption).isEqualTo("⚡ от сети")
        assertThat(ui.tiles[1].alert).isFalse()
        assertThat(ui.tiles[2].value).isEqualTo("-71 dBm")
        assertThat(ui.tiles[2].caption).isEqualTo("сигнал средний")
        assertThat(ui.tiles[3].value).isEqualTo("chrome")
        assertThat(ui.tiles[4].value).isEqualTo("1.1 ГБ")
        assertThat(ui.tiles[5].value).isEqualTo("1.0.0")
        assertThat(ui.chartThreshold).isEqualTo(45.0)
        assertThat(ui.chartCaption).isEqualTo("Температура · 30 мин · порог 45")
        assertThat(ui.footer).isEqualTo("Последние данные 8 с назад")
        assertThat(ui.commandsEnabled).isTrue()
    }

    @Test
    fun `mapper handles offline device without telemetry and alert variants`() {
        val offline = mapper.map(DetailsSession(card = card(status = "offline", t = null, name = null), loading = false, knownNow = NOW))
        assertThat(offline.title).isEqualTo("d1 · Химки")
        assertThat(offline.subtitle).isEqualTo("офлайн · Lenovo M10 · A13")
        assertThat(offline.statusTone).isEqualTo(StatusTone.Off)
        assertThat(offline.footer).isEqualTo("Данных телеметрии нет")
        assertThat(offline.tiles.first().value).isEqualTo("—")

        fun tone(a: DeviceAlert) = mapper.map(DetailsSession(card = card(alerts = listOf(a)), loading = false)).statusTone
        assertThat(tone(DeviceAlert("R-06", false, null, emptyMap()))).isEqualTo(StatusTone.Warn)
        assertThat(mapper.map(DetailsSession(card = card(), loading = false)).statusTone).isEqualTo(StatusTone.Ok)
        val tiles = mapper.map(
            DetailsSession(
                card = card(alerts = listOf(DeviceAlert("R-02", false, null, emptyMap()), DeviceAlert("R-04", false, null, emptyMap()), DeviceAlert("R-06", false, null, emptyMap())),
                    t = telemetry(rssi = -90, charging = false, thermal = "NONE")),
                loading = false,
            ),
        ).tiles
        assertThat(tiles.map { it.alert }).containsExactly(false, true, false, true, true, false).inOrder()
        assertThat(tiles[1].caption).isEqualTo("от батареи")
        assertThat(tiles[2].caption).isEqualTo("сигнал слабый")
        assertThat(tiles[0].caption).isNull()
        assertThat(mapper.map(DetailsSession(card = card(t = telemetry(rssi = -50)), loading = false)).tiles[2].caption).isEqualTo("сигнал хороший")
    }

    @Test
    fun `mapper probes last per target with phases and history`() {
        val ok = ProbeResult(NOW - 60_000, "kds", true, 204, null, 212, 9, 31, 64, 108)
        val failed = ProbeResult(NOW - 30_000, "api", false, null, "timeout", 5000, null, null, null, null)
        val older = ProbeResult(NOW - 120_000, "api", true, 200, null, 100, 1, 2, 3, 4)
        val ui = mapper.map(DetailsSession(card = card(), loading = false, knownNow = NOW, probes = listOf(failed, ok, older)))

        assertThat(ui.probesLoaded).isTrue()
        assertThat(ui.probesLast.map { it.target }).containsExactly("api", "kds").inOrder()
        assertThat(ui.probesLast[0].summary).isEqualTo("timeout · 5000 мс")
        assertThat(ui.probesLast[0].ok).isFalse()
        assertThat(ui.probesLast[1].summary).isEqualTo("204 · 212 мс")
        assertThat(ui.probesLast[1].phases.map { it.ms }).containsExactly(9L, 31L, 64L, 108L).inOrder()
        assertThat(ui.probeRows).hasSize(3)
        assertThat(ui.probeRows[1].ago).isEqualTo("1 мин назад")
        assertThat(mapper.map(DetailsSession(card = card(), loading = false)).probesLoaded).isFalse()
        val noStatus = ProbeResult(1, "x", true, null, null, 5, null, null, null, null)
        assertThat(mapper.map(DetailsSession(probes = listOf(noStatus))).probesLast.single().summary).isEqualTo("ok · 5 мс")
        val noError = ProbeResult(1, "x", false, null, null, 5, null, null, null, null)
        assertThat(mapper.map(DetailsSession(probes = listOf(noError))).probesLast.single().summary).isEqualTo("ошибка · 5 мс")
    }

    @Test
    fun `mapper event texts`() {
        val events = listOf(
            DeviceEvent(NOW - 60_000, "probe_state", mapOf("target" to "api", "state" to "down", "error" to "timeout", "failures" to "3")),
            DeviceEvent(NOW - 120_000, "package_changed", mapOf("pkg" to "com.chrome", "action" to "updated")),
            DeviceEvent(NOW - 180_000, "other", mapOf("a" to "1", "b" to "2")),
        )
        val ui = mapper.map(DetailsSession(card = card(), loading = false, knownNow = NOW, events = events, eventsHasMore = true))

        assertThat(ui.eventsLoaded).isTrue()
        assertThat(ui.eventRows.map { it.text }).containsExactly("api down timeout ×3", "com.chrome · updated", "a=1 · b=2").inOrder()
        assertThat(ui.eventRows[0].ago).isEqualTo("1 мин назад")
        assertThat(ui.canLoadMoreEvents).isTrue()
        assertThat(mapper.map(DetailsSession(events = events, eventsHasMore = true, eventsLoadingMore = true)).canLoadMoreEvents).isFalse()
    }

    @Test
    fun `mapper loading, errors and shadow tab`() {
        assertThat(mapper.map(DetailsSession()).loading).isTrue()
        assertThat(mapper.map(DetailsSession()).commandsEnabled).isFalse()
        assertThat(mapper.map(DetailsSession(card = card(), loading = true)).loading).isFalse()
        fun err(e: ConsoleError) = mapper.map(DetailsSession(loading = false, error = e)).errorText
        assertThat(err(ConsoleError.Network)).isEqualTo("Нет связи с сервером")
        assertThat(err(ConsoleError.NotFound)).isEqualTo("Устройство не найдено")
        assertThat(err(ConsoleError.Unauthorized)).contains("Сессия")
        assertThat(err(ConsoleError.Forbidden)).contains("Не удалось")
        assertThat(mapper.map(DetailsSession(tab = DetailsTab.Shadow)).shadowSelected).isTrue()
        assertThat(mapper.map(DetailsSession(probesError = ConsoleError.Network)).probesErrorText).isNotNull()
        assertThat(mapper.map(DetailsSession(eventsError = ConsoleError.Network)).eventsErrorText).isNotNull()
        assertThat(mapper.map(DetailsSession(commandSheet = true)).commandSheet).isTrue()
    }

    private class Env {
        val live = MutableSharedFlow<DeviceLiveEvent>(extraBufferCapacity = 8)
        val router = mockk<IDeviceDetailsRouter>(relaxed = true)
        val loadCard = mockk<LoadDeviceCard>()
        val loadTemp = mockk<LoadTemperatureHistory>()
        val loadProbes = mockk<LoadProbes>()
        val loadEvents = mockk<LoadEvents>()
        val observe = mockk<ObserveDeviceLive>()
        var cardCalls = 0
        var probeCalls = 0
        val eventOffsets = mutableListOf<Int>()
        var cardResult: Outcome<DeviceCard, ConsoleError> = Outcome.Success(card())

        init {
            coEvery { loadCard("d1") } answers { cardCalls++; cardResult }
            coEvery { loadTemp("d1") } returns Outcome.Success(listOf(TempPoint(NOW - MIN, 40.0)))
            coEvery { loadProbes("d1") } answers {
                probeCalls++
                Outcome.Success(listOf(ProbeResult(NOW, "api", true, 200, null, 10, null, null, null, null)))
            }
            coEvery { loadEvents("d1", any()) } answers {
                val offset = secondArg<Int>()
                eventOffsets += offset
                Outcome.Success(EventsPage(listOf(DeviceEvent(offset.toLong(), "e$offset", emptyMap())), offset, offset == 0))
            }
            every { observe("d1") } returns live
        }

        fun vm() = DetailsViewModel(
            "d1", loadCard, loadTemp, loadProbes, loadEvents, observe, router,
            CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
        )
    }

    @Test
    fun `loads card and chart on start, probes and events lazily on first open`() = runTest {
        val env = Env()
        val vm = env.vm()
        assertThat(vm.uiState.value.title).isEqualTo("Кухня 2 · Химки")
        assertThat(vm.uiState.value.chart).hasSize(1)
        assertThat(env.probeCalls).isEqualTo(0)

        vm.onIntent(DetailsIntent.User.SelectTab(DetailsTab.Probes))
        vm.onIntent(DetailsIntent.User.SelectTab(DetailsTab.Probes))
        assertThat(env.probeCalls).isEqualTo(1)
        assertThat(vm.uiState.value.probesLast).hasSize(1)

        vm.onIntent(DetailsIntent.User.SelectTab(DetailsTab.Events))
        assertThat(env.eventOffsets).containsExactly(0)
        vm.onIntent(DetailsIntent.User.LoadMoreEvents)
        assertThat(env.eventOffsets).containsExactly(0, 1).inOrder()
        vm.onIntent(DetailsIntent.User.LoadMoreEvents)
        assertThat(env.eventOffsets).hasSize(2)
        assertThat(vm.uiState.value.eventRows).hasSize(2)
        vm.onDestroy()
    }

    @Test
    fun `live telemetry and status update the screen, reload signal refetches including loaded probes`() = runTest {
        val env = Env()
        val vm = env.vm()
        vm.onIntent(DetailsIntent.User.SelectTab(DetailsTab.Probes))

        env.live.emit(DeviceLiveEvent.TelemetryPushed(telemetry(ts = NOW + 1000, temp = 50.0)))
        assertThat(vm.uiState.value.tiles[0].value).isEqualTo("50 °C")
        assertThat(vm.uiState.value.chart.last().y).isEqualTo(50.0)
        env.live.emit(DeviceLiveEvent.StatusChanged("offline", 5))
        assertThat(vm.uiState.value.statusText).isEqualTo("офлайн")
        val cards = env.cardCalls
        env.live.emit(DeviceLiveEvent.ReloadNeeded)

        assertThat(env.cardCalls).isEqualTo(cards + 1)
        assertThat(env.probeCalls).isEqualTo(2)
        vm.onDestroy()
    }

    @Test
    fun `card failure is shown and refresh recovers`() = runTest {
        val env = Env().also { it.cardResult = Outcome.Failure(ConsoleError.NotFound) }
        val vm = env.vm()
        assertThat(vm.uiState.value.errorText).isEqualTo("Устройство не найдено")
        assertThat(vm.uiState.value.title).isEmpty()

        env.cardResult = Outcome.Success(card())
        vm.onIntent(DetailsIntent.User.Refresh)

        assertThat(vm.uiState.value.errorText).isNull()
        assertThat(vm.uiState.value.title).isNotEmpty()
        vm.onDestroy()
    }

    @Test
    fun `command sheet and back`() = runTest {
        val env = Env()
        val vm = env.vm()
        vm.onIntent(DetailsIntent.User.OpenCommands)
        assertThat(vm.uiState.value.commandSheet).isTrue()
        vm.onIntent(DetailsIntent.User.CloseCommands)
        assertThat(vm.uiState.value.commandSheet).isFalse()
        vm.onIntent(DetailsIntent.User.Back)
        verify { env.router.back() }
        vm.onDestroy()
    }
}
