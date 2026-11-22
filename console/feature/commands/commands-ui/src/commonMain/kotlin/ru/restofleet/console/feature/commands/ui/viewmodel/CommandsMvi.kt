package ru.restofleet.console.feature.commands.ui.viewmodel

import androidx.compose.runtime.Immutable
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.designsystem.StatusTone
import ru.restofleet.console.core.mvi.contract.MviAction
import ru.restofleet.console.core.mvi.contract.MviEffect
import ru.restofleet.console.core.mvi.contract.MviIntent
import ru.restofleet.console.core.mvi.contract.MviState
import ru.restofleet.console.feature.commands.domain.CommandRecord
import ru.restofleet.console.feature.commands.domain.CommandSpec
import ru.restofleet.console.feature.commands.domain.CommandType

data class CommandsSession(
    val specs: List<CommandSpec> = emptyList(),
    val history: List<CommandRecord> = emptyList(),
    val loading: Boolean = true,
    val error: ConsoleError? = null,
    val form: CommandType? = null,
    val inputs: Map<String, String> = emptyMap(),
    val sending: Boolean = false,
    val sendError: ConsoleError? = null,
) : MviState

sealed interface CommandsIntent : MviIntent {
    sealed interface User : CommandsIntent {
        data class Select(val type: CommandType) : User
        data class InputChanged(val key: String, val value: String) : User
        data object Submit : User
        data object CancelForm : User
        data object Refresh : User
    }
}

sealed interface CommandsAction : MviAction {
    data object LoadStarted : CommandsAction
    data class Loaded(val specs: List<CommandSpec>, val history: List<CommandRecord>) : CommandsAction
    data class LoadFailed(val error: ConsoleError) : CommandsAction
    data class HistoryLoaded(val history: List<CommandRecord>) : CommandsAction
    data class FormOpened(val type: CommandType) : CommandsAction
    data class InputChanged(val key: String, val value: String) : CommandsAction
    data object FormCancelled : CommandsAction
    data object SendStarted : CommandsAction
    data class SendSucceeded(val record: CommandRecord) : CommandsAction
    data class SendFailed(val error: ConsoleError) : CommandsAction
    data class RecordUpdated(val record: CommandRecord) : CommandsAction
}

sealed interface CommandsEffect : MviEffect {
    data class ShowMessage(val text: String) : CommandsEffect
}

@Immutable
data class SpecUi(val type: CommandType, val title: String, val subtitle: String, val enabled: Boolean, val reason: String?)

@Immutable
data class FieldUi(val key: String, val label: String, val value: String, val error: Boolean)

@Immutable
data class CommandRowUi(
    val cmdId: String,
    val title: String,
    val text: String,
    val statusText: String,
    val tone: StatusTone,
)

@Immutable
data class CommandsUiState(
    val specs: List<SpecUi>,
    val history: List<CommandRowUi>,
    val loading: Boolean,
    val errorText: String?,
    val formTitle: String?,
    val fields: List<FieldUi>,
    val sending: Boolean,
    val sendErrorText: String?,
)
