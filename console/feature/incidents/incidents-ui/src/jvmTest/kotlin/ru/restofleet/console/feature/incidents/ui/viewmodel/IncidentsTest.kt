package ru.restofleet.console.feature.incidents.ui.viewmodel

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
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
import ru.restofleet.console.feature.incidents.domain.AcknowledgeIncident
import ru.restofleet.console.feature.incidents.domain.Incident
import ru.restofleet.console.feature.incidents.domain.IncidentDetails
import ru.restofleet.console.feature.incidents.domain.IncidentEvidence
import ru.restofleet.console.feature.incidents.domain.IncidentKind
import ru.restofleet.console.feature.incidents.domain.IncidentLists
import ru.restofleet.console.feature.incidents.domain.IncidentsEvent
import ru.restofleet.console.feature.incidents.domain.IncidentsRepository
import ru.restofleet.console.feature.incidents.domain.LoadIncidentDetails
import ru.restofleet.console.feature.incidents.domain.LoadIncidents
import ru.restofleet.console.feature.incidents.domain.ObserveIncidentsEvents
import ru.restofleet.console.feature.incidents.domain.RestaurantEvidence
import ru.restofleet.console.feature.incidents.domain.RestaurantRef
import ru.restofleet.console.feature.incidents.domain.Thresholds
import ru.restofleet.console.feature.incidents.ui.api.IIncidentDetailsRouter
import ru.restofleet.console.feature.incidents.ui.api.IIncidentsRouter
import ru.restofleet.console.feature.incidents.ui.details.IncidentDetailsAction
import ru.restofleet.console.feature.incidents.ui.details.IncidentDetailsIntent
import ru.restofleet.console.feature.incidents.ui.details.IncidentDetailsReducer
import ru.restofleet.console.feature.incidents.ui.details.IncidentDetailsSession
import ru.restofleet.console.feature.incidents.ui.details.IncidentDetailsUiStateMapper
import ru.restofleet.console.feature.incidents.ui.details.IncidentDetailsViewModel
import ru.restofleet.console.feature.incidents.ui.details.IncidentExplanationMapper
import ru.restofleet.console.feature.incidents.ui.viewmodel.mapper.IncidentsUiStateMapper
import ru.restofleet.shared.kernel.Outcome

private const val HOUR = 3_600_000L
private const val NOW = 10 * HOUR

private val sokol = RestaurantRef("r1", "MSK-01", "Сокол")
private val himki = RestaurantRef("r2", "MSK-02", "Химки")

private fun incident(
    id: String = "i1", kind: IncidentKind = IncidentKind.BackendGlobal, rule: String = "R-08", resolved: Boolean = false,
    restaurants: List<RestaurantRef> = List(17) { RestaurantRef("r$it", "C$it", "Р$it") }, started: Long = NOW - 2 * HOUR,
    resolvedAt: Long? = null, reason: String? = null, suppressed: Int = 6, ackBy: String? = null, comment: String? = null,
) = Incident(id, kind, rule, resolved, "api", restaurants, started, resolvedAt, reason, suppressed, ackBy, comment)

private fun ev(name: String, total: Int, online: Int, tOnline: Int, tDown: Int, refOnline: Int, refUp: Int, errors: Map<String, Int> = emptyMap()) =
    RestaurantEvidence(name, total, online, tOnline, tDown, refOnline, refUp, errors)

class IncidentsTest {
    private val reducer = IncidentsReducer()
    private val mapper = IncidentsUiStateMapper()

    @Test
    fun `reducer load and upsert between lists`() {
        val lists = IncidentLists(listOf(incident("a")), listOf(incident("b", resolved = true)), NOW)
        val loaded = reducer.reduce(IncidentsAction.Loaded(lists), IncidentsSession())
        assertThat(loaded.loading).isFalse()
        assertThat(reducer.reduce(IncidentsAction.LoadStarted, loaded).loading).isFalse()
        assertThat(reducer.reduce(IncidentsAction.LoadStarted, IncidentsSession()).loading).isTrue()
        assertThat(reducer.reduce(IncidentsAction.LoadFailed(ConsoleError.Network), loaded).error).isEqualTo(ConsoleError.Network)

        val closed = reducer.reduce(IncidentsAction.Pushed(incident("a", resolved = true)), loaded).lists!!
        assertThat(closed.open).isEmpty()
        assertThat(closed.resolved.map { it.id }).containsExactly("a", "b")
        val fresh = reducer.reduce(IncidentsAction.Pushed(incident("c", started = NOW)), loaded).lists!!
        assertThat(fresh.open.map { it.id }).containsExactly("c", "a").inOrder()
        assertThat(reducer.reduce(IncidentsAction.Pushed(incident("c")), IncidentsSession()).lists).isNull()
    }

