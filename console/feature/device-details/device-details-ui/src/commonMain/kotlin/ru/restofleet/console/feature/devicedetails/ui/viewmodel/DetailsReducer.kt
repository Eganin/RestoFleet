package ru.restofleet.console.feature.devicedetails.ui.viewmodel

import ru.restofleet.console.core.mvi.Reducer
import ru.restofleet.console.feature.devicedetails.domain.LoadTemperatureHistory
import ru.restofleet.console.feature.devicedetails.domain.TempPoint
import ru.restofleet.console.feature.devicedetails.ui.viewmodel.model.DetailsSession

class DetailsReducer : Reducer<DetailsAction, DetailsSession> {
    override fun reduce(action: DetailsAction, state: DetailsSession): DetailsSession = when (action) {
        DetailsAction.CardStarted -> state.copy(loading = state.card == null, error = null)
        is DetailsAction.CardLoaded -> state.copy(
            card = action.card, temp = action.temp, loading = false, error = null, knownNow = action.card.loadedAt,
        )
        is DetailsAction.CardFailed -> state.copy(loading = false, error = action.error)
        is DetailsAction.TabSelected -> state.copy(tab = action.tab)
        is DetailsAction.ProbesLoaded -> state.copy(probes = action.probes, probesError = null)
        is DetailsAction.ProbesFailed -> state.copy(probesError = action.error)
        DetailsAction.EventsMoreStarted -> state.copy(eventsLoadingMore = true, eventsError = null)
        is DetailsAction.EventsLoaded -> state.copy(
            events = if (action.page.offset == 0) action.page.items else state.events.orEmpty() + action.page.items,
            eventsHasMore = action.page.hasMore,
            eventsLoadingMore = false,
            eventsError = null,
        )
        is DetailsAction.EventsFailed -> state.copy(eventsLoadingMore = false, eventsError = action.error)
        is DetailsAction.TelemetryPushed -> push(state, action)
        is DetailsAction.StatusChanged -> state.copy(
            card = state.card?.let { it.copy(status = action.status, lastSeenAt = action.lastSeenAt ?: it.lastSeenAt) },
        )
        is DetailsAction.CommandSheet -> state.copy(commandSheet = action.visible)
    }

    private fun push(state: DetailsSession, action: DetailsAction.TelemetryPushed): DetailsSession {
        val t = action.telemetry
        val card = state.card ?: return state
        val point = t.tempC?.let { TempPoint(t.ts, it) }
        val points = if (point == null) {
            state.temp
        } else {
            (state.temp.filter { it.ts != point.ts } + point).sortedBy { it.ts }
                .filter { it.ts >= t.ts - LoadTemperatureHistory.WINDOW_MS }
        }
        return state.copy(card = card.copy(telemetry = t), temp = points, knownNow = maxOf(state.knownNow, t.ts))
    }
}
