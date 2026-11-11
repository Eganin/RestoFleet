package ru.restofleet.agent.presentation.status.viewmodel

import ru.restofleet.agent.presentation.status.viewmodel.model.StatusSession
import ru.restofleet.console.core.mvi.Reducer

class StatusReducer : Reducer<StatusAction, StatusSession> {
    override fun reduce(action: StatusAction, state: StatusSession): StatusSession = when (action) {
        StatusAction.RefreshStarted -> state.copy(isRefreshing = true)
        is StatusAction.Loaded -> state.copy(status = action.status, isRefreshing = false)
        StatusAction.RefreshFinished -> state.copy(isRefreshing = false)
    }
}