    @Test
    fun `mapper rows by kind`() {
        val lists = IncidentLists(
            listOf(
                incident("g"),
                incident("n", IncidentKind.RestaurantNetwork, "R-07", restaurants = listOf(sokol), suppressed = 0),
                incident("l", IncidentKind.BackendLocal, "R-09", restaurants = listOf(himki, sokol)),
                incident("u", IncidentKind.Unknown, "R-42", restaurants = emptyList()),
            ),
            listOf(incident("r", IncidentKind.RestaurantNetwork, "R-07", true, listOf(sokol), NOW - 3 * HOUR, NOW - 2 * HOUR, "cleared", 0)),
            NOW,
        )
        val ui = mapper.map(IncidentsSession(lists = lists, loading = false))

        assertThat(ui.open.map { it.title }).containsExactly(
            "Бэкенд недоступен · 17 ресторанов", "Сеть ресторана · Сокол", "Бэкенд недоступен · Химки, Сокол", "Инцидент R-42",
        ).inOrder()
        assertThat(ui.open[0].subtitle).isEqualTo("api · идёт 2 ч · скрыто тревог: 6")
        assertThat(ui.open[1].subtitle).isEqualTo("api · идёт 2 ч")
        assertThat(ui.open[0].tone).isEqualTo(StatusTone.Crit)
        assertThat(ui.open[1].tone).isEqualTo(StatusTone.Warn)
        assertThat(ui.resolved.single().subtitle).isEqualTo("api · длился 1 ч")
        assertThat(ui.resolved.single().tone).isEqualTo(StatusTone.Off)
        assertThat(ui.emptyText).isNull()
    }

    @Test
    fun `mapper empty, loading, error`() {
        assertThat(mapper.map(IncidentsSession(lists = IncidentLists(emptyList(), emptyList(), NOW), loading = false)).emptyText)
            .isEqualTo("Инцидентов нет")
        assertThat(mapper.map(IncidentsSession()).loading).isTrue()
        assertThat(mapper.map(IncidentsSession(error = ConsoleError.Network)).errorText).isEqualTo("Нет связи с сервером")
        assertThat(mapper.map(IncidentsSession(error = ConsoleError.Forbidden)).errorText).contains("Не удалось")
    }

    private class Repo : IncidentsRepository {
        var lists: Outcome<IncidentLists, ConsoleError> = Outcome.Success(IncidentLists(listOf(incident("i1")), emptyList(), NOW))
        var loads = 0
        override suspend fun lists(): Outcome<IncidentLists, ConsoleError> {
            loads++
            return lists
        }
        override suspend fun details(id: String): Outcome<IncidentDetails, ConsoleError> = error("not used")
        override suspend fun acknowledge(id: String, comment: String?): Outcome<Incident, ConsoleError> = error("not used")
        val events = MutableSharedFlow<IncidentsEvent>(extraBufferCapacity = 8)
        override fun events() = events
    }

    @Test
    fun `list view model loads, reloads on signal, applies pushes and opens an incident`() = runTest {
        val repo = Repo()
        val router = mockk<IIncidentsRouter>(relaxed = true)
        val vm = IncidentsViewModel(
            LoadIncidents(repo), ObserveIncidentsEvents(repo), router, CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
        )
        assertThat(vm.uiState.value.open).hasSize(1)

        repo.events.emit(IncidentsEvent.Pushed(incident("i2", started = NOW)))
        assertThat(vm.uiState.value.open.map { it.id }).containsExactly("i2", "i1").inOrder()
        repo.events.emit(IncidentsEvent.ReloadNeeded)
        assertThat(repo.loads).isEqualTo(2)
        vm.onIntent(IncidentsIntent.User.Refresh)
        assertThat(repo.loads).isEqualTo(3)
        vm.onIntent(IncidentsIntent.User.Open("i2"))
        verify { router.openIncident("i2") }

        repo.lists = Outcome.Failure(ConsoleError.Network)
        vm.onIntent(IncidentsIntent.User.Refresh)
        assertThat(vm.uiState.value.errorText).isEqualTo("Нет связи с сервером")
        vm.onDestroy()
    }
}

