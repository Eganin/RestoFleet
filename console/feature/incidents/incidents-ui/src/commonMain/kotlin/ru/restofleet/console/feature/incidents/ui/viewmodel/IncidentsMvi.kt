package ru.restofleet.console.feature.incidents.ui.viewmodel

import androidx.compose.runtime.Immutable
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.designsystem.StatusTone
import ru.restofleet.console.core.mvi.contract.MviAction
import ru.restofleet.console.core.mvi.contract.MviEffect
import ru.restofleet.console.core.mvi.contract.MviIntent
import ru.restofleet.console.core.mvi.contract.MviState
import ru.restofleet.console.feature.incidents.domain.Incident
import ru.restofleet.console.feature.incidents.domain.IncidentLists

data class IncidentsSession(
    val lists: IncidentLists? = null,
    val loading: Boolean = true,
    val error: ConsoleError? = null,
) : MviState

sealed interface IncidentsIntent : MviIntent {
    sealed interface User : IncidentsIntent {
        data object Refresh : User
        data class Open(val incidentId: String) : User
    }
}

sealed interface IncidentsAction : MviAction {
    data object LoadStarted : IncidentsAction
    data class Loaded(val lists: IncidentLists) : IncidentsAction
    data class LoadFailed(val error: ConsoleError) : IncidentsAction
    data class Pushed(val incident: Incident) : IncidentsAction
}

sealed interface IncidentsEffect : MviEffect {
    data class ShowMessage(val text: String) : IncidentsEffect
}

@Immutable
data class IncidentRowUi(val id: String, val title: String, val subtitle: String, val ruleId: String, val tone: StatusTone)

@Immutable
data class IncidentsUiState(
    val open: List<IncidentRowUi>,
    val resolved: List<IncidentRowUi>,
    val loading: Boolean,
    val errorText: String?,
    val emptyText: String?,
)
