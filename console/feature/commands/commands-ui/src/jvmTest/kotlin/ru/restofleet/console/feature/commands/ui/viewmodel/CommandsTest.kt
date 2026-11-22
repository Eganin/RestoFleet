package ru.restofleet.console.feature.commands.ui.viewmodel

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.designsystem.StatusTone
import ru.restofleet.console.feature.commands.domain.AvailableCommands
import ru.restofleet.console.feature.commands.domain.CommandRecord
import ru.restofleet.console.feature.commands.domain.CommandState
import ru.restofleet.console.feature.commands.domain.CommandType
import ru.restofleet.console.feature.commands.domain.CommandsRepository
import ru.restofleet.console.feature.commands.domain.LoadCommandsOverview
import ru.restofleet.console.feature.commands.domain.RefreshHistory
import ru.restofleet.console.feature.commands.domain.SendCommand
import ru.restofleet.console.feature.commands.domain.TrackCommand
import ru.restofleet.console.feature.commands.ui.viewmodel.mapper.CommandsUiStateMapper
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome

class CommandsTest {
    private fun rec(
        id: String = "c1", state: CommandState = CommandState.Queued, type: String = "ping", delivered: Long? = null,
        finished: Long? = null, reason: String? = null,
    ) = CommandRecord(id, type, state, 1_000L, delivered, finished, reason, emptyMap())

    private val specs = AvailableCommands()("normal")
    private val reducer = CommandsReducer()
    private val mapper = CommandsUiStateMapper()

    @Test
    fun `reducer table`() {
        val loaded = reducer.reduce(CommandsAction.Loaded(specs, listOf(rec("a"))), CommandsSession())
        assertThat(loaded.loading).isFalse()
        assertThat(reducer.reduce(CommandsAction.LoadStarted, loaded).loading).isFalse()
        assertThat(reducer.reduce(CommandsAction.LoadStarted, CommandsSession()).loading).isTrue()
        assertThat(reducer.reduce(CommandsAction.LoadFailed(ConsoleError.Network), loaded).error).isEqualTo(ConsoleError.Network)
        assertThat(reducer.reduce(CommandsAction.HistoryLoaded(listOf(rec("z"))), loaded).history.single().cmdId).isEqualTo("z")

        val form = reducer.reduce(CommandsAction.FormOpened(CommandType.ShowMessage), loaded)
        assertThat(form.form).isEqualTo(CommandType.ShowMessage)
        val typed = reducer.reduce(CommandsAction.InputChanged("text", "Привет"), form)
        assertThat(typed.inputs).containsExactly("text", "Привет")
        assertThat(reducer.reduce(CommandsAction.FormCancelled, typed).form).isNull()
        assertThat(reducer.reduce(CommandsAction.SendStarted, typed).sending).isTrue()
        val failed = reducer.reduce(CommandsAction.SendFailed(ConsoleError.Network), typed.copy(sending = true))
        assertThat(failed.sending).isFalse()
        assertThat(failed.sendError).isEqualTo(ConsoleError.Network)
        val sent = reducer.reduce(CommandsAction.SendSucceeded(rec("b")), typed.copy(sending = true))
        assertThat(sent.history.map { it.cmdId }).containsExactly("b", "a").inOrder()
        assertThat(sent.form).isNull()
        assertThat(sent.inputs).isEmpty()
        val updated = reducer.reduce(CommandsAction.RecordUpdated(rec("b", CommandState.Succeeded)), sent)
        assertThat(updated.history.first().state).isEqualTo(CommandState.Succeeded)
        assertThat(updated.history.last().cmdId).isEqualTo("a")
    }