class IncidentExplanationTest {
    private val explain = IncidentExplanationMapper()
    private val thresholds = Thresholds(50, 10, 38)

    private fun details(i: Incident, restaurants: List<RestaurantEvidence>, ok: Int = 21) =
        IncidentDetails(i, IncidentEvidence(NOW, restaurants, ok, thresholds))

    @Test
    fun `restaurant network explanation`() {
        val d = details(
            incident("n", IncidentKind.RestaurantNetwork, "R-07", restaurants = listOf(sokol)),
            listOf(ev("Сокол", 6, 1, 6, 0, 6, 0)),
        )
        assertThat(explain.explain(d)).containsExactly(
            "Сокол: на связи 1 из 6 устройств; эталон (ref) недоступен у 6 из 6.",
            "Пропала сеть ресторана: эталонный адрес не отвечает, значит бэкенд ни при чём.",
        ).inOrder()
    }

    @Test
    fun `backend global explanation`() {
        val d = details(
            incident("g", IncidentKind.BackendGlobal, "R-08", restaurants = listOf(sokol, himki), suppressed = 34),
            listOf(ev("Сокол", 6, 6, 6, 4, 6, 6, mapOf("timeout" to 3, "dns" to 1)), ev("Химки", 4, 4, 4, 4, 4, 4, mapOf("timeout" to 4))),
            ok = 21,
        )
        assertThat(explain.explain(d)).containsExactly(
            "Цель api недоступна у ≥ 50 % онлайн-устройств при живом эталоне в 2 ресторанах (для общего отказа нужно не меньше 10 из 38).",
            "Остальных ресторанов без проблем: 21.",
            "Сокол: цель не отвечает на 4 из 6 устройств (timeout ×3, dns ×1).",
            "Химки: цель не отвечает на 4 из 4 устройств (timeout ×4).",
            "Одна причина вместо россыпи тревог: отказ на стороне бэкенда, скрыто тревог — 34.",
        ).inOrder()
        assertThat(explain.errors(d)).isEqualTo("timeout ×7, dns ×1")
    }

    @Test
    fun `backend local explanation`() {
        val d = details(
            incident("l", IncidentKind.BackendLocal, "R-09", restaurants = listOf(sokol)),
            listOf(ev("Сокол", 6, 6, 6, 5, 6, 6)),
        )
        assertThat(explain.explain(d)).containsExactly(
            "Цель api недоступна при живом эталоне в ресторанах: 1; для общего отказа нужно не меньше 10, поэтому инцидент локальный.",
            "Сокол: цель не отвечает на 5 из 6 устройств.",
        ).inOrder()
        assertThat(explain.errors(d)).isNull()
    }

    @Test
    fun `unknown kind explanation`() {
        val d = details(incident("u", IncidentKind.Unknown, "R-42", restaurants = emptyList()), emptyList())
        assertThat(explain.explain(d).single()).contains("R-42")
    }
}

class IncidentDetailsTest {
    private val reducer = IncidentDetailsReducer()
    private val mapper = IncidentDetailsUiStateMapper()
    private val open = IncidentDetails(
        incident("i1", IncidentKind.BackendLocal, "R-09", restaurants = listOf(sokol)), IncidentEvidence(NOW, listOf(ev("Сокол", 6, 6, 6, 5, 6, 6, mapOf("timeout" to 5))), 3, Thresholds(50, 10, 38)),
    )

