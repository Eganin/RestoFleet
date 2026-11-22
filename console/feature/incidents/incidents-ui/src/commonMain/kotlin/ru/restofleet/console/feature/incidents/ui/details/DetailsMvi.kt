package ru.restofleet.console.feature.incidents.ui.details

import androidx.compose.runtime.Immutable
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.designsystem.StatusTone
import ru.restofleet.console.core.mvi.contract.MviAction
import ru.restofleet.console.core.mvi.contract.MviEffect
import ru.restofleet.console.core.mvi.contract.MviIntent
import ru.restofleet.console.core.mvi.contract.MviState
import ru.restofleet.console.feature.incidents.domain.Incident
import ru.restofleet.console.feature.incidents.domain.IncidentDetails

data class IncidentDetailsSession(
    val details: IncidentDetails? = null,
    val loading: Boolean = true,
    val error: ConsoleError? = null,
    val comment: String = "",
    val acknowledging: Boolean = false,
    val ackError: ConsoleError? = null,
) : MviState

sealed interface IncidentDetailsIntent : MviIntent {
    sealed interface User : IncidentDetailsIntent {
        data class CommentChanged(val text: String) : User
        data object Acknowledge : User
        data object Refresh : User
        data object Back : User
    }
}

sealed interface IncidentDetailsAction : MviAction {
    data object LoadStarted : IncidentDetailsAction
    data class Loaded(val details: IncidentDetails) : IncidentDetailsAction
    data class LoadFailed(val error: ConsoleError) : IncidentDetailsAction
    data class CommentChanged(val text: String) : IncidentDetailsAction
    data object AckStarted : IncidentDetailsAction
    data class Acknowledged(val incident: Incident) : IncidentDetailsAction
    data class AckFailed(val error: ConsoleError) : IncidentDetailsAction
}

sealed interface IncidentDetailsEffect : MviEffect {
    data class ShowMessage(val text: String) : IncidentDetailsEffect
}

@Immutable
data class IncidentDetailsUiState(
    val loading: Boolean,
    val errorText: String?,
    val title: String,
    val statusText: String,
    val statusTone: StatusTone,
    val ruleId: String,
    val restaurants: String,
    val explanation: List<String>,
    val errorsLine: String?,
    val ackInfo: String?,
    val canAcknowledge: Boolean,
    val comment: String,
    val commentError: String?,
    val acknowledging: Boolean,
    val ackErrorText: String?,
)