    @Test
    fun `mapper rows like the mockup`() {
        fun text(r: CommandRecord) = mapper.map(CommandsSession(history = listOf(r), loading = false)).history.single()
        val ok = text(rec(state = CommandState.Succeeded, type = "show_message", delivered = 1_400, finished = 2_200))
        assertThat(ok.text).isEqualTo("delivered 0.4 с → succeeded 1.2 с")
        assertThat(ok.tone).isEqualTo(StatusTone.Ok)
        assertThat(ok.statusText).isEqualTo("succeeded")
        assertThat(text(rec(state = CommandState.Expired, finished = 301_000)).text).isEqualTo("expired")
        assertThat(text(rec(state = CommandState.Expired)).tone).isEqualTo(StatusTone.Warn)
        assertThat(text(rec()).text).isEqualTo("в очереди")
        assertThat(text(rec(state = CommandState.Delivered, delivered = 1_100)).text).isEqualTo("delivered 0.1 с")
        assertThat(text(rec(state = CommandState.Delivered, delivered = 1_100)).tone).isEqualTo(StatusTone.Off)
        val unsupported = text(rec(state = CommandState.Unsupported, reason = "requires_device_owner", finished = 1_300))
        assertThat(unsupported.text).isEqualTo("unsupported 0.3 с · нужен Device Owner")
        assertThat(unsupported.tone).isEqualTo(StatusTone.Crit)
        assertThat(text(rec(state = CommandState.Failed, reason = "kiosk_active")).text).isEqualTo("failed · включён киоск")
        assertThat(text(rec(state = CommandState.Failed, reason = "boom", finished = 500)).text).isEqualTo("failed 0.0 с · boom")
    }

    @Test
    fun `mapper specs explain disabled commands`() {
        val ui = mapper.map(CommandsSession(specs = AvailableCommands()("normal"), loading = false))
        assertThat(ui.specs.map { it.title }).containsExactly(
            "Ping", "Показать сообщение", "Собрать диагностику", "Открыть приложение", "Перезагрузить", "Киоск", "Установить обновление",
        ).inOrder()
        assertThat(ui.specs.first { it.type == CommandType.Reboot }.enabled).isFalse()
        assertThat(ui.specs.first { it.type == CommandType.Reboot }.reason).isEqualTo("Нужен режим Device Owner")
        assertThat(ui.specs.first { it.type == CommandType.Ping }.reason).isNull()
        val owner = mapper.map(CommandsSession(specs = AvailableCommands()("device_owner"), loading = false)).specs
        assertThat(owner.first { it.type == CommandType.Reboot }.enabled).isTrue()
        assertThat(owner.first { it.type == CommandType.LockTask }.reason).contains("диплом")
        assertThat(ui.specs.all { it.subtitle.isNotBlank() }).isTrue()
    }

    @Test
    fun `mapper form fields and errors`() {
        val base = CommandsSession(form = CommandType.ShowMessage, inputs = mapOf("text" to "Привет"), loading = false)
        val ui = mapper.map(base)
        assertThat(ui.formTitle).isEqualTo("Показать сообщение")
        assertThat(ui.fields.single().label).isEqualTo("Текст сообщения")
        assertThat(ui.fields.single().value).isEqualTo("Привет")
        assertThat(ui.fields.single().error).isFalse()
        val missing = mapper.map(base.copy(inputs = emptyMap(), sendError = ConsoleError.InvalidInput("text", "empty")))
        assertThat(missing.fields.single().error).isTrue()
        assertThat(missing.sendErrorText).isEqualTo("Заполните поле «Текст сообщения»")
        assertThat(mapper.map(CommandsSession(form = CommandType.InstallUpdate)).fields.map { it.label })
            .containsExactly("Пакет", "Адрес APK", "SHA-256").inOrder()
        assertThat(mapper.map(CommandsSession(form = CommandType.LaunchApp)).fields.single().label).isEqualTo("Пакет приложения")
        fun err(e: ConsoleError) = mapper.map(CommandsSession(sendError = e)).sendErrorText
        assertThat(err(ConsoleError.Conflict("device_offline"))).isEqualTo("Устройство не принимает команду: device_offline")
        assertThat(err(ConsoleError.Conflict(null))).contains("конфликт")
        assertThat(err(ConsoleError.Validation("p", "bad"))).contains("bad")
        assertThat(err(ConsoleError.Network)).isEqualTo("Нет связи с сервером")
        assertThat(err(ConsoleError.Forbidden)).isEqualTo("Недостаточно прав")
        assertThat(err(ConsoleError.NotFound)).isEqualTo("Устройство не найдено")
        assertThat(err(ConsoleError.Server(500, null))).isEqualTo("Не удалось выполнить")
        assertThat(mapper.map(CommandsSession(error = ConsoleError.Network)).errorText).isEqualTo("Нет связи с сервером")
        assertThat(mapper.map(CommandsSession()).formTitle).isNull()
    }

