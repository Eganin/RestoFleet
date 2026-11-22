package ru.restofleet.console.feature.devicedetails.ui.viewmodel

import androidx.compose.runtime.Immutable
import ru.restofleet.console.core.designsystem.StatusTone
import ru.restofleet.console.core.ui.ChartPoint
import ru.restofleet.console.core.ui.PhaseSegment

@Immutable
data class TileUi(val label: String, val value: String, val caption: String?, val tone: StatusTone?, val alert: Boolean)

@Immutable
data class ProbeLastUi(val target: String, val ok: Boolean, val summary: String, val phases: List<PhaseSegment>)

@Immutable
data class ProbeRowUi(val ago: String, val target: String, val ok: Boolean, val text: String)

@Immutable
data class EventRowUi(val ago: String, val title: String, val text: String)

@Immutable
data class DetailsUiState(
    val title: String,
    val subtitle: String,
    val statusText: String,
    val statusTone: StatusTone,
    val rules: List<String>,
    val tabs: List<String>,
    val selectedTab: Int,
    val tiles: List<TileUi>,
    val chart: List<ChartPoint>,
    val chartThreshold: Double,
    val chartCaption: String,
    val footer: String,
    val probesLast: List<ProbeLastUi>,
    val probeRows: List<ProbeRowUi>,
    val probesLoaded: Boolean,
    val probesErrorText: String?,
    val eventRows: List<EventRowUi>,
    val eventsLoaded: Boolean,
    val canLoadMoreEvents: Boolean,
    val eventsErrorText: String?,
    val loading: Boolean,
    val errorText: String?,
    val commandSheet: Boolean,
    val commandsEnabled: Boolean,
    val shadowSelected: Boolean,
)
