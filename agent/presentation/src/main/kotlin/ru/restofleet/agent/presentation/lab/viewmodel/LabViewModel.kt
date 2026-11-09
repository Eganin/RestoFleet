package ru.restofleet.agent.presentation.lab.viewmodel

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.command.Command
import ru.restofleet.agent.domain.command.Level
import ru.restofleet.agent.domain.error.MqttNotConfigured
import ru.restofleet.agent.domain.port.ForeignTopicPublisher
import ru.restofleet.agent.domain.usecase.ExportCapabilityReport
import ru.restofleet.agent.domain.usecase.GenerateDebugEvents
import ru.restofleet.agent.domain.usecase.GetDeviceInfo
import ru.restofleet.agent.domain.usecase.PublishCheckResult
import ru.restofleet.agent.domain.usecase.RunCapabilityChecks
import ru.restofleet.agent.domain.usecase.RunCommandCheck
import ru.restofleet.agent.presentation.common.BACKGROUND_LAUNCH_DELAY_MS
import ru.restofleet.agent.presentation.common.guarded
import ru.restofleet.agent.presentation.lab.viewmodel.LabIntent.User
import ru.restofleet.agent.presentation.lab.viewmodel.mapper.LabUiStateMapper
import ru.restofleet.agent.presentation.lab.viewmodel.model.LabCommand
import ru.restofleet.agent.presentation.lab.viewmodel.model.LabSession
import ru.restofleet.console.core.mvi.BaseMviViewModel
import ru.restofleet.console.core.mvi.MviStore
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/**
 * Состояние меняется только на главном потоке (scope — `Main.immediate`); тяжёлые use case идут через [io].
 * «Запустить всё» гоняет проверки по одной, чтобы строки получали статусы по мере готовности. [runCommand] — запуск
 * команд C1–C4; `null` в release-сборке: секции «Команды» нет, кнопки команд ничего не делают (решение 140).
 */
