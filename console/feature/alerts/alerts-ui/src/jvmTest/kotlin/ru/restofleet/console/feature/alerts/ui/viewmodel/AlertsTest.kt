package ru.restofleet.console.feature.alerts.ui.viewmodel

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
import ru.restofleet.console.feature.alerts.domain.AcknowledgeAlert
import ru.restofleet.console.feature.alerts.domain.AlertItem
import ru.restofleet.console.feature.alerts.domain.AlertLists
import ru.restofleet.console.feature.alerts.domain.AlertState
import ru.restofleet.console.feature.alerts.domain.AlertsEvent
import ru.restofleet.console.feature.alerts.domain.AlertsRepository
import ru.restofleet.console.feature.alerts.domain.LoadAlerts
import ru.restofleet.console.feature.alerts.domain.ObserveAlertsEvents
import ru.restofleet.console.feature.alerts.domain.ResolveAlert
import ru.restofleet.console.feature.alerts.ui.api.IAlertsRouter
import ru.restofleet.console.feature.alerts.ui.viewmodel.mapper.AlertsUiStateMapper
import ru.restofleet.shared.kernel.Outcome

private const val NOW = 10 * 60_000L

private fun alert(
    id: String, state: AlertState = AlertState.Open, rule: String = "R-03", critical: Boolean = true, opened: Long = NOW - 4 * 60_000,
    auto: Boolean = false, target: String? = null, details: Map<String, String> = mapOf("tempC" to "46.8", "thermal" to "SEVERE"),
) = AlertItem(id, rule, critical, state, "d-$id", "Кухня 2", "Химки", target, opened, null, null, null, auto, details)

class AlertsTest {
    private val reducer = AlertsReducer()
    private val mapper = AlertsUiStateMapper()
    private val a1 = alert("a1")
    private val a2 = alert("a2", AlertState.Acknowledged, "R-05", false, target = "api", details = mapOf("error" to "timeout", "failures" to "3"))
    private val a3 = alert("a3", AlertState.Resolved, "R-01", false, auto = true, details = mapOf("reason" to "lwt"))
    private val lists = AlertLists(listOf(a1), listOf(a2), listOf(a3), NOW)

    @Test
    fun `reducer load and tabs`() {
        val loaded = reducer.reduce(AlertsAction.Loaded(lists), AlertsSession())
        assertThat(loaded.loading).isFalse()
        assertThat(reducer.reduce(AlertsAction.LoadStarted, loaded).loading).isFalse()
        assertThat(reducer.reduce(AlertsAction.LoadStarted, AlertsSession()).loading).isTrue()
        assertThat(reducer.reduce(AlertsAction.LoadFailed(ConsoleError.Network), loaded).error).isEqualTo(ConsoleError.Network)
        assertThat(reducer.reduce(AlertsAction.TabSelected(AlertsTab.Resolved), loaded).tab).isEqualTo(AlertsTab.Resolved)
    }

    @Test
    fun `reducer moves a pushed alert between lists by its state`() {
        val s = AlertsSession(lists = lists, loading = false)
        val acked = reducer.reduce(AlertsAction.Pushed(a1.copy(state = AlertState.Acknowledged)), s).lists!!
        assertThat(acked.open).isEmpty()
        assertThat(acked.acknowledged.map { it.id }).containsExactly("a1", "a2")
        val resolved = reducer.reduce(AlertsAction.Pushed(a2.copy(state = AlertState.Resolved)), s).lists!!
        assertThat(resolved.acknowledged).isEmpty()
        assertThat(resolved.resolved.map { it.id }).contains("a2")
        val fresh = reducer.reduce(AlertsAction.Pushed(alert("a9", opened = NOW)), s).lists!!
        assertThat(fresh.open.map { it.id }).containsExactly("a9", "a1").inOrder()
        val unchanged = reducer.reduce(AlertsAction.Pushed(a1), AlertsSession())
        assertThat(unchanged.lists).isNull()
    }

    @Test
    fun `reducer dialog and actions`() {
        val s = AlertsSession(lists = lists, loading = false)
        val dialog = reducer.reduce(AlertsAction.ResolveRequested(a1), s)
        assertThat(dialog.resolving).isEqualTo(a1)
        val typed = reducer.reduce(AlertsAction.CommentChanged("ok"), dialog.copy(commentError = "empty"))
        assertThat(typed.comment).isEqualTo("ok")
        assertThat(typed.commentError).isNull()
        val rejected = reducer.reduce(AlertsAction.CommentRejected("empty"), typed.copy(acting = true))
        assertThat(rejected.commentError).isEqualTo("empty")
        assertThat(rejected.acting).isFalse()
        assertThat(reducer.reduce(AlertsAction.DialogDismissed, typed).resolving).isNull()
        assertThat(reducer.reduce(AlertsAction.ActionStarted, s).acting).isTrue()
        val done = reducer.reduce(AlertsAction.ActionSucceeded(a1.copy(state = AlertState.Resolved)), dialog.copy(acting = true))
        assertThat(done.resolving).isNull()
        assertThat(done.lists!!.resolved.map { it.id }).contains("a1")
        assertThat(reducer.reduce(AlertsAction.ActionFailed(ConsoleError.Network), dialog).actionError).isEqualTo(ConsoleError.Network)
    }

