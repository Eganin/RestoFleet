package ru.restofleet.console.feature.dashboard.ui.viewmodel

import ru.restofleet.console.core.mvi.Reducer
import ru.restofleet.console.feature.dashboard.ui.viewmodel.model.DashboardSession

class DashboardReducer : Reducer<DashboardAction, DashboardSession> {
    override fun reduce(action: DashboardAction, state: DashboardSession): DashboardSession = when (action) {
        DashboardAction.LoadStarted -> state.copy(loading = true)
        is DashboardAction.Loaded -> DashboardSession(loading = false, data = action.dashboard, error = null)
        is DashboardAction.LoadFailed -> state.copy(loading = false, error = action.error)
        is DashboardAction.SummaryPushed -> state.data?.let {
            state.copy(data = it.copy(summary = action.summary), error = null)
        } ?: state
    }
}