    @Test
    fun `reducer table`() {
        val loaded = reducer.reduce(IncidentDetailsAction.Loaded(open), IncidentDetailsSession())
        assertThat(loaded.loading).isFalse()
        assertThat(reducer.reduce(IncidentDetailsAction.LoadStarted, loaded).loading).isFalse()
        assertThat(reducer.reduce(IncidentDetailsAction.LoadStarted, IncidentDetailsSession()).loading).isTrue()
        assertThat(reducer.reduce(IncidentDetailsAction.LoadFailed(ConsoleError.NotFound), loaded).error).isEqualTo(ConsoleError.NotFound)
        val typed = reducer.reduce(IncidentDetailsAction.CommentChanged("ок"), loaded.copy(ackError = ConsoleError.Network))
        assertThat(typed.comment).isEqualTo("ок")
        assertThat(typed.ackError).isNull()
        assertThat(reducer.reduce(IncidentDetailsAction.AckStarted, typed).acknowledging).isTrue()
        val acked = reducer.reduce(IncidentDetailsAction.Acknowledged(open.incident.copy(ackBy = "me", comment = "ок")), typed.copy(acknowledging = true))
        assertThat(acked.details!!.incident.ackBy).isEqualTo("me")
        assertThat(acked.comment).isEmpty()
        assertThat(acked.acknowledging).isFalse()
        assertThat(reducer.reduce(IncidentDetailsAction.AckFailed(ConsoleError.Conflict(null)), typed).ackError).isEqualTo(ConsoleError.Conflict(null))
    }

    @Test
    fun `mapper open, acknowledged and resolved incident`() {
        val ui = mapper.map(IncidentDetailsSession(details = open, loading = false))
        assertThat(ui.title).isEqualTo("Бэкенд недоступен · Сокол")
        assertThat(ui.statusText).isEqualTo("Открыт")
        assertThat(ui.restaurants).isEqualTo("Сокол (MSK-01)")
        assertThat(ui.canAcknowledge).isTrue()
        assertThat(ui.errorsLine).isEqualTo("Ошибки проверок: timeout ×5")
        assertThat(ui.explanation).isNotEmpty()
        assertThat(ui.ackInfo).isNull()

        val acked = mapper.map(IncidentDetailsSession(details = open.copy(incident = open.incident.copy(ackBy = "support", comment = "Смотрим"))))
        assertThat(acked.ackInfo).isEqualTo("Подтверждено: support — Смотрим")
        assertThat(acked.canAcknowledge).isFalse()
        assertThat(mapper.map(IncidentDetailsSession(details = open.copy(incident = open.incident.copy(ackBy = "x")))).ackInfo).isEqualTo("Подтверждено: x")

        fun resolved(reason: String?, at: Long?) = mapper.map(
            IncidentDetailsSession(details = open.copy(incident = open.incident.copy(resolved = true, resolvedAt = at, resolveReason = reason))),
        )
        assertThat(resolved("cleared", NOW - HOUR).statusText).isEqualTo("Закрыт · условие исчезло · длился 1 ч")
        assertThat(resolved("superseded", null).statusText).isEqualTo("Закрыт · заменён другим инцидентом")
        assertThat(resolved("other", null).statusText).isEqualTo("Закрыт · other")
        assertThat(resolved(null, null).statusText).isEqualTo("Закрыт")
        assertThat(resolved(null, null).canAcknowledge).isFalse()
    }

    @Test
    fun `mapper loading and errors`() {
        assertThat(mapper.map(IncidentDetailsSession()).loading).isTrue()
        assertThat(mapper.map(IncidentDetailsSession()).statusTone).isEqualTo(StatusTone.Off)
        assertThat(mapper.map(IncidentDetailsSession(details = open, loading = true)).loading).isFalse()
        fun err(e: ConsoleError) = mapper.map(IncidentDetailsSession(error = e)).errorText
        assertThat(err(ConsoleError.Network)).isEqualTo("Нет связи с сервером")
        assertThat(err(ConsoleError.NotFound)).isEqualTo("Инцидент не найден")
        assertThat(err(ConsoleError.Conflict(null))).contains("уже подтверждён")
        assertThat(err(ConsoleError.Forbidden)).isEqualTo("Не удалось выполнить")
        val tooLong = mapper.map(IncidentDetailsSession(details = open, ackError = ConsoleError.InvalidInput("comment", "too_long")))
        assertThat(tooLong.commentError).isEqualTo("Комментарий длиннее 500 символов")
        assertThat(tooLong.ackErrorText).isNull()
        assertThat(mapper.map(IncidentDetailsSession(details = open, ackError = ConsoleError.Network)).ackErrorText).isNotNull()
    }

