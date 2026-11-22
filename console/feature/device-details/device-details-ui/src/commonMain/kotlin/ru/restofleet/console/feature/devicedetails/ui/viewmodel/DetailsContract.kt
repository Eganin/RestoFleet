package ru.restofleet.console.feature.devicedetails.ui.viewmodel

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.mvi.contract.MviAction
import ru.restofleet.console.core.mvi.contract.MviEffect
import ru.restofleet.console.core.mvi.contract.MviIntent
import ru.restofleet.console.feature.devicedetails.domain.DeviceCard
import ru.restofleet.console.feature.devicedetails.domain.EventsPage
import ru.restofleet.console.feature.devicedetails.domain.ProbeResult
import ru.restofleet.console.feature.devicedetails.domain.TempPoint
import ru.restofleet.console.feature.devicedetails.domain.Telemetry
import ru.restofleet.console.feature.devicedetails.ui.viewmodel.model.DetailsTab

sealed interface DetailsIntent : MviIntent {
    sealed interface User : DetailsIntent {
        data class SelectTab(val tab: DetailsTab) : User
        data object Refresh : User
        data object LoadMoreEvents : User
        data object OpenCommands : User
        data object CloseCommands : User
        data object Back : User
    }
}

sealed interface DetailsAction : MviAction {
    data object CardStarted : DetailsAction
    data class CardLoaded(val card: DeviceCard, val temp: List<TempPoint>) : DetailsAction
    data class CardFailed(val error: ConsoleError) : DetailsAction
    data class TabSelected(val tab: DetailsTab) : DetailsAction
    data class ProbesLoaded(val probes: List<ProbeResult>) : DetailsAction
    data class ProbesFailed(val error: ConsoleError) : DetailsAction
    data object EventsMoreStarted : DetailsAction
    data class EventsLoaded(val page: EventsPage) : DetailsAction
    data class EventsFailed(val error: ConsoleError) : DetailsAction
    data class TelemetryPushed(val telemetry: Telemetry) : DetailsAction
    data class StatusChanged(val status: String, val lastSeenAt: Long?) : DetailsAction
    data class CommandSheet(val visible: Boolean) : DetailsAction
}

sealed interface DetailsEffect : MviEffect {
    data class ShowMessage(val text: String) : DetailsEffect
}