    @Test
    fun `mapper rows like the mockup`() {
        val ui = mapper.map(AlertsSession(lists = lists, loading = false))

        assertThat(ui.tabs).containsExactly("Открытые · 1", "Подтв. · 1", "Закрытые").inOrder()
        val row = ui.rows.single()
        assertThat(row.title).isEqualTo("Перегрев")
        assertThat(row.line).isEqualTo("Кухня 2 · Химки · 46.8 °C · SEVERE · 4 мин")
        assertThat(row.tone).isEqualTo(StatusTone.Crit)
        assertThat(row.canAcknowledge).isTrue()
        assertThat(row.canResolve).isTrue()
        assertThat(row.auto).isFalse()

        val acked = mapper.map(AlertsSession(tab = AlertsTab.Acknowledged, lists = lists, loading = false)).rows.single()
        assertThat(acked.title).isEqualTo("Цель api недоступна")
        assertThat(acked.line).contains("timeout ×3")
        assertThat(acked.canAcknowledge).isFalse()
        assertThat(acked.canResolve).isTrue()
        assertThat(acked.tone).isEqualTo(StatusTone.Warn)

        val closed = mapper.map(AlertsSession(tab = AlertsTab.Resolved, lists = lists, loading = false)).rows.single()
        assertThat(closed.auto).isTrue()
        assertThat(closed.canResolve).isFalse()
        assertThat(closed.line).contains("обрыв связи (LWT)")
    }

    @Test
    fun `mapper empty, loading, errors, dialog`() {
        val empty = AlertLists(emptyList(), emptyList(), emptyList(), NOW)
        fun empty(tab: AlertsTab) = mapper.map(AlertsSession(tab = tab, lists = empty, loading = false)).emptyText
        assertThat(empty(AlertsTab.Open)).isEqualTo("Открытых тревог нет")
        assertThat(empty(AlertsTab.Acknowledged)).isEqualTo("Подтверждённых тревог нет")
        assertThat(empty(AlertsTab.Resolved)).isEqualTo("Закрытых тревог нет")
        assertThat(mapper.map(AlertsSession()).loading).isTrue()
        assertThat(mapper.map(AlertsSession()).tabs).containsExactly("Открытые", "Подтв.", "Закрытые")
        assertThat(mapper.map(AlertsSession(lists = lists, loading = true)).loading).isFalse()
        fun err(e: ConsoleError) = mapper.map(AlertsSession(error = e)).errorText
        assertThat(err(ConsoleError.Network)).isEqualTo("Нет связи с сервером")
        assertThat(err(ConsoleError.Unauthorized)).contains("Сессия")
        assertThat(err(ConsoleError.Conflict(null))).contains("уже изменена")
        assertThat(err(ConsoleError.NotFound)).isEqualTo("Тревога не найдена")
        assertThat(err(ConsoleError.Server(500, null))).isEqualTo("Не удалось выполнить")

        val dialog = mapper.map(AlertsSession(lists = lists, resolving = a2, comment = "x", commentError = "too_long", acting = true))
        assertThat(dialog.dialogTitle).isEqualTo("Цель api недоступна")
        assertThat(dialog.commentError).isEqualTo("Комментарий длиннее 500 символов")
        assertThat(mapper.map(AlertsSession(commentError = "empty")).commentError).isEqualTo("Введите комментарий")
        assertThat(dialog.acting).isTrue()
        assertThat(mapper.map(AlertsSession(actionError = ConsoleError.Network)).actionErrorText).isNotNull()
    }

    private class Repo : AlertsRepository {
        var listsResult: Outcome<AlertLists, ConsoleError> = Outcome.Success(AlertLists(emptyList(), emptyList(), emptyList(), 0))
        var ackResult: Outcome<AlertItem, ConsoleError>? = null
        var resolveResult: Outcome<AlertItem, ConsoleError>? = null
        var loads = 0
        val calls = mutableListOf<String>()
        val events = MutableSharedFlow<AlertsEvent>(extraBufferCapacity = 8)
        override suspend fun lists(limit: Int): Outcome<AlertLists, ConsoleError> {
            loads++
            return listsResult
        }
        override suspend fun acknowledge(id: String): Outcome<AlertItem, ConsoleError> {
            calls += "ack:$id"
            return ackResult ?: Outcome.Success(alert(id, AlertState.Acknowledged))
        }
        override suspend fun resolve(id: String, comment: String): Outcome<AlertItem, ConsoleError> {
            calls += "resolve:$id:$comment"
            return resolveResult ?: Outcome.Success(alert(id, AlertState.Resolved))
        }
        override fun events() = events
    }

    private fun vm(repo: Repo, router: IAlertsRouter = mockk(relaxed = true)) = AlertsViewModel(
        LoadAlerts(repo), AcknowledgeAlert(repo), ResolveAlert(repo), ObserveAlertsEvents(repo), router,
        CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
    )

