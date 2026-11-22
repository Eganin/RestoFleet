package ru.restofleet.console.feature.incidents.ui.viewmodel

import ru.restofleet.console.core.mvi.Reducer
import ru.restofleet.console.feature.incidents.domain.Incident
import ru.restofleet.console.feature.incidents.domain.IncidentLists

class IncidentsReducer : Reducer<IncidentsAction, IncidentsSession> {
    override fun reduce(action: IncidentsAction, state: IncidentsSession): IncidentsSession = when (action) {
        IncidentsAction.LoadStarted -> state.copy(loading = state.lists == null, error = null)
        is IncidentsAction.Loaded -> state.copy(lists = action.lists, loading = false, error = null)
        is IncidentsAction.LoadFailed -> state.copy(loading = false, error = action.error)
        is IncidentsAction.Pushed -> state.copy(lists = state.lists?.upsert(action.incident))
    }

    private fun IncidentLists.upsert(incident: Incident): IncidentLists {
        val rest = { list: List<Incident> -> list.filter { it.id != incident.id } }
        return if (incident.resolved) {
            copy(open = rest(open), resolved = (rest(resolved) + incident).sortedByDescending { it.startedAt })
        } else {
            copy(open = (rest(open) + incident).sortedByDescending { it.startedAt }, resolved = rest(resolved))
        }
    }
}