    private class FakeRepo : CommandsRepository {
        var mode: Outcome<String?, ConsoleError> = Outcome.Success("normal")
        var sendResult: Outcome<CommandRecord, ConsoleError>? = null
        val sent = mutableListOf<Pair<CommandType, Map<String, String>>>()
        val statuses = ArrayDeque<CommandRecord>()
        override suspend fun mode(deviceId: String) = mode
        override suspend fun send(deviceId: String, type: CommandType, params: Map<String, String>): Outcome<CommandRecord, ConsoleError> {
            sent += type to params
            return sendResult ?: Outcome.Success(CommandRecord("c9", type.wire, CommandState.Queued, 1_000L, null, null, null, emptyMap()))
        }
        override suspend fun history(deviceId: String, limit: Int) = Outcome.Success(emptyList<CommandRecord>())
        override suspend fun status(cmdId: String): Outcome<CommandRecord, ConsoleError> =
            statuses.removeFirstOrNull()?.let { Outcome.Success(it) } ?: Outcome.Failure(ConsoleError.Network)
    }

    private fun vm(repo: FakeRepo): CommandsViewModel {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        return CommandsViewModel(
            "d1", LoadCommandsOverview(repo, AvailableCommands()), RefreshHistory(repo), SendCommand(repo),
            TrackCommand(repo, Clock { 0L }), scope,
        )
    }

    @Test
    fun `parameterless command is sent at once and tracked to the final state`() = runTest {
        val repo = FakeRepo().apply {
            statuses += CommandRecord("c9", "ping", CommandState.Succeeded, 1_000L, 1_400L, 2_000L, null, emptyMap())
        }
        val vm = vm(repo)

        vm.onIntent(CommandsIntent.User.Select(CommandType.Ping))

        assertThat(repo.sent).containsExactly(CommandType.Ping to emptyMap<String, String>())
        assertThat(vm.uiState.value.history.single().statusText).isEqualTo("succeeded")
        assertThat(vm.uiState.value.sending).isFalse()
        vm.onDestroy()
    }

    @Test
    fun `command with params opens a form, validates and sends`() = runTest {
        val repo = FakeRepo().apply { statuses += CommandRecord("c9", "show_message", CommandState.Delivered, 1_000L, 1_300L, null, null, emptyMap()) }
        val vm = vm(repo)

        vm.onIntent(CommandsIntent.User.Select(CommandType.ShowMessage))
        assertThat(vm.uiState.value.formTitle).isEqualTo("Показать сообщение")
        vm.onIntent(CommandsIntent.User.Submit)
        assertThat(vm.uiState.value.sendErrorText).isEqualTo("Заполните поле «Текст сообщения»")
        assertThat(repo.sent).isEmpty()

        vm.onIntent(CommandsIntent.User.InputChanged("text", "Проверьте планшет"))
        vm.onIntent(CommandsIntent.User.Submit)

        assertThat(repo.sent.single().second).containsExactly("text", "Проверьте планшет")
        assertThat(vm.uiState.value.formTitle).isNull()
        assertThat(vm.uiState.value.history.single().text).isEqualTo("delivered 0.3 с")
        vm.onDestroy()
    }

    @Test
    fun `disabled command is ignored, cancel closes the form, server failure is shown`() = runTest {
        val repo = FakeRepo().apply { sendResult = Outcome.Failure(ConsoleError.Conflict("device_offline")) }
        val vm = vm(repo)

        vm.onIntent(CommandsIntent.User.Select(CommandType.Reboot))
        assertThat(repo.sent).isEmpty()
        assertThat(vm.uiState.value.formTitle).isNull()

        vm.onIntent(CommandsIntent.User.Select(CommandType.LaunchApp))
        vm.onIntent(CommandsIntent.User.CancelForm)
        assertThat(vm.uiState.value.formTitle).isNull()

        vm.onIntent(CommandsIntent.User.Select(CommandType.Ping))
        assertThat(vm.uiState.value.sendErrorText).contains("device_offline")
        assertThat(vm.uiState.value.sending).isFalse()
        vm.onDestroy()
    }

    @Test
    fun `overview failure is shown and refresh retries`() = runTest {
        val repo = FakeRepo().apply { mode = Outcome.Failure(ConsoleError.Network) }
        val vm = vm(repo)
        assertThat(vm.uiState.value.errorText).isEqualTo("Нет связи с сервером")

        repo.mode = Outcome.Success("device_owner")
        vm.onIntent(CommandsIntent.User.Refresh)

        assertThat(vm.uiState.value.errorText).isNull()
        assertThat(vm.uiState.value.specs.first { it.type == CommandType.Reboot }.enabled).isTrue()
        vm.onDestroy()
    }
}