class LabViewModel(
    private val runChecks: RunCapabilityChecks,
    private val exportReport: ExportCapabilityReport,
    private val publishResult: PublishCheckResult,
    private val getDeviceInfo: GetDeviceInfo,
    private val runCommand: RunCommandCheck?,
    private val debugEvents: GenerateDebugEvents? = null,
    private val foreignTopic: ForeignTopicPublisher? = null,
    private val io: CoroutineDispatcher,
    scope: CoroutineScope = defaultScope(),
) : BaseMviViewModel<LabSession, LabUiState, LabIntent, LabAction, LabEffect>(
    store = MviStore(
        LabSession(
            commandsAvailable = runCommand != null,
            debugAvailable = debugEvents != null || foreignTopic != null,
        ),
        LabReducer(),
        LabUiStateMapper()::map,
    ),
    scope = scope,
) {
    init {
        scope.launch { reduce(LabAction.DeviceLoaded(withContext(io) { getDeviceInfo() })) }
    }

    override fun onIntent(intent: LabIntent) {
        when (intent) {
            User.RunAll -> runAll()
            is User.RunOne -> LabCommand.of(intent.id)?.let(::runCommandButton) ?: runOne(intent.id)
            is User.SelectFilter -> reduce(LabAction.FilterSelected(intent.filter))
            User.Export -> export()
            is User.SendToMqtt -> sendToMqtt(intent.id)
            is User.OpenSettings -> emitEffect(LabEffect.OpenSystemSettings(intent.target))
            is User.EditCommandPackage -> reduce(LabAction.CommandPackageChanged(intent.text))
            is User.EditCommandUrl -> reduce(LabAction.CommandUrlChanged(intent.text))
            is User.RunCommand -> runCommandButton(intent.command)
            User.PublishForeignTopic -> publishForeignTopic()
            User.GenerateDebugEvents -> generateDebugEvents()
        }
    }

    private fun runAll() {
        if (state.isRunningAll) return
        // Команды C1–C4 меняют состояние устройства (уведомление, звук, запуск приложения): их запускают кнопками
        val ids = CheckId.entries.filter { LabCommand.of(it) == null }
        reduce(LabAction.RunStarted(ids.toSet(), isAll = true))
        scope.launch {
            var firstError: Exception? = null
            try {
                for (id in ids) {
                    val results = guarded({ firstError = firstError ?: it }) { runOnIo(id) }
                    reduce(results?.let(LabAction::ResultsArrived) ?: LabAction.RunFinished(setOf(id)))
                }
            } finally {
                reduce(LabAction.RunFinished(ids.toSet()))
            }
            firstError?.let(::emitError)
        }
    }

    private fun runOne(id: CheckId) {
        if (state.isRunningAll || id in state.running) return
        reduce(LabAction.RunStarted(setOf(id)))
        scope.launch {
            try {
                guarded(::emitError) { runOnIo(id) }?.let { reduce(LabAction.ResultsArrived(it)) }
            } finally {
                reduce(LabAction.RunFinished(setOf(id)))
            }
        }
    }

    private fun runCommandButton(button: LabCommand) {
        val run = runCommand ?: return // команды подключены только в debug-сборке (решение 140)
        val id = button.checkId
        if (state.isRunningAll || id in state.running) return
        reduce(LabAction.RunStarted(setOf(id)))
        scope.launch {
            try {
                val command = button.toCommand(state.commandPackage, state.commandUrl)
                guarded(::emitError) {
                    if (button == LabCommand.LAUNCH_APP_DELAYED) delay(BACKGROUND_LAUNCH_DELAY_MS)
                    withContext(io) { run(id, command) }
                }?.let { reduce(LabAction.ResultsArrived(listOf(it))) }
            } finally {
                reduce(LabAction.RunFinished(setOf(id)))
            }
        }
    }

    private suspend fun runOnIo(id: CheckId) = withContext(io) { runChecks(setOf(id)) }

    // ExportCapabilityReport заново прогоняет все проверки (≈10 с и больше), поэтому есть признак isExporting.
    // Экспорт — отдельный прогон: во время «Запустить всё» он игнорируется (иначе две параллельные прогонки,
    // а JSON расходится с тем, что видит техник).
    private fun export() {
        if (state.isExporting || state.isRunningAll) return
        reduce(LabAction.ExportingChanged(true))
        scope.launch {
            try {
                val outcome = guarded(::emitError) { withContext(io) { exportReport() } }
                outcome?.let {
                    emitEffect(
                        LabEffect.ShowMessage(
                            when (it) {
                                is Outcome.Success -> LabMessage.Exported(it.value)
                                is Outcome.Failure -> LabMessage.Error(it.error.code)
                            },
                        ),
                    )
                }
            } finally {
                reduce(LabAction.ExportingChanged(false))
            }
        }
    }

    private fun sendToMqtt(id: CheckId) {
        val result = state.results[id]
        if (result == null) {
            emitEffect(LabEffect.ShowMessage(LabMessage.NoResult))
            return
        }
        scope.launch {
            guarded(::emitError) { withContext(io) { publishResult(result) } }
                ?.let { emitEffect(LabEffect.ShowMessage(publishMessage(id, it))) }
        }
    }

    // Только debug (M2): без реализации (release) кнопки нет, интент ничего не делает.
    private fun publishForeignTopic() {
        val publisher = foreignTopic ?: return
        scope.launch {
            guarded(::emitError) { withContext(io) { publisher.publish() } }
                ?.let { emitEffect(LabEffect.ShowMessage(LabMessage.ForeignTopic(it.accepted, it.reason))) }
        }
    }

    // Только debug (M7): серия `debug_seq` через PublishOrBuffer; офлайн копится в outbox.
    private fun generateDebugEvents() {
        val generate = debugEvents ?: return
        scope.launch {
            guarded(::emitError) { withContext(io) { generate(GenerateDebugEvents.DEFAULT_COUNT) } }
                ?.let { batchId ->
                    val sent = LabMessage.DebugEventsSent(GenerateDebugEvents.DEFAULT_COUNT, batchId)
                    emitEffect(LabEffect.ShowMessage(sent))
                }
        }
    }

    private fun emitError(e: Exception) {
        emitEffect(LabEffect.ShowMessage(LabMessage.Error(e.message ?: e::class.simpleName.orEmpty())))
    }

    private fun publishMessage(id: CheckId, outcome: Outcome<Unit, DomainError>): LabMessage = when (outcome) {
        is Outcome.Success -> LabMessage.Published(id)
        is Outcome.Failure -> when (val error = outcome.error) {
            MqttNotConfigured -> LabMessage.MqttNotConfigured
            else -> LabMessage.Error(error.code)
        }
    }
}

/** Последняя линия обороны: необработанное исключение корутины VM — в лог, а не в крах процесса агента. */
internal fun labExceptionHandler(log: (Throwable) -> Unit): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, e -> log(e) }

private fun defaultScope() = CoroutineScope(
    SupervisorJob() + Dispatchers.Main.immediate + labExceptionHandler { Log.e("LabViewModel", "unhandled", it) },
)

private const val MESSAGE_TEXT = "Проверка RestoFleet"
private const val BEEP_MS = 3_000L

private fun LabCommand.toCommand(pkg: String, url: String): Command = when (this) {
    LabCommand.SHOW_MESSAGE -> Command.ShowMessage(MESSAGE_TEXT, Level.WARNING)
    LabCommand.LAUNCH_APP, LabCommand.LAUNCH_APP_DELAYED -> Command.LaunchApp(pkg.trim(), url.trim().ifEmpty { null })
    LabCommand.BEEP -> Command.Beep(BEEP_MS)
}
