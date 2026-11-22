package ru.restofleet.console.feature.alerts.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.mvi.BaseMviViewModel
import ru.restofleet.console.core.mvi.MviStore
import ru.restofleet.console.core.mvi.defaultViewModelScope
import ru.restofleet.console.feature.alerts.domain.AcknowledgeAlert
import ru.restofleet.console.feature.alerts.domain.AlertItem
import ru.restofleet.console.feature.alerts.domain.AlertsEvent
import ru.restofleet.console.feature.alerts.domain.LoadAlerts
import ru.restofleet.console.feature.alerts.domain.ObserveAlertsEvents
import ru.restofleet.console.feature.alerts.domain.ResolveAlert
import ru.restofleet.console.feature.alerts.ui.api.IAlertsRouter
import ru.restofleet.console.feature.alerts.ui.viewmodel.mapper.AlertsUiStateMapper
import ru.restofleet.shared.kernel.Outcome

/**
 * Тревоги по вкладкам. Свайп вправо — подтвердить сразу, свайп влево — закрыть через диалог с обязательным
 * комментарием. Обновления приходят по WebSocket (`alert`): тревога переезжает между вкладками.
 */
class AlertsViewModel(
    private val loadAlerts: LoadAlerts,
    private val acknowledge: AcknowledgeAlert,
    private val resolve: ResolveAlert,
    observeEvents: ObserveAlertsEvents,
    private val router: IAlertsRouter,
    scope: CoroutineScope = defaultViewModelScope(),
) : BaseMviViewModel<AlertsSession, AlertsUiState, AlertsIntent, AlertsAction, AlertsEffect>(
    store = MviStore(AlertsSession(), AlertsReducer(), AlertsUiStateMapper()::map),
    scope = scope,
) {
    private val reloads = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { for (ignored in reloads) load() }
        scope.launch {
            observeEvents().collect {
                when (it) {
                    is AlertsEvent.Pushed -> reduce(AlertsAction.Pushed(it.alert))
                    AlertsEvent.ReloadNeeded -> reloads.trySend(Unit)
                }
            }
        }
        reloads.trySend(Unit)
    }

    override fun onIntent(intent: AlertsIntent) {
        when (intent) {
            is AlertsIntent.User.SelectTab -> reduce(AlertsAction.TabSelected(intent.tab))
            is AlertsIntent.User.Acknowledge -> alert(intent.id)?.let { act { acknowledge(it.id) } }
            is AlertsIntent.User.RequestResolve -> alert(intent.id)?.let { reduce(AlertsAction.ResolveRequested(it)) }
            is AlertsIntent.User.CommentChanged -> reduce(AlertsAction.CommentChanged(intent.text))
            AlertsIntent.User.ConfirmResolve -> confirmResolve()
            AlertsIntent.User.DismissDialog -> reduce(AlertsAction.DialogDismissed)
            AlertsIntent.User.Refresh -> reloads.trySend(Unit)
            is AlertsIntent.User.OpenDevice -> router.openDevice(intent.deviceId)
        }
    }

    override fun onDestroy() {
        reloads.close()
        super.onDestroy()
    }

    private fun alert(id: String): AlertItem? =
        state.lists?.let { l -> (l.open + l.acknowledged + l.resolved).firstOrNull { it.id == id } }

    private fun confirmResolve() {
        val target = state.resolving ?: return
        if (state.acting) return
        act { resolve(target.id, state.comment) }
    }

    private fun act(call: suspend () -> Outcome<AlertItem, ConsoleError>) {
        if (state.acting) return
        reduce(AlertsAction.ActionStarted)
        scope.launch {
            when (val result = call()) {
                is Outcome.Success -> reduce(AlertsAction.ActionSucceeded(result.value))
                is Outcome.Failure -> onFailure(result.error)
            }
        }
    }

    private fun onFailure(error: ConsoleError) {
        if (error is ConsoleError.InvalidInput) {
            reduce(AlertsAction.CommentRejected(error.message))
        } else {
            reduce(AlertsAction.ActionFailed(error))
            if (error is ConsoleError.Conflict) reloads.trySend(Unit)
        }
    }

    private suspend fun load() {
        reduce(AlertsAction.LoadStarted)
        when (val result = loadAlerts()) {
            is Outcome.Success -> reduce(AlertsAction.Loaded(result.value))
            is Outcome.Failure -> reduce(AlertsAction.LoadFailed(result.error))
        }
    }
}
