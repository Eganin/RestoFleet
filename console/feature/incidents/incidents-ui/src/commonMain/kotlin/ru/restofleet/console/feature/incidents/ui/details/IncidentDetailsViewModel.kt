package ru.restofleet.console.feature.incidents.ui.details

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.mvi.BaseMviViewModel
import ru.restofleet.console.core.mvi.MviStore
import ru.restofleet.console.core.mvi.defaultViewModelScope
import ru.restofleet.console.feature.incidents.domain.AcknowledgeIncident
import ru.restofleet.console.feature.incidents.domain.IncidentsEvent
import ru.restofleet.console.feature.incidents.domain.LoadIncidentDetails
import ru.restofleet.console.feature.incidents.domain.ObserveIncidentsEvents
import ru.restofleet.console.feature.incidents.ui.api.IIncidentDetailsRouter
import ru.restofleet.shared.kernel.Outcome

class IncidentDetailsViewModel(
    private val incidentId: String,
    private val loadDetails: LoadIncidentDetails,
    private val acknowledge: AcknowledgeIncident,
    observeEvents: ObserveIncidentsEvents,
    private val router: IIncidentDetailsRouter,
    scope: CoroutineScope = defaultViewModelScope(),
) : BaseMviViewModel<IncidentDetailsSession, IncidentDetailsUiState, IncidentDetailsIntent, IncidentDetailsAction, IncidentDetailsEffect>(
    store = MviStore(IncidentDetailsSession(), IncidentDetailsReducer(), IncidentDetailsUiStateMapper()::map),
    scope = scope,
) {
    private val reloads = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { for (ignored in reloads) load() }
        scope.launch {
            observeEvents().collect {
                when (it) {
                    is IncidentsEvent.Pushed -> if (it.incident.id == incidentId) reloads.trySend(Unit)
                    IncidentsEvent.ReloadNeeded -> reloads.trySend(Unit)
                }
            }
        }
        reloads.trySend(Unit)
    }

    override fun onIntent(intent: IncidentDetailsIntent) {
        when (intent) {
            is IncidentDetailsIntent.User.CommentChanged -> reduce(IncidentDetailsAction.CommentChanged(intent.text))
            IncidentDetailsIntent.User.Acknowledge -> acknowledgeNow()
            IncidentDetailsIntent.User.Refresh -> reloads.trySend(Unit)
            IncidentDetailsIntent.User.Back -> router.back()
        }
    }

    override fun onDestroy() {
        reloads.close()
        super.onDestroy()
    }

    private fun acknowledgeNow() {
        val incident = state.details?.incident ?: return
        if (state.acknowledging || incident.resolved || incident.ackBy != null) return
        reduce(IncidentDetailsAction.AckStarted)
        scope.launch {
            when (val result = acknowledge(incidentId, state.comment)) {
                is Outcome.Success -> reduce(IncidentDetailsAction.Acknowledged(result.value))
                is Outcome.Failure -> {
                    reduce(IncidentDetailsAction.AckFailed(result.error))
                    if (result.error is ConsoleError.Conflict) reloads.trySend(Unit)
                }
            }
        }
    }

    private suspend fun load() {
        reduce(IncidentDetailsAction.LoadStarted)
        when (val result = loadDetails(incidentId)) {
            is Outcome.Success -> reduce(IncidentDetailsAction.Loaded(result.value))
            is Outcome.Failure -> reduce(IncidentDetailsAction.LoadFailed(result.error))
        }
    }
}
