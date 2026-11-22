package ru.restofleet.console.feature.commands.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.restofleet.console.core.mvi.BaseMviViewModel
import ru.restofleet.console.core.mvi.MviStore
import ru.restofleet.console.core.mvi.defaultViewModelScope
import ru.restofleet.console.feature.commands.domain.CommandType
import ru.restofleet.console.feature.commands.domain.LoadCommandsOverview
import ru.restofleet.console.feature.commands.domain.RefreshHistory
import ru.restofleet.console.feature.commands.domain.SendCommand
import ru.restofleet.console.feature.commands.domain.TrackCommand
import ru.restofleet.console.feature.commands.ui.viewmodel.mapper.CommandsUiStateMapper
import ru.restofleet.shared.kernel.Outcome

class CommandsViewModel(
    private val deviceId: String,
    private val loadOverview: LoadCommandsOverview,
    private val refreshHistory: RefreshHistory,
    private val sendCommand: SendCommand,
    private val trackCommand: TrackCommand,
    scope: CoroutineScope = defaultViewModelScope(),
) : BaseMviViewModel<CommandsSession, CommandsUiState, CommandsIntent, CommandsAction, CommandsEffect>(
    store = MviStore(CommandsSession(), CommandsReducer(), CommandsUiStateMapper()::map),
    scope = scope,
) {
    init {
        load()
    }

    override fun onIntent(intent: CommandsIntent) {
        when (intent) {
            is CommandsIntent.User.Select -> select(intent.type)
            is CommandsIntent.User.InputChanged -> reduce(CommandsAction.InputChanged(intent.key, intent.value))
            CommandsIntent.User.Submit -> state.form?.let { send(it, state.inputs) }
            CommandsIntent.User.CancelForm -> reduce(CommandsAction.FormCancelled)
            CommandsIntent.User.Refresh -> load()
        }
    }

    private fun select(type: CommandType) {
        val spec = state.specs.firstOrNull { it.type == type } ?: return
        if (!spec.enabled || state.sending) return
        if (type.params.isEmpty()) send(type, emptyMap()) else reduce(CommandsAction.FormOpened(type))
    }

    private fun load() {
        reduce(CommandsAction.LoadStarted)
        scope.launch {
            when (val result = loadOverview(deviceId)) {
                is Outcome.Success -> reduce(CommandsAction.Loaded(result.value.specs, result.value.history))
                is Outcome.Failure -> reduce(CommandsAction.LoadFailed(result.error))
            }
        }
    }

    private fun send(type: CommandType, inputs: Map<String, String>) {
        if (state.sending) return
        reduce(CommandsAction.SendStarted)
        scope.launch {
            when (val result = sendCommand(deviceId, type, inputs)) {
                is Outcome.Failure -> reduce(CommandsAction.SendFailed(result.error))
                is Outcome.Success -> {
                    reduce(CommandsAction.SendSucceeded(result.value))
                    track(result.value.cmdId)
                }
            }
        }
    }

    private fun track(cmdId: String) {
        scope.launch { trackCommand(cmdId).collect { reduce(CommandsAction.RecordUpdated(it)) } }
    }
}