    private class Repo(var details: Outcome<IncidentDetails, ConsoleError>) : IncidentsRepository {
        var ack: Pair<String, String?>? = null
        var ackResult: Outcome<Incident, ConsoleError>? = null
        var loads = 0
        val events = MutableSharedFlow<IncidentsEvent>(extraBufferCapacity = 8)
        override suspend fun lists(): Outcome<IncidentLists, ConsoleError> = error("not used")
        override suspend fun details(id: String): Outcome<IncidentDetails, ConsoleError> {
            loads++
            return details
        }
        override suspend fun acknowledge(id: String, comment: String?): Outcome<Incident, ConsoleError> {
            ack = id to comment
            return ackResult ?: Outcome.Success(incident(id, ackBy = "me", comment = comment))
        }
        override fun events() = events
    }

    private fun vm(repo: Repo, router: IIncidentDetailsRouter = mockk(relaxed = true)) = IncidentDetailsViewModel(
        "i1", LoadIncidentDetails(repo), AcknowledgeIncident(repo), ObserveIncidentsEvents(repo), router,
        CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
    )

    @Test
    fun `view model loads, acknowledges with a comment and ignores a second acknowledge`() = runTest {
        val repo = Repo(Outcome.Success(open))
        val vm = vm(repo)
        assertThat(vm.uiState.value.canAcknowledge).isTrue()

        vm.onIntent(IncidentDetailsIntent.User.CommentChanged(" Провайдер подтвердил "))
        vm.onIntent(IncidentDetailsIntent.User.Acknowledge)

        assertThat(repo.ack).isEqualTo("i1" to "Провайдер подтвердил")
        assertThat(vm.uiState.value.ackInfo).isEqualTo("Подтверждено: me — Провайдер подтвердил")
        assertThat(vm.uiState.value.canAcknowledge).isFalse()
        repo.ack = null
        vm.onIntent(IncidentDetailsIntent.User.Acknowledge)
        assertThat(repo.ack).isNull()
        vm.onDestroy()
    }

    @Test
    fun `conflict reloads, long comment is rejected, resolved incident cannot be acknowledged`() = runTest {
        val repo = Repo(Outcome.Success(open)).apply { ackResult = Outcome.Failure(ConsoleError.Conflict("already")) }
        val vm = vm(repo)
        val loads = repo.loads
        vm.onIntent(IncidentDetailsIntent.User.Acknowledge)
        assertThat(vm.uiState.value.ackErrorText).contains("уже подтверждён")
        assertThat(repo.loads).isEqualTo(loads + 1)

        vm.onIntent(IncidentDetailsIntent.User.CommentChanged("x".repeat(501)))
        vm.onIntent(IncidentDetailsIntent.User.Acknowledge)
        assertThat(vm.uiState.value.commentError).isEqualTo("Комментарий длиннее 500 символов")
        vm.onDestroy()

        val closed = Repo(Outcome.Success(open.copy(incident = open.incident.copy(resolved = true))))
        val vm2 = vm(closed)
        vm2.onIntent(IncidentDetailsIntent.User.Acknowledge)
        assertThat(closed.ack).isNull()
        vm2.onDestroy()
    }

    @Test
    fun `realtime updates of this incident or a resync reload the card, others do not`() = runTest {
        val repo = Repo(Outcome.Success(open))
        val vm = vm(repo)
        repo.events.emit(IncidentsEvent.Pushed(incident("other")))
        assertThat(repo.loads).isEqualTo(1)
        repo.events.emit(IncidentsEvent.Pushed(incident("i1")))
        assertThat(repo.loads).isEqualTo(2)
        repo.events.emit(IncidentsEvent.ReloadNeeded)
        assertThat(repo.loads).isEqualTo(3)
        vm.onIntent(IncidentDetailsIntent.User.Refresh)
        assertThat(repo.loads).isEqualTo(4)
        vm.onDestroy()
    }

    @Test
    fun `load failure is shown and back goes to the router`() = runTest {
        val repo = Repo(Outcome.Failure(ConsoleError.NotFound))
        val router = mockk<IIncidentDetailsRouter>(relaxed = true)
        val vm = vm(repo, router)
        assertThat(vm.uiState.value.errorText).isEqualTo("Инцидент не найден")
        vm.onIntent(IncidentDetailsIntent.User.Back)
        verify { router.back() }
        vm.effect.test { cancelAndIgnoreRemainingEvents() }
        vm.onDestroy()
    }
}
