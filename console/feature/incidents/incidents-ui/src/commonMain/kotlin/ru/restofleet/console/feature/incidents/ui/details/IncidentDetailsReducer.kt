package ru.restofleet.console.feature.incidents.ui.details

import ru.restofleet.console.core.mvi.Reducer

class IncidentDetailsReducer : Reducer<IncidentDetailsAction, IncidentDetailsSession> {
    override fun reduce(action: IncidentDetailsAction, state: IncidentDetailsSession): IncidentDetailsSession = when (action) {
        IncidentDetailsAction.LoadStarted -> state.copy(loading = state.details == null, error = null)
        is IncidentDetailsAction.Loaded -> state.copy(details = action.details, loading = false, error = null)
        is IncidentDetailsAction.LoadFailed -> state.copy(loading = false, error = action.error)
        is IncidentDetailsAction.CommentChanged -> state.copy(comment = action.text, ackError = null)
        IncidentDetailsAction.AckStarted -> state.copy(acknowledging = true, ackError = null)
        is IncidentDetailsAction.Acknowledged -> state.copy(
            details = state.details?.copy(incident = action.incident), acknowledging = false, comment = "",
        )
        is IncidentDetailsAction.AckFailed -> state.copy(acknowledging = false, ackError = action.error)
    }
}
