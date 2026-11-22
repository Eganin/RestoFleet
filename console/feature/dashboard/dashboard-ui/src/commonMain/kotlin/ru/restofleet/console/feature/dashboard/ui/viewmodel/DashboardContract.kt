package ru.restofleet.console.feature.dashboard.ui.viewmodel

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.mvi.contract.MviAction
import ru.restofleet.console.core.mvi.contract.MviEffect
import ru.restofleet.console.core.mvi.contract.MviIntent
import ru.restofleet.console.feature.dashboard.domain.Dashboard
import ru.restofleet.console.feature.dashboard.domain.Summary

sealed interface DashboardIntent : MviIntent {
    sealed interface User : DashboardIntent {
        data object Refresh : User
        data class OpenDevice(val deviceId: String) : User
        data class OpenRestaurant(val restaurantId: String, val name: String) : User
        data object OpenAlerts : User
        data object Logout : User
    }
}

sealed interface DashboardAction : MviAction {
    data object LoadStarted : DashboardAction
    data class Loaded(val dashboard: Dashboard) : DashboardAction
    data class LoadFailed(val error: ConsoleError) : DashboardAction
    data class SummaryPushed(val summary: Summary) : DashboardAction
}

sealed interface DashboardEffect : MviEffect {
    data class ShowMessage(val text: String) : DashboardEffect
}
