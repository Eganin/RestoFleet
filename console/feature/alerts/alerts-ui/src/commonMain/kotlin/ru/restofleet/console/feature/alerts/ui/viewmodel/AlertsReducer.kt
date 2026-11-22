package ru.restofleet.console.feature.alerts.ui.viewmodel

import ru.restofleet.console.core.mvi.Reducer
import ru.restofleet.console.feature.alerts.domain.AlertItem
import ru.restofleet.console.feature.alerts.domain.AlertLists
import ru.restofleet.console.feature.alerts.domain.AlertState

class AlertsReducer : Reducer<AlertsAction, AlertsSession> {
    override fun reduce(action: AlertsAction, state: AlertsSession): AlertsSession = when (action) {
        AlertsAction.LoadStarted -> state.copy(loading = state.lists == null, error = null)
        is AlertsAction.Loaded -> state.copy(lists = action.lists, loading = false, error = null)
        is AlertsAction.LoadFailed -> state.copy(loading = false, error = action.error)
        is AlertsAction.TabSelected -> state.copy(tab = action.tab)
        is AlertsAction.Pushed -> state.copy(lists = state.lists?.upsert(action.alert))
        is AlertsAction.ResolveRequested ->
            state.copy(resolving = action.alert, comment = "", commentError = null, actionError = null)
        is AlertsAction.CommentChanged -> state.copy(comment = action.text, commentError = null)
        is AlertsAction.CommentRejected -> state.copy(commentError = action.reason, acting = false)
        AlertsAction.DialogDismissed ->
            state.copy(resolving = null, comment = "", commentError = null, actionError = null)
        AlertsAction.ActionStarted -> state.copy(acting = true, actionError = null)
        is AlertsAction.ActionSucceeded -> state.copy(
            lists = state.lists?.upsert(action.alert), acting = false, resolving = null, comment = "", commentError = null,
        )
        is AlertsAction.ActionFailed -> state.copy(acting = false, actionError = action.error)
    }

    /** Тревога переезжает между списками по своему состоянию; в списке сначала новые. */
    private fun AlertLists.upsert(alert: AlertItem): AlertLists {
        fun List<AlertItem>.without() = filter { it.id != alert.id }
        fun List<AlertItem>.with(target: AlertState) =
            if (alert.state == target) (without() + alert).sortedByDescending { it.openedAt } else without()
        return copy(
            open = open.with(AlertState.Open),
            acknowledged = acknowledged.with(AlertState.Acknowledged),
            resolved = resolved.with(AlertState.Resolved),
        )
    }
}