    private fun loadedRepo() = Repo().apply {
        listsResult = Outcome.Success(AlertLists(listOf(alert("a1"), alert("a4")), listOf(alert("a2", AlertState.Acknowledged)), emptyList(), NOW))
    }

    @Test
    fun `swipe right acknowledges at once and the alert moves to the second tab`() = runTest {
        val repo = loadedRepo()
        val vm = vm(repo)

        vm.onIntent(AlertsIntent.User.Acknowledge("a1"))

        assertThat(repo.calls).containsExactly("ack:a1")
        assertThat(vm.uiState.value.tabs[0]).isEqualTo("Открытые · 1")
        assertThat(vm.uiState.value.tabs[1]).isEqualTo("Подтв. · 2")
        vm.onIntent(AlertsIntent.User.Acknowledge("missing"))
        assertThat(repo.calls).hasSize(1)
        vm.onDestroy()
    }

    @Test
    fun `swipe left asks for a comment, rejects an empty one and closes with a valid one`() = runTest {
        val repo = loadedRepo()
        val vm = vm(repo)

        vm.onIntent(AlertsIntent.User.RequestResolve("a1"))
        assertThat(vm.uiState.value.dialogTitle).isEqualTo("Перегрев")
        vm.onIntent(AlertsIntent.User.ConfirmResolve)
        assertThat(vm.uiState.value.commentError).isEqualTo("Введите комментарий")
        assertThat(vm.uiState.value.acting).isFalse()
        assertThat(repo.calls).isEmpty()

        vm.onIntent(AlertsIntent.User.CommentChanged("Заменили кабель"))
        vm.onIntent(AlertsIntent.User.ConfirmResolve)

        assertThat(repo.calls).containsExactly("resolve:a1:Заменили кабель")
        assertThat(vm.uiState.value.dialogTitle).isNull()
        assertThat(vm.uiState.value.tabs[0]).isEqualTo("Открытые · 1")
        vm.onIntent(AlertsIntent.User.RequestResolve("a2"))
        vm.onIntent(AlertsIntent.User.DismissDialog)
        assertThat(vm.uiState.value.dialogTitle).isNull()
        vm.onIntent(AlertsIntent.User.ConfirmResolve)
        assertThat(repo.calls).hasSize(1)
        vm.onDestroy()
    }

    @Test
    fun `too long comment is rejected by the use case`() = runTest {
        val vm = vm(loadedRepo())
        vm.onIntent(AlertsIntent.User.RequestResolve("a1"))
        vm.onIntent(AlertsIntent.User.CommentChanged("x".repeat(501)))
        vm.onIntent(AlertsIntent.User.ConfirmResolve)
        assertThat(vm.uiState.value.commentError).isEqualTo("Комментарий длиннее 500 символов")
        vm.onDestroy()
    }

    @Test
    fun `conflict shows a message and reloads the lists`() = runTest {
        val repo = loadedRepo().apply { ackResult = Outcome.Failure(ConsoleError.Conflict("already_acknowledged")) }
        val vm = vm(repo)
        val loads = repo.loads

        vm.onIntent(AlertsIntent.User.Acknowledge("a1"))

        assertThat(vm.uiState.value.actionErrorText).contains("уже изменена")
        assertThat(repo.loads).isEqualTo(loads + 1)
        vm.onDestroy()
    }

    @Test
    fun `other failures are shown without reload`() = runTest {
        val repo = loadedRepo().apply { ackResult = Outcome.Failure(ConsoleError.Network) }
        val vm = vm(repo)
        val loads = repo.loads
        vm.onIntent(AlertsIntent.User.Acknowledge("a1"))
        assertThat(vm.uiState.value.actionErrorText).isEqualTo("Нет связи с сервером")
        assertThat(repo.loads).isEqualTo(loads)
        vm.onDestroy()
    }

    @Test
    fun `realtime pushes update tabs and reload signal refetches, tab selection and device navigation work`() = runTest {
        val repo = loadedRepo()
        val router = mockk<IAlertsRouter>(relaxed = true)
        val vm = vm(repo, router)

        repo.events.emit(AlertsEvent.Pushed(alert("a9", opened = NOW)))
        assertThat(vm.uiState.value.rows.first().id).isEqualTo("a9")
        val loads = repo.loads
        repo.events.emit(AlertsEvent.ReloadNeeded)
        assertThat(repo.loads).isEqualTo(loads + 1)
        vm.onIntent(AlertsIntent.User.Refresh)
        assertThat(repo.loads).isEqualTo(loads + 2)
        vm.onIntent(AlertsIntent.User.SelectTab(AlertsTab.Acknowledged))
        assertThat(vm.uiState.value.selectedTab).isEqualTo(1)
        vm.onIntent(AlertsIntent.User.OpenDevice("d-a1"))
        verify { router.openDevice("d-a1") }
        vm.onDestroy()
    }

    @Test
    fun `load failure is shown`() = runTest {
        val repo = Repo().apply { listsResult = Outcome.Failure(ConsoleError.Network) }
        val vm = vm(repo)
        assertThat(vm.uiState.value.errorText).isEqualTo("Нет связи с сервером")
        vm.onDestroy()
    }
}
