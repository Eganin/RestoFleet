package ru.restofleet.console.feature.commands.ui.viewmodel

import ru.restofleet.console.core.mvi.Reducer

class CommandsReducer : Reducer<CommandsAction, CommandsSession> {
    override fun reduce(action: CommandsAction, state: CommandsSession): CommandsSession = when (action) {
        CommandsAction.LoadStarted -> state.copy(loading = state.specs.isEmpty(), error = null)
        is CommandsAction.Loaded -> state.copy(specs = action.specs, history = action.history, loading = false, error = null)
        is CommandsAction.LoadFailed -> state.copy(loading = false, error = action.error)
        is CommandsAction.HistoryLoaded -> state.copy(history = action.history)
        is CommandsAction.FormOpened -> state.copy(form = action.type, inputs = emptyMap(), sendError = null)
        is CommandsAction.InputChanged -> state.copy(inputs = state.inputs + (action.key to action.value), sendError = null)
        CommandsAction.FormCancelled -> state.copy(form = null, inputs = emptyMap(), sendError = null)
        CommandsAction.SendStarted -> state.copy(sending = true, sendError = null)
        is CommandsAction.SendSucceeded -> state.copy(
            history = listOf(action.record) + state.history.filter { it.cmdId != action.record.cmdId },
            sending = false, form = null, inputs = emptyMap(), sendError = null,
        )
        is CommandsAction.SendFailed -> state.copy(sending = false, sendError = action.error)
        is CommandsAction.RecordUpdated ->
            state.copy(history = state.history.map { if (it.cmdId == action.record.cmdId) action.record else it })
    }
}
