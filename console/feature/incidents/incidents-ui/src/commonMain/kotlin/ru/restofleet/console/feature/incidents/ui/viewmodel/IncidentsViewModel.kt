package ru.restofleet.console.feature.incidents.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import ru.restofleet.console.core.mvi.BaseMviViewModel
import ru.restofleet.console.core.mvi.MviStore
import ru.restofleet.console.core.mvi.defaultViewModelScope
import ru.restofleet.console.feature.incidents.domain.IncidentsEvent
import ru.restofleet.console.feature.incidents.domain.LoadIncidents
import ru.restofleet.console.feature.incidents.domain.ObserveIncidentsEvents
import ru.restofleet.console.feature.incidents.ui.api.IIncidentsRouter
import ru.restofleet.console.feature.incidents.ui.viewmodel.mapper.IncidentsUiStateMapper
import ru.restofleet.shared.kernel.Outcome

/** Список: открытые и закрытые за сутки; обновляется по WebSocket (`incident`). */
class IncidentsViewModel(
    private val loadIncidents: LoadIncidents,
    observeEvents: ObserveIncidentsEvents,
    private val router: IIncidentsRouter,
    scope: CoroutineScope = defaultViewModelScope(),
) : BaseMviViewModel<IncidentsSession, IncidentsUiState, IncidentsIntent, IncidentsAction, IncidentsEffect>(
    store = MviStore(IncidentsSession(), IncidentsReducer(), IncidentsUiStateMapper()::map),
    scope = scope,
) {
    private val reloads = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { for (ignored in reloads) load() }
        scope.launch {
            observeEvents().collect {
                when (it) {
                    is IncidentsEvent.Pushed -> reduce(IncidentsAction.Pushed(it.incident))
                    IncidentsEvent.ReloadNeeded -> reloads.trySend(Unit)
                }
            }
        }
        reloads.trySend(Unit)
    }

    override fun onIntent(intent: IncidentsIntent) {
        when (intent) {
            IncidentsIntent.User.Refresh -> reloads.trySend(Unit)
            is IncidentsIntent.User.Open -> router.openIncident(intent.incidentId)
        }
    }

    override fun onDestroy() {
        reloads.close()
        super.onDestroy()
    }

    private suspend fun load() {
        reduce(IncidentsAction.LoadStarted)
        when (val result = loadIncidents()) {
            is Outcome.Success -> reduce(IncidentsAction.Loaded(result.value))
            is Outcome.Failure -> reduce(IncidentsAction.LoadFailed(result.error))
        }
    }
}
